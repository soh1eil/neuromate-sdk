package com.neuromate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Staying on duty: {@code client.watch(strategy)}. A watcher sends a heartbeat every 30 s (so the agent shows as on duty and
 * challenges are held for it instead of expiring), reconnects with backoff, resumes games that were running when it started,
 * accepts challenges by the auto-accept rules the owner set on the agent page and calls your strategy for every move. A strategy that
 * throws never forfeits a game: the SDK plays a random legal move and logs it.
 */
public final class Watcher {
    /** Options of a watcher; every field has a default. */
    public static final class Options {
        /** Overrides the owner's rules from the agent page when set. */
        public Rules rules;
        public boolean serverRules = true;
        public long heartbeatEveryMs = 30_000, busyPollMs = 3_000, maxBackoffMs = 60_000;
        public double waitSeconds = 25;
        public Consumer<String> log;
        public String clientName;
        /** Options passed to every {@link Client#play}; gameId is set by the watcher. */
        public Client.PlayOptions play = new Client.PlayOptions();
    }

    private final Client client;
    private final Client.Strategy handler;
    private final Options o;
    private final Consumer<String> log;
    private final String clientName;
    private volatile boolean stopped;
    private volatile Rules rules;
    private volatile boolean standbyOk = true;
    private final Map<String, Thread> games = new ConcurrentHashMap<>();
    private final Map<String, Long> cooldown = new ConcurrentHashMap<>();
    private final Set<String> seen = ConcurrentHashMap.newKeySet();
    private final Object wake = new Object();
    private volatile int accepted, played;

    public Watcher(Client client, Client.Strategy handler, Options options) {
        this.client = client;
        this.o = options == null ? new Options() : options;
        this.log = o.log != null ? o.log : m -> System.err.println(java.time.LocalTime.now().withNano(0) + " " + m);
        this.clientName = o.clientName != null ? o.clientName : "neuromate-java/" + Client.VERSION + " watch";
        this.rules = o.rules != null ? o.rules : new Rules();
        this.handler = pos -> {
            try {
                return handler.choose(pos);
            } catch (Exception e) {
                log.accept("handler error in " + pos.gameId + ": " + e + "; playing a fallback move");
                return pos.legalMoves.isEmpty() ? Client.Move.of("") : Client.Move.of(pos.legalMoves.get(new java.util.Random().nextInt(pos.legalMoves.size())).uci);
            }
        };
    }

    public int accepted() { return accepted; }
    public int played() { return played; }
    public Rules rules() { return rules; }
    public boolean stopped() { return stopped; }

    /** Ends the loop (the duty is ended cleanly) and wakes any pause. Safe from any thread. */
    public void stop() {
        stopped = true;
        synchronized (wake) { wake.notifyAll(); }
    }

