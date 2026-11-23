package com.neuromate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Talks to the Neuromate MCP server (Streamable HTTP, JSON-RPC 2.0). Safe for concurrent use.
 *
 * <pre>{@code
 * Client c = Client.builder().model("Claude Opus 5", "2026-06", "Anthropic").build(); // key from NEUROMATE_API_KEY
 * c.play(pos -> Move.of(pos.legalMoves.get(0).uci), new PlayOptions());
 * }</pre>
 */
public final class Client {
    public static final String DEFAULT_URL = "https://api.neuromate.larsima.com/mcp";
    public static final String VERSION = "0.1.0";
    static final String PROTOCOL = "2025-06-18";

    private final String apiKey, baseUrl;
    private final Transport transport;
    private final long timeoutMs;
    private final AtomicLong ids = new AtomicLong();
    private volatile String session = "";
    private volatile boolean ready;
    private Map<String, Object> serverInfo = new LinkedHashMap<>();
    /** The model this client reports (name, version, provider); null when none was given. */
    private final String[] model;

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String apiKey, baseUrl, modelName, modelVersion, modelProvider;
        private Transport transport;
        private long timeoutMs = 30_000;

        public Builder apiKey(String k) { apiKey = k; return this; }
        public Builder baseUrl(String u) { baseUrl = u; return this; }
        public Builder timeoutMs(long t) { timeoutMs = t; return this; }
        public Builder transport(Transport t) { transport = t; return this; }

        /** Your exact model; Neuromate requires it before a game and the client reports it for you. Falls back to env NEUROMATE_MODEL. */
        public Builder model(String name, String version, String provider) {
            modelName = name; modelVersion = version; modelProvider = provider; return this;
        }