    private void nap(long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (wake) {
            while (!stopped) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return;
                try { wake.wait(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); stopped = true; return; }
            }
        }
    }

    private void beat() {
        if (!standbyOk) return;
        Duty d;
        try {
            d = client.standby(clientName, false);
        } catch (NeuromateException.Protocol e) {
            if (String.valueOf(e.getMessage()).contains("unknown tool")) {
                standbyOk = false;
                log.accept("this server has no standby tool: running without heartbeats (tool calls still keep you present for two minutes)");
                return;
            }
            throw e;
        }
        if (o.serverRules && o.rules == null) {
            if (!d.rules.equals(rules)) log.accept("auto-accept rules updated: " + (d.rules.enabled ? "on" : "off"));
            rules = d.rules;
        }
    }

    private void heartbeatLoop() {
        int fails = 0;
        while (!stopped) {
            nap(o.heartbeatEveryMs);
            if (stopped) return;
            try {
                beat();
                fails = 0;
            } catch (NeuromateException.Auth e) {
                log.accept("heartbeat: the API key was rejected");
                return;
            } catch (NeuromateException e) {
                fails++;
                if (fails == 1 || fails == 5) log.accept("heartbeat failed (" + shorten(e.getMessage(), 100) + "); retrying");
            }
        }
    }

    private static String shorten(String s, int n) {
        s = String.valueOf(s);
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** Runs until {@link #stop()}; throws {@link NeuromateException.Auth} when the API key is rejected. */
    public void run() {
        log.accept("on duty as " + clientName);
        long backoff = 1000;
        try {
            beat();
        } catch (NeuromateException.Auth e) {
            throw e;
        } catch (NeuromateException e) {
            log.accept("first heartbeat failed (" + shorten(e.getMessage(), 100) + "); will keep trying");
        }
        Thread hb = new Thread(this::heartbeatLoop, "neuromate-heartbeat");
        hb.setDaemon(true);
        hb.start();
        boolean first = true;
        long last = 0;
        try {
            while (!stopped) {
                try {
                    long now = System.currentTimeMillis();
                    if (first || now - last > 30_000 || !games.isEmpty()) {
                        reconcile();
                        last = System.currentTimeMillis();
                        first = false;
                    }
                    reap();
                    if (!games.isEmpty()) {
                        nap(o.busyPollMs);
                    } else {
                        Client.Turn turn = client.waitForTurn("", o.waitSeconds);
                        events(turn.events);
                        if (turn.position != null && !turn.position.isOver() && !games.containsKey(turn.position.gameId)) spawn(turn.position.gameId);
                    }
                    backoff = 1000;
                } catch (NeuromateException.Auth e) {
                    log.accept("the API key was rejected (" + shorten(e.getMessage(), 80) + "); stopping");
                    throw e;
                } catch (NeuromateException.RateLimited e) {
                    nap(Math.min(e.retryAfterMs, 30_000));
                } catch (NeuromateException e) {
                    log.accept("connection problem (" + shorten(e.getMessage(), 100) + "); retrying in " + backoff / 1000 + "s");
                    nap((long) (backoff * (0.5 + Math.random())));
                    backoff = Math.min(backoff * 2, o.maxBackoffMs);
                }
            }
        } finally {
            stopped = true;
            synchronized (wake) { wake.notifyAll(); }
            try { hb.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            for (Thread t : games.values()) {
                try { t.join(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            }
            if (standbyOk) {
                try {
                    client.standby(clientName, true);
                    log.accept("off duty");
                } catch (NeuromateException ignored) {
                    // best effort
                }
            }
        }
    }

    private void reconcile() {
        Map<String, Object> lobby = client.whoami();
        for (Object g : Json.array(lobby.get("active_games"))) {
            String id = Json.str(Json.object(g).get("game_id"));
            if (!games.containsKey(id)) spawn(id);
        }
        List<Object> evs = Json.array(lobby.get("events"));
        events(evs);
        java.util.Set<String> pending = new java.util.HashSet<>();
        long max = 0;
        for (Object e : evs) {
            Map<String, Object> ev = Json.object(e);
            max = Math.max(max, Json.num(ev.get("seq")));
            if (Json.str(ev.get("type")).equals("challenge_received")) pending.add(Json.str(Json.object(ev.get("data")).get("challenge_id")));
        }
        for (Object c : Json.array(lobby.get("incoming_challenges"))) {
            Map<String, Object> ch = Json.object(c);
            String st = Json.str(ch.get("status")), id = Json.str(ch.get("id"));
            if ((st.isEmpty() || st.equals("pending")) && !pending.contains(id)) challenge(id, Rules.infoFromChallenge(ch), Json.str(Json.object(ch.get("from")).get("name")));
        }
        if (max > 0) client.listChallenges(max);
    }

    private void events(List<? extends Object> evs) {
        for (Object e : evs) {
            Map<String, Object> ev = Json.object(e), d = Json.object(ev.get("data"));
            switch (Json.str(ev.get("type"))) {
                case "challenge_received":
                    String who = Json.str(d.get("from"));
                    challenge(Json.str(d.get("challenge_id")), Rules.infoFromEvent(ev), who.isEmpty() ? "?" : who);
                    break;
                case "game_started":
                    String gid = Json.str(d.get("game_id"));
                    if (!gid.isEmpty() && !games.containsKey(gid)) spawn(gid);
                    break;
                case "tournament_offline_warning":
                case "tournament_withdrawn":
                case "queue_dropped":
                    log.accept(Json.str(ev.get("type")) + ": " + (Json.str(d.get("next")).isEmpty() ? Json.str(d.get("message")) : Json.str(d.get("next"))));
                    break;
                case "challenge_declined":
                    log.accept("challenge declined: " + Json.str(d.get("reason")));
                    break;
                default:
                    break;
            }
        }
    }

    private void challenge(String cid, Map<String, Object> info, String who) {
        if (cid.isEmpty()) return;
        Rules.Decision d = rules.evaluate(info, games.size());
        boolean first = !seen.contains(cid);
        switch (d.action) {
            case "accept":
                Position pos;
                try {
                    pos = client.acceptChallenge(cid);
                } catch (NeuromateException.Tool e) {
                    log.accept("could not accept " + cid + " from " + who + ": " + shorten(e.getMessage(), 120));
                    seen.add(cid);
                    return;
                }
                accepted++;
                seen.add(cid);
                log.accept("accepted " + cid + " from " + who + " (" + info.get("speed") + " " + info.get("variant") + "); game " + pos.gameId);
                spawn(pos.gameId);
                break;
            case "decline":
                if (first) log.accept("declined " + cid + " from " + who + ": " + d.reason);
                try {
                    client.declineChallenge(cid, shorten(d.reason, 200));
                } catch (NeuromateException.Tool ignored) {
                    // already gone
                }
                seen.add(cid);
                break;
            default:
                if (first) {
                    log.accept("challenge " + cid + " from " + who + " left for the owner: " + d.reason);
                    seen.add(cid);
                    if (seen.size() > 500) seen.clear();
                }
        }
    }

    private void spawn(String gameId) {
        if (gameId == null || gameId.isEmpty() || games.containsKey(gameId)) return;
        if (System.currentTimeMillis() - cooldown.getOrDefault(gameId, -100_000L) < 5000) return;
        Thread t = new Thread(() -> playGame(gameId), "neuromate-game-" + gameId);
        t.setDaemon(true);
        if (games.putIfAbsent(gameId, t) != null) return;
        t.start();
        log.accept("playing game " + gameId);
    }

    private void playGame(String gameId) {
        Client.PlayOptions po = copy(o.play);
        po.gameId = gameId;
        try {
            Position fin = client.play(handler, po);
            log.accept("game " + gameId + " over: " + fin.resultScore);
            played++;
        } catch (NeuromateException.Auth e) {
            log.accept("the API key was rejected while playing; stopping");
            stop();
        } catch (RuntimeException e) {
            log.accept("game " + gameId + ": " + shorten(e.getMessage(), 120));
            cooldown.put(gameId, System.currentTimeMillis());
        }
    }

    private static Client.PlayOptions copy(Client.PlayOptions s) {
        Client.PlayOptions p = new Client.PlayOptions();
        p.opponent = s.opponent; p.variant = s.variant; p.timeControl = s.timeControl; p.pool = s.pool; p.rated = s.rated;
        p.acceptChallenges = s.acceptChallenges; p.maxIllegal = s.maxIllegal; p.maxRetries = s.maxRetries; p.waitSeconds = s.waitSeconds;
        p.findTimeoutMs = s.findTimeoutMs; p.onPosition = s.onPosition; p.onDrawOffer = s.onDrawOffer; p.fallback = s.fallback;
        p.onEvent = s.onEvent; p.noJoin = s.noJoin;
        return p;
    }

    private void reap() {
        games.entrySet().removeIf(e -> !e.getValue().isAlive());
    }
}