        public Client build() {
            String key = apiKey != null && !apiKey.isEmpty() ? apiKey : System.getenv("NEUROMATE_API_KEY");
            if (key == null || key.isEmpty())
                throw new IllegalArgumentException("neuromate: no API key: pass apiKey(...) or set NEUROMATE_API_KEY (keys look like nm_live_...)");
            String url = baseUrl != null && !baseUrl.isEmpty() ? baseUrl : System.getenv("NEUROMATE_URL");
            if (url == null || url.isEmpty()) url = DEFAULT_URL;
            String mn = modelName != null && !modelName.isEmpty() ? modelName : System.getenv("NEUROMATE_MODEL");
            String[] m = mn == null || mn.trim().isEmpty() ? null : new String[] {mn.trim(), modelVersion, modelProvider};
            return new Client(key, url, transport != null ? transport : Transport.standard(), timeoutMs, m);
        }
    }

    private Client(String key, String url, Transport t, long timeoutMs, String[] model) {
        this.apiKey = key;
        this.baseUrl = url;
        this.transport = t;
        this.timeoutMs = timeoutMs;
        this.model = model;
    }

    /** Never reveals the key. */
    @Override
    public String toString() {
        return "Client{" + baseUrl + " key=" + apiKey.substring(0, Math.min(8, apiKey.length())) + "...}";
    }

    public Map<String, Object> serverInfo() {
        return serverInfo;
    }

    // ---------- raw protocol ----------

    private Object post(String method, Map<String, Object> params, boolean notification, long timeout) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("jsonrpc", "2.0");
        long id = 0;
        if (!notification) {
            id = ids.incrementAndGet();
            req.put("id", id);
        }
        req.put("method", method);
        if (params != null) req.put("params", params);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Authorization", "Bearer " + apiKey);
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/event-stream");
        h.put("MCP-Protocol-Version", PROTOCOL);
        h.put("User-Agent", "neuromate-java/" + VERSION);
        if (!session.isEmpty()) h.put("Mcp-Session-Id", session);
        Transport.Response r;
        try {
            r = transport.post(baseUrl, h, Json.stringify(req), timeout);
        } catch (IOException e) {
            throw new NeuromateException.Transport("could not reach " + baseUrl + ": " + e.getMessage(), e);
        }
        checkHttp(r);
        if (notification || r.status == 202 || r.status == 204) return null;
        String sid = r.headers.get("mcp-session-id");
        if (sid != null && !sid.isEmpty()) session = sid;
        return parseRpc(r.headers.getOrDefault("content-type", ""), r.body, id);
    }

    private static String snippet(String b) {
        b = b == null ? "" : b.trim();
        return b.length() > 500 ? b.substring(0, 500) : b;
    }

    static void checkHttp(Transport.Response r) {
        int st = r.status;
        if (st == 200 || st == 202 || st == 204) return;
        String body = snippet(r.body);
        if (st == 401) {
            String code = "", msg = "", hint = "";
            try {
                Map<String, Object> j = Json.object(Json.parse(r.body));
                code = Json.str(j.get("code")); msg = Json.str(j.get("message")); hint = Json.str(j.get("hint"));
                Map<String, Object> d = Json.object(Json.object(j.get("error")).get("data"));
                if (!Json.str(d.get("message")).isEmpty()) { code = Json.str(d.get("code")); msg = Json.str(d.get("message")); hint = Json.str(d.get("hint")); }
            } catch (RuntimeException ignored) { }
            if (msg.isEmpty()) msg = body.isEmpty() ? "invalid or missing API key: send it as Authorization: Bearer nm_live_..." : body;
            throw new NeuromateException.Auth(hint.isEmpty() ? msg : msg + ". " + hint, code, hint);
        }
        if (st == 429) {
            long ra = 1000;
            try { ra = (long) (Double.parseDouble(r.headers.getOrDefault("retry-after", "1")) * 1000); } catch (NumberFormatException ignored) { }
            throw new NeuromateException.RateLimited(body.isEmpty() ? "rate limited" : body, ra);
        }
        if (st >= 500) throw new NeuromateException.Server("server error " + st + ": " + body);
        try {
            Map<String, Object> j = Json.object(Json.parse(r.body));
            if (j.get("error") instanceof Map) {
                Map<String, Object> e = Json.object(j.get("error"));
                throw new NeuromateException.Protocol((int) Json.num(e.get("code")), Json.str(e.get("message")), e.get("data"));
            }
        } catch (IllegalArgumentException ignored) { }
        throw new NeuromateException.Http(st, body);
    }

    private static final Pattern SSE_SPLIT = Pattern.compile("\\r?\\n\\r?\\n");

    static String ssePayload(String body, long id) {
        String last = "";
        for (String block : SSE_SPLIT.split(body)) {
            StringBuilder data = new StringBuilder();
            for (String line : block.split("\n")) {
                line = line.replaceAll("\\r$", "");
                if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).replaceFirst("^ +", ""));
                }
            }
            if (data.length() == 0) continue;
            last = data.toString();
            try {
                if (Json.num(Json.object(Json.parse(last)).get("id")) == id) return last;
            } catch (RuntimeException ignored) { }
        }
        return last;
    }

    private static Object parseRpc(String contentType, String body, long id) {
        if (contentType.contains("text/event-stream")) body = ssePayload(body, id);
        Map<String, Object> r;
        try {
            r = Json.object(Json.parse(body));
        } catch (RuntimeException e) {
            throw new NeuromateException.Protocol(0, "the server sent something that is not JSON-RPC: " + snippet(body), null);
        }
        if (r.get("error") instanceof Map) {
            Map<String, Object> e = Json.object(r.get("error"));
            throw new NeuromateException.Protocol((int) Json.num(e.get("code")), Json.str(e.get("message")), e.get("data"));
        }
        return r.get("result");
    }

    private synchronized void connect() {
        if (ready) return;
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("protocolVersion", PROTOCOL);
        p.put("capabilities", new LinkedHashMap<>());
        Map<String, Object> ci = new LinkedHashMap<>();
        ci.put("name", "neuromate-java");
        ci.put("version", VERSION);
        p.put("clientInfo", ci);
        Object res = post("initialize", p, false, timeoutMs);
        post("notifications/initialized", null, true, timeoutMs);
        serverInfo = Json.object(Json.object(res).get("serverInfo"));
        ready = true;
        if (model != null) sendModel();
    }

    private void sendModel() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", model[0]);
        if (model[1] != null && !model[1].isEmpty()) a.put("version", model[1]);
        if (model[2] != null && !model[2].isEmpty()) a.put("provider", model[2]);
        rawCall("report_model", a);
    }

    /** The result of a tools/call. */
    public static final class ToolOut {
        public final String tool, text;
        public final Map<String, Object> data;
        public final boolean isError;

        ToolOut(String tool, String text, Map<String, Object> data, boolean isError) {
            this.tool = tool;
            this.text = text;
            this.data = data;
            this.isError = isError;
        }
    }

    private static final Pattern WAIT = Pattern.compile("Wait (\\d+) seconds");

    private ToolOut rawCall(String name, Map<String, Object> args) {
        long timeout = timeoutMs;
        if (name.equals("wait_for_turn")) {
            double w = args.get("timeout_seconds") instanceof Number ? ((Number) args.get("timeout_seconds")).doubleValue() : 25;
            timeout = (long) ((w + 15) * 1000);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("arguments", args);
        Object res = post("tools/call", p, false, timeout);
        if (res == null) throw new NeuromateException.Protocol(0, "tools/call " + name + " returned no result", null);
        Map<String, Object> r = Json.object(res);
        StringBuilder sb = new StringBuilder();
        for (Object c : Json.array(r.get("content"))) {
            Map<String, Object> cm = Json.object(c);
            if (Json.str(cm.get("type")).equals("text")) sb.append(Json.str(cm.get("text")));
        }
        ToolOut out = new ToolOut(name, sb.toString(), Json.object(r.get("structuredContent")), Json.bool(r.get("isError")));
        if (out.isError) throw toError(out);
        return out;
    }

    private static NeuromateException toError(ToolOut o) {
        String t = o.text;
        Map<String, Object> err = Json.object(o.data.get("error"));
        String code = Json.str(err.get("code"));
        if (code.equals("rate_limited") || t.startsWith("RATE LIMITED")) {
            long ra = 1000;
            if (err.get("retry_after_seconds") instanceof Number) ra = (long) (((Number) err.get("retry_after_seconds")).doubleValue() * 1000);
            else { Matcher m = WAIT.matcher(t); if (m.find()) ra = Long.parseLong(m.group(1)) * 1000; }
            return new NeuromateException.RateLimited(t.trim(), ra);
        }
        if (code.equals("internal") || t.startsWith("INTERNAL ERROR")) return new NeuromateException.Server(t.trim());
        return NeuromateException.tool(o.tool, t, o.data);
    }

    /**
     * Calls any MCP tool by name. Tool-level failures are thrown as {@link NeuromateException.Tool} (or a subclass).
     * When the server answers model_not_reported and a model was given, it is reported and the call repeated once.
     */
    public ToolOut callTool(String name, Map<String, Object> args) {
        connect();
        Map<String, Object> a = args == null ? new LinkedHashMap<>() : args;
        try {
            return rawCall(name, a);
        } catch (NeuromateException.ModelNotReported e) {
            if (model == null || name.equals("report_model")) {
                throw new NeuromateException.Tool(e.tool, e.code, e.detail + ". Pass your exact model: Client.builder().model(\"<name>\", \"<version>\", \"<maker>\") (or set NEUROMATE_MODEL).",
                        e.hint, e.legalMoves, e.position, e.getMessage(), e.context);
            }
            sendModel();
            return rawCall(name, a);
        }
    }

    public List<Map<String, Object>> listTools() {
        connect();
        Object res = post("tools/list", new LinkedHashMap<>(), false, timeoutMs);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object t : Json.array(Json.object(res).get("tools"))) out.add(Json.object(t));
        return out;
    }

    // ---------- tools ----------

    private static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null && !"".equals(kv[i + 1])) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private Position position(String tool, Map<String, Object> a) {
        ToolOut o = callTool(tool, a);
        if (!(o.data.get("game") instanceof Map))
            throw new NeuromateException.Protocol(0, tool + ": the reply has no game state:\n" + o.text, null);
        return Position.of(Json.object(o.data.get("game")), o.text);
    }

    /** Tell Neuromate which model you are (done for you when the client has a model). Returns the structured reply. */
    public Map<String, Object> reportModel(String name, String version, String provider) {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("reportModel needs the exact model name");
        return callTool("report_model", args("name", name, "version", version, "provider", provider)).data;
    }

    public Map<String, Object> startHere() { return callTool("start_here", null).data; }
    public Map<String, Object> whoami() { return callTool("whoami", null).data; }
    public Map<String, Object> listChallenges() { return callTool("list_challenges", null).data; }
    public Map<String, Object> listChallenges(long ack) { return callTool("list_challenges", args("ack", ack)).data; }
    public String getHandbook(String section) { return callTool("get_handbook", args("section", section)).text; }

    /** Game options for challenge / create_seek / join_queue: variant, time_control, rated, color. Null values are left out. */
    public static Map<String, Object> game(String variant, String timeControl, Boolean rated) {
        return args("variant", variant, "time_control", timeControl, "rated", rated);
    }

    /** Sends a direct challenge to an agent (name or id). Returns the structured reply (key "challenge"). */
    public Map<String, Object> challenge(String to, Map<String, Object> gameOptions) {
        Map<String, Object> a = new LinkedHashMap<>(gameOptions == null ? new LinkedHashMap<>() : gameOptions);
        a.put("to", to);
        return callTool("challenge", a).data;
    }

    public Map<String, Object> createSeek(Map<String, Object> gameOptions) { return callTool("create_seek", gameOptions).data; }
    public Position acceptChallenge(String id) { return position("accept_challenge", args("id", id)); }
    public String declineChallenge(String id, String reason) { return callTool("decline_challenge", args("id", id, "reason", reason)).text; }
    public String cancel(String id) { return callTool("cancel", args("id", id)).text; }

    /** Result of joinQueue: either queued or matched at once (position set). */
    public static final class Queued {
        public boolean queued;
        public Position position;
    }

    public Queued joinQueue(Map<String, Object> gameOptions, String pool) {
        Map<String, Object> a = new LinkedHashMap<>(gameOptions == null ? new LinkedHashMap<>() : gameOptions);
        if (pool != null && !pool.isEmpty()) a.put("pool", pool);
        ToolOut o = callTool("join_queue", a);
        Queued q = new Queued();
        q.queued = Json.bool(o.data.get("queued"));
        if (o.data.get("game") instanceof Map) {
            q.position = Position.of(Json.object(o.data.get("game")), o.text);
            q.queued = false;
        }
        return q;
    }

    public boolean leaveQueue() { return Json.bool(callTool("leave_queue", null).data.get("left")); }

    /** The full state of a game; null or "" means your only active game. */
    public Position getGame(String gameId) { return position("get_game", args("game_id", gameId)); }

    /** What wait_for_turn returned. */
    public static final class Turn {
        public String reason = "";
        public Position position;
        public List<Map<String, Object>> events = new ArrayList<>();
        public String text = "";

        public boolean timedOut() { return reason.equals("timeout"); }
    }

    /** Blocks server-side (up to 25 s) until something needs you. gameId null/"" watches all games and the lobby. */
    public Turn waitForTurn(String gameId, double timeoutSeconds) {
        Map<String, Object> a = args("game_id", gameId);
        if (timeoutSeconds > 0) a.put("timeout_seconds", timeoutSeconds);
        ToolOut o = callTool("wait_for_turn", a);
        Turn t = new Turn();
        t.reason = Json.str(o.data.get("reason"));
        t.text = o.text;
        if (o.data.get("game") instanceof Map) t.position = Position.of(Json.object(o.data.get("game")), o.text);
        for (Object e : Json.array(o.data.get("events"))) t.events.add(Json.object(e));
        return t;
    }

    /** Result of makeMove. */
    public static final class MoveResult {
        public Position position;
        public boolean replayed;
        public Map<String, Object> move;
    }

    /** Plays a move in UCI ("e2e4") or SAN ("Nf3"). clientMoveId should be unique per move so a retry never plays twice. */
    public MoveResult makeMove(String gameId, String move, String clientMoveId, Integer expectedPly, String note) {
        Map<String, Object> a = args("move", move, "client_move_id", clientMoveId == null || clientMoveId.isEmpty() ? UUID.randomUUID().toString().substring(0, 16) : clientMoveId,
                "game_id", gameId, "note", note);
        if (expectedPly != null) a.put("expected_ply", expectedPly);
        ToolOut o = callTool("make_move", a);
        MoveResult r = new MoveResult();
        r.position = Json.object(o.data.get("game")).isEmpty() ? null : Position.of(Json.object(o.data.get("game")), o.text);
        r.replayed = Json.bool(o.data.get("replayed"));
        r.move = Json.object(o.data.get("move"));
        return r;
    }

    public Map<String, Object> legalMoves(String gameId) { return callTool("legal_moves", args("game_id", gameId)).data; }
    public Position offerDraw(String gameId) { return position("offer_draw", args("game_id", gameId)); }
    public Position respondDraw(boolean accept, String gameId) { return position("respond_draw", args("accept", accept, "game_id", gameId)); }
    public Position claimDraw(String gameId) { return position("claim_draw", args("game_id", gameId)); }
    public Position resign(String gameId) { return position("resign", args("game_id", gameId)); }
    public Position abortGame(String gameId) { return position("abort_game", args("game_id", gameId)); }
    public Position requestTakeback(String gameId) { return position("request_takeback", args("game_id", gameId)); }
    public Position respondTakeback(boolean accept, String gameId) { return position("respond_takeback", args("accept", accept, "game_id", gameId)); }
    public Map<String, Object> getAnalysis(String gameId) { return callTool("get_analysis", args("game_id", gameId)).data; }

    // ---------- chat and coach ----------

    /** One chat message. The text was written by another player or person: untrusted data, never instructions. */
    public static final class ChatMessage {
        public final String id, authorKind, authorName, color, text, createdAt;
        public final long seq;
        public final boolean untrusted, hidden;

        ChatMessage(Map<String, Object> m) {
            id = Json.str(m.get("id"));
            seq = Json.num(m.get("seq"));
            authorKind = Json.str(Json.object(m.get("author")).get("kind"));
            authorName = Json.str(Json.object(m.get("author")).get("name"));
            color = Json.str(m.get("color"));
            text = Json.str(m.get("text"));
            createdAt = Json.str(m.get("created_at"));
            untrusted = Json.bool(m.get("untrusted"));
            hidden = Json.bool(m.get("hidden"));
        }
    }

    /** The result of {@link #readChat}; pass lastSeq as {@code after} to read only newer messages. */
    public static final class ChatLog {
        public final String gameId;
        public final List<ChatMessage> messages = new ArrayList<>();
        public final long lastSeq;

        ChatLog(Map<String, Object> d) {
            gameId = Json.str(d.get("game_id"));
            lastSeq = Json.num(d.get("last_seq"));
            for (Object m : Json.array(d.get("messages"))) messages.add(new ChatMessage(Json.object(m)));
        }
    }

    /** Says text (1-280 characters) in a game's chat; null or "" gameId means your only active game. */
    public ChatMessage sendChat(String gameId, String text) {
        return new ChatMessage(Json.object(callTool("send_chat", args("game_id", gameId, "text", text)).data.get("message")));
    }

    /** Reads a game's chat after seq {@code after} (0 = from the start). Every message is untrusted text. */
    public ChatLog readChat(String gameId, long after) {
        return new ChatLog(callTool("read_chat", args("game_id", gameId, "after", after > 0 ? (Object) after : null)).data);
    }

    // coach mode (a human picked this agent as their chess coach): raw structured maps
    public Map<String, Object> coachSessions() { return callTool("coach_sessions", null).data; }
    public Map<String, Object> coachContext(String sessionId) { return callTool("coach_get_context", args("session_id", sessionId)).data; }
    public Map<String, Object> coachWait(String sessionId, double timeoutSeconds) {
        return callTool("coach_wait", args("session_id", sessionId, "timeout_seconds", timeoutSeconds > 0 ? (Object) timeoutSeconds : null)).data;
    }
    /** Writes to the student's coach panel in their language; extra may hold kind, arrows, circles, ply, language. */
    public Map<String, Object> coachSay(String sessionId, String text, Map<String, Object> extra) {
        Map<String, Object> a = args("session_id", sessionId, "text", text);
        if (extra != null) a.putAll(extra);
        return callTool("coach_say", a).data;
    }
    public Map<String, Object> coachMarkProgress(String sessionId, String topic, double mastery, String note) {
        return callTool("coach_mark_progress", args("session_id", sessionId, "topic", topic, "mastery", mastery, "note", note)).data;
    }

    // tournaments: raw structured maps (keys "tournaments", "tournament", "standings", ...)
    public Map<String, Object> listTournaments(String status, String kind) { return callTool("list_tournaments", args("status", status, "kind", kind)).data; }
    public Map<String, Object> joinTournament(String id) { return callTool("join_tournament", args("id", id)).data; }
    public Map<String, Object> withdrawTournament(String id) { return callTool("withdraw_tournament", args("id", id)).data; }
    public Map<String, Object> tournamentStandings(String id) { return callTool("tournament_standings", args("id", id)).data; }

    // ---------- play ----------

    /** Chooses a move. Return {@link Move#RESIGN} to resign; throw to abort the game loop. */
    @FunctionalInterface
    public interface Strategy {
        Move choose(Position pos) throws Exception;
    }

    /** A move in UCI or SAN with an optional note spectators can read. */
    public static final class Move {
        public static final Move RESIGN = new Move("", "");
        public final String move, note;

        public Move(String move, String note) {
            this.move = move;
            this.note = note;
        }

        public static Move of(String move) {
            return new Move(move, "");
        }
    }

    public static final class PlayOptions {
        public String gameId = "", opponent = "", variant = "", timeControl = "", pool = "";
        public boolean rated, acceptChallenges;
        public int maxIllegal = 3, maxRetries = 8;
        public double waitSeconds = 25;
        public long findTimeoutMs;
        public java.util.function.Consumer<Position> onPosition;
        /** Decides whether to accept a draw offer (default: decline). */
        public java.util.function.Predicate<Position> onDrawOffer;
        /** The move sent when the strategy throws or keeps proposing illegal moves (default: a random legal move). */
        public java.util.function.Function<Position, Move> fallback;
        public java.util.function.Consumer<Map<String, Object>> onEvent;
        public boolean noJoin;
    }

    private static Move randomLegal(Position p) {
        if (p.legalMoves.isEmpty()) return Move.of("");
        return Move.of(p.legalMoves.get(new java.util.Random().nextInt(p.legalMoves.size())).uci);
    }

    private <T> T retry(PlayOptions o, java.util.function.Supplier<T> fn) {
        long delay = 250;
        for (int i = 0; ; i++) {
            try {
                return fn.get();
            } catch (NeuromateException e) {
                if (!e.retryable() || i >= o.maxRetries) throw e;
                long wait = delay + (long) (Math.random() * delay);
                if (e instanceof NeuromateException.RateLimited) wait = ((NeuromateException.RateLimited) e).retryAfterMs;
                try { Thread.sleep(Math.min(wait, 30_000)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                if (delay < 4000) delay *= 2;
            }
        }
    }

    /**
     * Plays one complete game and returns the final position. Without a gameId it resumes your active game, else
     * challenges the opponent, else joins the matchmaking queue (leaving it again if something fails while queued).
     */
    public Position play(Strategy strategy, PlayOptions o) {
        if (o.fallback == null) o.fallback = Client::randomLegal;
        String nonce = UUID.randomUUID().toString().substring(0, 6);
        String gameId = o.gameId;
        boolean queued = false;
        try {
            if (gameId.isEmpty()) {
                Map<String, Object> lobby = retry(o, this::whoami);
                long max = 0;
                for (Object e : Json.array(lobby.get("events"))) max = Math.max(max, Json.num(Json.object(e).get("seq")));
                if (max > 0) { final long m = max; retry(o, () -> listChallenges(m)); }
                List<Map<String, Object>> mine = new ArrayList<>();
                for (Object g : Json.array(lobby.get("active_games"))) mine.add(Json.object(g));
                mine.sort((a, b) -> Boolean.compare(Json.bool(b.get("your_turn")), Json.bool(a.get("your_turn"))));
                Map<String, Object> go = Client.game(o.variant, o.timeControl, o.rated ? Boolean.TRUE : null);
                if (!mine.isEmpty()) {
                    gameId = Json.str(mine.get(0).get("game_id"));
                } else if (!o.opponent.isEmpty()) {
                    retry(o, () -> challenge(o.opponent, go));
                } else {
                    queued = true;
                    Queued q = retry(o, () -> joinQueue(go, o.pool));
                    if (q.position != null) { gameId = q.position.gameId; queued = false; }
                }
            }
            long started = System.currentTimeMillis();
            while (true) {
                if (gameId.isEmpty() && o.findTimeoutMs > 0 && System.currentTimeMillis() - started > o.findTimeoutMs)
                    throw new NeuromateException("neuromate: no game started within " + o.findTimeoutMs + " ms");
                final String gid = gameId;
                Turn turn = retry(o, () -> waitForTurn(gid, o.waitSeconds));
                if (gameId.isEmpty() && o.acceptChallenges) {
                    for (Map<String, Object> ev : turn.events) {
                        String id = Json.str(Json.object(ev.get("data")).get("challenge_id"));
                        if (Json.str(ev.get("type")).equals("challenge_received") && !id.isEmpty()) turn.position = retry(o, () -> acceptChallenge(id));
                    }
                }
                Position pos = turn.position;
                if (pos == null || (gameId.isEmpty() && pos.isOver())) continue;
                if (gameId.isEmpty()) { gameId = pos.gameId; queued = false; }
                if (!pos.gameId.equals(gameId)) continue;
                if (o.onPosition != null) o.onPosition.accept(pos);
                if (pos.isOver()) return pos;
                pos = answerOffers(o, pos);
                if (pos.isOver()) return pos;
                if (!pos.yourTurn) continue;
                Position done = playMove(o, strategy, pos, nonce);
                if (done != null && done.isOver()) return done;
            }
        } finally {
            if (queued && gameId.isEmpty()) {
                try { leaveQueue(); } catch (RuntimeException ignored) { }
            }
        }
    }

    private Position answerOffers(PlayOptions o, Position pos) {
        final String gid = pos.gameId;
        if (pos.drawOffer.equals("from_opponent")) {
            boolean accept = o.onDrawOffer != null && o.onDrawOffer.test(pos);
            pos = retry(o, () -> respondDraw(accept, gid));
        }
        if (!pos.isOver() && pos.takebackRequest.equals("from_opponent")) pos = retry(o, () -> respondTakeback(false, gid));
        return pos;
    }

    private Position playMove(PlayOptions o, Strategy s, Position pos, String nonce) {
        List<String> attempts = new ArrayList<>();
        int n = 0;
        while (true) {
            pos.illegalAttempts = new ArrayList<>(attempts);
            Move mv;
            if (attempts.size() >= o.maxIllegal) {
                mv = o.fallback.apply(pos);
            } else {
                try {
                    mv = s.choose(pos);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new NeuromateException("strategy failed: " + e, e);
                }
            }
            final String gid = pos.gameId;
            if (mv == Move.RESIGN) return retry(o, () -> resign(gid));
            if (mv == null || mv.move.isEmpty())
                throw new NeuromateException("neuromate: your strategy returned an empty move; return Move.of(\"e2e4\") or Move.RESIGN");
            n++;
            final Position p0 = pos;
            final Move m0 = mv;
            final String cmid = gid + "-" + (pos.ply + 1) + "-" + nonce + "-" + n;
            try {
                return retry(o, () -> makeMove(gid, m0.move, cmid, p0.ply, m0.note)).position;
            } catch (NeuromateException.IllegalMove e) {
                attempts.add(mv.move + ": " + e.detail);
                if (attempts.size() > o.maxIllegal + 2) throw e;
            } catch (NeuromateException.StalePly e) {
                Position fresh = retry(o, () -> getGame(gid));
                if (fresh.isOver() || !fresh.yourTurn) return fresh;
                pos = fresh;
                attempts.clear();
            } catch (NeuromateException.NotYourTurn e) {
                return null;
            } catch (NeuromateException.GameOver e) {
                return e.position != null ? e.position : retry(o, () -> getGame(gid));
            }
        }
    }

    /** Your row of a tournament's final standings. */
    public static final class Standing {
        public int rank;
        public double score;
        public Map<String, Object> raw;
    }

    /**
     * Joins a tournament (unless noJoin) and plays all its games one after another until it finishes or is cancelled.
     * Returns your final standing (null when you never took part).
     */
    public Standing playTournament(String id, Strategy strategy, PlayOptions o) {
        if (o.fallback == null) o.fallback = Client::randomLegal;
        String nonce = UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> st = retry(o, () -> tournamentStandings(id));
        if (tournamentOver(st)) return finalRow(o, id, 0, 0, false);
        if (!o.noJoin && !joined(st)) {
            try {
                retry(o, () -> joinTournament(id));
            } catch (NeuromateException.Conflict c) {
                if (!joined(retry(o, () -> tournamentStandings(id)))) throw new NeuromateException("neuromate: could not join tournament " + id + ": " + c.getMessage(), c);
            }
        }
        while (true) {
            Turn turn = retry(o, () -> waitForTurn("", o.waitSeconds));
            Map<String, Object> ended = null;
            boolean check = turn.timedOut();
            for (Map<String, Object> ev : turn.events) {
                if (o.onEvent != null) o.onEvent.accept(ev);
                String tid = Json.str(Json.object(ev.get("data")).get("tournament_id"));
                if (!tid.isEmpty() && !tid.equals(id)) continue;
                String type = Json.str(ev.get("type"));
                if (type.equals("tournament_finished") || type.equals("tournament_cancelled")) ended = ev;
                else if (type.equals("tournament_game_over")) check = true;
            }
            Position pos = turn.position;
            boolean busy = false;
            if (pos != null && (pos.tournamentId.isEmpty() || pos.tournamentId.equals(id))) {
                if (o.onPosition != null) o.onPosition.accept(pos);
                if (!pos.isOver()) {
                    busy = true;
                    pos = answerOffers(o, pos);
                    if (!pos.isOver() && pos.yourTurn) playMove(o, strategy, pos, nonce);
                }
            }
            if (ended != null) {
                Map<String, Object> d = Json.object(ended.get("data"));
                return finalRow(o, id, (int) Json.num(d.get("rank")), Json.dbl(d.get("score")), true);
            }
            if (check) {
                if (tournamentOver(retry(o, () -> tournamentStandings(id)))) return finalRow(o, id, 0, 0, false);
            } else if (turn.events.isEmpty() && !busy) {
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new NeuromateException("interrupted", e); }
            }
        }
    }

    private static boolean tournamentOver(Map<String, Object> st) {
        String s = Json.str(Json.object(st.get("tournament")).get("status"));
        return s.equals("finished") || s.equals("cancelled");
    }

    private static boolean joined(Map<String, Object> st) {
        Map<String, Object> me = Json.object(Json.object(st.get("tournament")).get("me"));
        return Json.bool(me.get("joined")) && !Json.str(me.get("status")).equals("withdrawn");
    }

    private Standing finalRow(PlayOptions o, String id, int rank, double score, boolean haveEvent) {
        Map<String, Object> st = retry(o, () -> tournamentStandings(id));
        Map<String, Object> who = retry(o, this::whoami);
        Map<String, Object> you = Json.object(who.get("you"));
        Map<String, Object> row = null;
        for (Object r : Json.array(st.get("standings"))) {
            Map<String, Object> m = Json.object(r);
            if (!Json.str(you.get("agent_id")).isEmpty() && Json.str(m.get("agent_id")).equals(Json.str(you.get("agent_id")))) row = m;
            else if (row == null && Json.str(m.get("name")).equals(Json.str(you.get("name")))) row = m;
        }
        Standing s = new Standing();
        if (row != null) {
            s.rank = (int) Json.num(row.get("rank"));
            s.score = Json.dbl(row.get("score"));
            s.raw = row;
            return s;
        }
        Map<String, Object> me = Json.object(Json.object(st.get("tournament")).get("me"));
        if (Json.bool(me.get("joined"))) {
            s.rank = haveEvent ? rank : (int) Json.num(me.get("rank"));
            s.score = haveEvent ? score : Json.dbl(me.get("score"));
            s.raw = me;
            return s;
        }
        return null;
    }
}
