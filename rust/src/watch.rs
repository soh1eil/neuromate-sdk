//! Staying on duty: [`Client::watch`]. A watcher sends a heartbeat every 30 s (so the agent shows as on duty and challenges are
//! held for it instead of expiring), reconnects with backoff, resumes games that were running when it started, accepts challenges
//! by the auto-accept rules the owner set on the agent page and calls your handler for every move, one thread per game. A handler
//! that panics never forfeits a game: a random legal move is played and the panic is logged.

use crate::client::Client;
use crate::error::Error;
use crate::play::{rand_u64, Move, PlayOptions};
use crate::position::Position;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

/// What to do with a challenge.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Decision {
    /// `accept`, `decline` or `ignore` (leave it for the owner).
    pub action: String,
    pub reason: String,
}

/// The owner's auto-accept rules (mirrors server/internal/duty/rules.go; both are tested against sdk/testdata/auto_accept_cases.json).
/// Empty lists mean "any".
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rules {
    pub enabled: bool,
    pub speeds: Vec<String>,
    pub variants: Vec<String>,
    /// `any`, `casual` or `rated`.
    pub rated: String,
    /// `any`, `casual` or `strict`.
    pub ruleset: String,
    pub opponent_kinds: Vec<String>,
    pub max_concurrent: usize,
    pub decline_others: bool,
}

impl Default for Rules {
    fn default() -> Self {
        Rules { enabled: false, speeds: vec![], variants: vec![], rated: "any".into(), ruleset: "any".into(), opponent_kinds: vec![], max_concurrent: 1, decline_others: false }
    }
}

fn strs(v: &Value, k: &str) -> Vec<String> {
    v.get(k).and_then(Value::as_array).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default()
}

fn s<'a>(v: &'a Value, k: &str) -> &'a str {
    v.get(k).and_then(Value::as_str).unwrap_or("")
}

impl Rules {
    /// Rules that accept every challenge while idle (the other fields keep their defaults).
    pub fn enabled() -> Rules { Rules { enabled: true, ..Default::default() } }

    pub fn from_value(d: &Value) -> Rules {
        Rules {
            enabled: d.get("enabled").and_then(Value::as_bool).unwrap_or(false),
            speeds: strs(d, "speeds"),
            variants: strs(d, "variants"),
            rated: if s(d, "rated").is_empty() { "any".into() } else { s(d, "rated").into() },
            ruleset: if s(d, "ruleset").is_empty() { "any".into() } else { s(d, "ruleset").into() },
            opponent_kinds: strs(d, "opponent_kinds"),
            max_concurrent: d.get("max_concurrent").and_then(Value::as_u64).unwrap_or(1).max(1) as usize,
            decline_others: d.get("decline_others").and_then(Value::as_bool).unwrap_or(false),
        }
    }

    /// `c`: speed, variant, rated, ruleset (casual | fide_strict), from_kind (human | agent | bot). `playing`: games running now.
    pub fn evaluate(&self, c: &Value, playing: usize) -> Decision {
        let d = |a: &str, r: String| Decision { action: a.into(), reason: r };
        if !self.enabled {
            return d("ignore", "auto-accept is off".into());
        }
        let refuse = |why: String| d(if self.decline_others { "decline" } else { "ignore" }, why);
        let (speed, variant, kind) = (s(c, "speed"), s(c, "variant"), s(c, "from_kind"));
        if !self.speeds.is_empty() && !self.speeds.iter().any(|x| x == speed) {
            return refuse(format!("time control {speed} is not accepted"));
        }
        if !self.variants.is_empty() && !self.variants.iter().any(|x| x == variant) {
            return refuse(format!("variant {variant} is not accepted"));
        }
        let rated = c.get("rated").and_then(Value::as_bool).unwrap_or(false);
        if self.rated == "casual" && rated {
            return refuse("only casual games are accepted".into());
        }
        if self.rated == "rated" && !rated {
            return refuse("only rated games are accepted".into());
        }
        let strict = matches!(s(c, "ruleset"), "fide_strict" | "strict");
        if self.ruleset == "casual" && strict {
            return refuse("strict (FIDE) games are not accepted".into());
        }
        if self.ruleset == "strict" && !strict {
            return refuse("only strict (FIDE) games are accepted".into());
        }
        if !self.opponent_kinds.is_empty() && !self.opponent_kinds.iter().any(|x| x == kind) {
            return refuse(format!("opponents of kind {kind} are not accepted"));
        }
        if playing >= self.max_concurrent.max(1) {
            return d("ignore", format!("already playing {playing} game(s)"));
        }
        d("accept", String::new())
    }
}

/// bullet / blitz / rapid / classical / correspondence / untimed, from a control object (or `Value::Null`) or a label like `3+2`.
pub fn speed_of(control: &Value, label: &str) -> String {
    let kind = s(control, "kind");
    let has_days = {
        let b = label.as_bytes();
        let mut found = false;
        for i in 0..b.len() {
            if b[i].is_ascii_digit() {
                let mut j = i + 1;
                while j < b.len() && b[j].is_ascii_whitespace() { j += 1; }
                if j < b.len() && b[j] == b'd' { found = true; break; }
            }
        }
        found
    };
    if kind == "correspondence" || has_days || label.contains("day") {
        return "correspondence".into();
    }
    if kind == "untimed" || label.trim().eq_ignore_ascii_case("untimed") {
        return "untimed".into();
    }
    let est: i64 = if control.as_object().is_some_and(|o| !o.is_empty()) {
        let n = |k: &str| control.get(k).and_then(Value::as_i64).unwrap_or(0);
        (n("initial_ms") + 40 * n("increment_ms").max(n("delay_ms")).max(n("bronstein_ms"))) / 1000
    } else {
        let t = label.trim_start();
        let main: String = t.chars().take_while(|c| c.is_ascii_digit() || *c == '.').collect();
        let Ok(minutes) = main.parse::<f64>() else { return "classical".into() };
        let rest = t[main.len()..].trim_start();
        let inc = rest.strip_prefix(['+', '|']).map(|r| r.trim_start().chars().take_while(char::is_ascii_digit).collect::<String>()).and_then(|x| x.parse::<i64>().ok()).unwrap_or(0);
        (minutes * 60.0) as i64 + 40 * inc
    };
    (if est < 180 { "bullet" } else if est < 480 { "blitz" } else if est < 1500 { "rapid" } else { "classical" }).into()
}

fn info(speed: String, variant: &str, rated: bool, ruleset: &str, kind: &str) -> Value {
    json!({"speed": speed, "variant": if variant.is_empty() { "standard" } else { variant }, "rated": rated,
           "ruleset": if ruleset.is_empty() { "casual" } else { ruleset }, "from_kind": if kind.is_empty() { "agent" } else { kind }})
}

/// The fields [`Rules::evaluate`] reads, from a `challenge_received` event.
pub fn info_from_event(ev: &Value) -> Value {
    let d = &ev["data"];
    let speed = if s(d, "speed").is_empty() { speed_of(&Value::Null, s(d, "time_control")) } else { s(d, "speed").into() };
    info(speed, s(d, "variant"), d.get("rated").and_then(Value::as_bool).unwrap_or(false), s(d, "ruleset"), s(d, "from_kind"))
}

/// The same, from an entry of whoami's `incoming_challenges`.
pub fn info_from_challenge(c: &Value) -> Value {
    let p = &c["params"];
    info(speed_of(p.get("control").unwrap_or(&Value::Null), ""), s(p, "variant"), p.get("rated").and_then(Value::as_bool).unwrap_or(false), s(p, "ruleset"), s(&c["from"], "kind"))
}

/// Reply of [`Client::standby`].
#[derive(Debug, Clone)]
pub struct Duty {
    pub on_duty: bool,
    /// `playing`, `on_duty` or `offline`.
    pub state: String,
    /// `watcher`, `webhook` or `active`.
    pub mode: String,
    pub rules: Rules,
    pub heartbeat_every_seconds: u64,
    pub ttl_seconds: u64,
    pub incoming_challenges: u64,
}

impl Client {
    /// Heartbeat of a watcher: marks you on duty (`off` ends the duty) and returns your owner's auto-accept rules.
    pub fn standby(&self, client: &str, off: bool) -> Result<Duty, Error> {
        let mut a = serde_json::Map::new();
        if !client.is_empty() { a.insert("client".into(), json!(client)); }
        if off { a.insert("action".into(), json!("off")); }
        let d = self.call_tool("standby", Value::Object(a))?.data;
        let p = &d["presence"];
        Ok(Duty {
            on_duty: d.get("on_duty").and_then(Value::as_bool).unwrap_or(false),
            state: s(p, "state").into(),
            mode: s(p, "mode").into(),
            rules: Rules::from_value(&d["rules"]),
            heartbeat_every_seconds: d.get("heartbeat_every_seconds").and_then(Value::as_u64).filter(|n| *n > 0).unwrap_or(30),
            ttl_seconds: d.get("ttl_seconds").and_then(Value::as_u64).filter(|n| *n > 0).unwrap_or(90),
            incoming_challenges: d.get("incoming_challenges").and_then(Value::as_u64).unwrap_or(0),
        })
    }

    /// Stays on duty until `stop` is set (see [`Watcher`]); blocks. Returns an error only when the API key is rejected.
    pub fn watch<H>(self: &Arc<Self>, handler: H, options: WatchOptions) -> Result<Arc<Watcher>, Error>
    where H: Fn(&Position) -> Move + Send + Sync + 'static {
        let w = Arc::new(Watcher::new(self.clone(), handler, options));
        w.run()?;
        Ok(w)
    }
}

/// Options of a [`Watcher`]; every field has a default.
pub struct WatchOptions {
    /// Overrides the owner's rules from the agent page when set.
    pub rules: Option<Rules>,
    pub server_rules: bool,
    pub heartbeat_every: Duration,
    pub busy_poll: Duration,
    pub max_backoff: Duration,
    pub wait_seconds: f64,
    pub log: Option<Arc<dyn Fn(&str) + Send + Sync>>,
    pub client_name: Option<String>,
    /// `max_illegal`, `max_retries` and `wait_seconds` are copied into every game's [`PlayOptions`].
    pub max_illegal: usize,
    pub max_retries: usize,
}

impl Default for WatchOptions {
    fn default() -> Self {
        WatchOptions { rules: None, server_rules: true, heartbeat_every: Duration::from_secs(30), busy_poll: Duration::from_secs(3), max_backoff: Duration::from_secs(60),
                       wait_seconds: 25.0, log: None, client_name: None, max_illegal: 3, max_retries: 8 }
    }
}

/// The durable loop behind [`Client::watch`].
pub struct Watcher {
    client: Arc<Client>,
    handler: Arc<dyn Fn(&Position) -> Move + Send + Sync>,
    o: WatchOptions,
    log: Arc<dyn Fn(&str) + Send + Sync>,
    name: String,
    stopped: AtomicBool,
    gate: (Mutex<()>, Condvar),
    rules: Mutex<Rules>,
    standby_ok: AtomicBool,
    games: Mutex<HashMap<String, JoinHandle<()>>>,
    cooldown: Mutex<HashMap<String, Instant>>,
    seen: Mutex<HashSet<String>>,
    accepted: AtomicUsize,
    played: AtomicUsize,
}

impl Watcher {
    pub fn new<H>(client: Arc<Client>, handler: H, options: WatchOptions) -> Watcher
    where H: Fn(&Position) -> Move + Send + Sync + 'static {
        let log: Arc<dyn Fn(&str) + Send + Sync> = options.log.clone().unwrap_or_else(|| Arc::new(|m: &str| eprintln!("{m}")));
        let l2 = log.clone();
        let handler: Arc<dyn Fn(&Position) -> Move + Send + Sync> = Arc::new(move |pos: &Position| {
            match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| handler(pos))) {
                Ok(m) => m,
                Err(_) => {
                    l2(&format!("handler panicked in {}; playing a fallback move", pos.game_id));
                    if pos.legal_moves.is_empty() { Move::default() } else { Move::uci(&pos.legal_moves[(rand_u64() % pos.legal_moves.len() as u64) as usize].uci) }
                }
            }
        });
        let name = options.client_name.clone().unwrap_or_else(|| format!("neuromate-rs/{} watch", crate::client::VERSION));
        let rules = options.rules.clone().unwrap_or_default();
        Watcher { client, handler, o: options, log, name, stopped: AtomicBool::new(false), gate: (Mutex::new(()), Condvar::new()), rules: Mutex::new(rules),
                  standby_ok: AtomicBool::new(true), games: Mutex::new(HashMap::new()), cooldown: Mutex::new(HashMap::new()), seen: Mutex::new(HashSet::new()),
                  accepted: AtomicUsize::new(0), played: AtomicUsize::new(0) }
    }

    pub fn accepted(&self) -> usize { self.accepted.load(Ordering::SeqCst) }
    pub fn played(&self) -> usize { self.played.load(Ordering::SeqCst) }
    pub fn rules(&self) -> Rules { self.rules.lock().unwrap().clone() }
    pub fn stopped(&self) -> bool { self.stopped.load(Ordering::SeqCst) }

    /// Ends the loop (the duty is ended cleanly) and wakes any pause. Safe from any thread.
    pub fn stop(&self) {
        self.stopped.store(true, Ordering::SeqCst);
        let _g = self.gate.0.lock().unwrap();
        self.gate.1.notify_all();
    }

    fn log(&self, m: &str) { (self.log)(m) }

    fn nap(&self, d: Duration) {
        let end = Instant::now() + d;
        let mut g = self.gate.0.lock().unwrap();
        while !self.stopped() {
            let now = Instant::now();
            if now >= end { return; }
            g = self.gate.1.wait_timeout(g, end - now).unwrap().0;
        }
    }

    fn beat(&self) -> Result<(), Error> {
        if !self.standby_ok.load(Ordering::SeqCst) { return Ok(()); }
        match self.client.standby(&self.name, false) {
            Ok(d) => {
                if self.o.server_rules && self.o.rules.is_none() {
                    let mut r = self.rules.lock().unwrap();
                    if *r != d.rules { self.log(&format!("auto-accept rules updated: {}", if d.rules.enabled { "on" } else { "off" })); }
                    *r = d.rules;
                }
                Ok(())
            }
            Err(Error::Protocol { message, .. }) if message.contains("unknown tool") => {
                self.standby_ok.store(false, Ordering::SeqCst);
                self.log("this server has no standby tool: running without heartbeats (tool calls still keep you present for two minutes)");
                Ok(())
            }
            Err(e) => Err(e),
        }
    }

    fn heartbeat_loop(&self) {
        let mut fails = 0;
        while !self.stopped() {
            self.nap(self.o.heartbeat_every);
            if self.stopped() { return; }
            match self.beat() {
                Ok(()) => fails = 0,
                Err(Error::Auth { .. }) => { self.log("heartbeat: the API key was rejected"); return; }
                Err(e) => { fails += 1; if fails == 1 || fails == 5 { self.log(&format!("heartbeat failed ({}); retrying", cut(&e.to_string(), 100))); } }
            }
        }
    }

    /// Runs until [`Watcher::stop`]; returns `Err(Error::Auth)` when the API key is rejected.
    pub fn run(self: &Arc<Self>) -> Result<(), Error> {
        self.log(&format!("on duty as {}", self.name));
        let mut backoff = Duration::from_secs(1);
        match self.beat() {
            Err(e @ Error::Auth { .. }) => return Err(e),
            Err(e) => self.log(&format!("first heartbeat failed ({}); will keep trying", cut(&e.to_string(), 100))),
            Ok(()) => {}
        }
        let hb = { let me = self.clone(); std::thread::spawn(move || me.heartbeat_loop()) };
        let mut first = true;
        let mut last = Instant::now();
        let mut result = Ok(());
        while !self.stopped() {
            let step = (|| -> Result<(), Error> {
                if first || last.elapsed() > Duration::from_secs(30) || !self.games.lock().unwrap().is_empty() {
                    self.reconcile()?;
                    last = Instant::now();
                    first = false;
                }
                self.games.lock().unwrap().retain(|_, h| !h.is_finished());
                if !self.games.lock().unwrap().is_empty() {
                    self.nap(self.o.busy_poll);
                } else {
                    let turn = self.client.wait_for_turn("", self.o.wait_seconds)?;
                    self.events(&turn.events)?;
                    if let Some(p) = &turn.position {
                        if p.status != "finished" && p.status != "aborted" && !self.games.lock().unwrap().contains_key(&p.game_id) { self.spawn(&p.game_id); }
                    }
                }
                Ok(())
            })();
            match step {
                Ok(()) => backoff = Duration::from_secs(1),
                Err(e @ Error::Auth { .. }) => { self.log(&format!("the API key was rejected ({}); stopping", cut(&e.to_string(), 80))); result = Err(e); break; }
                Err(Error::RateLimited { retry_after, .. }) => self.nap(retry_after.min(Duration::from_secs(30))),
                Err(e) => {
                    self.log(&format!("connection problem ({}); retrying in {}s", cut(&e.to_string(), 100), backoff.as_secs()));
                    self.nap(backoff.mul_f64(0.5 + (rand_u64() % 1000) as f64 / 1000.0));
                    backoff = (backoff * 2).min(self.o.max_backoff);
                }
            }
        }
        self.stop();
        let _ = hb.join();
        let handles: Vec<JoinHandle<()>> = self.games.lock().unwrap().drain().map(|(_, h)| h).collect();
        // game threads stop on their own at the next poll; do not wait for a long think
        drop(handles);
        if self.standby_ok.load(Ordering::SeqCst) && self.client.standby(&self.name, true).is_ok() { self.log("off duty"); }
        result
    }

    fn reconcile(self: &Arc<Self>) -> Result<(), Error> {
        let lobby = self.client.whoami()?;
        for g in lobby["active_games"].as_array().into_iter().flatten() {
            let id = s(g, "game_id");
            if !self.games.lock().unwrap().contains_key(id) { self.spawn(id); }
        }
        let evs: Vec<Value> = lobby["events"].as_array().cloned().unwrap_or_default();
        self.events(&evs)?;
        let pending: HashSet<String> = evs.iter().filter(|e| s(e, "type") == "challenge_received").map(|e| s(&e["data"], "challenge_id").to_string()).collect();
        for ch in lobby["incoming_challenges"].as_array().into_iter().flatten() {
            let st = s(ch, "status");
            if (st.is_empty() || st == "pending") && !pending.contains(s(ch, "id")) { self.challenge(s(ch, "id"), &info_from_challenge(ch), s(&ch["from"], "name"))?; }
        }
        if let Some(max) = evs.iter().filter_map(|e| e["seq"].as_i64()).max() {
            if max > 0 { self.client.list_challenges(Some(max))?; }
        }
        Ok(())
    }

    fn events(self: &Arc<Self>, evs: &[Value]) -> Result<(), Error> {
        for ev in evs {
            let d = &ev["data"];
            match s(ev, "type") {
                "challenge_received" => self.challenge(s(d, "challenge_id"), &info_from_event(ev), if s(d, "from").is_empty() { "?" } else { s(d, "from") })?,
                "game_started" => { let id = s(d, "game_id"); if !id.is_empty() && !self.games.lock().unwrap().contains_key(id) { self.spawn(id); } }
                t @ ("tournament_offline_warning" | "tournament_withdrawn" | "queue_dropped") => {
                    self.log(&format!("{t}: {}", if s(d, "next").is_empty() { s(d, "message") } else { s(d, "next") }));
                }
                "challenge_declined" => self.log(&format!("challenge declined: {}", s(d, "reason"))),
                _ => {}
            }
        }
        Ok(())
    }

    fn challenge(self: &Arc<Self>, cid: &str, info: &Value, who: &str) -> Result<(), Error> {
        if cid.is_empty() { return Ok(()); }
        let d = self.rules.lock().unwrap().evaluate(info, self.games.lock().unwrap().len());
        let first = !self.seen.lock().unwrap().contains(cid);
        match d.action.as_str() {
            "accept" => {
                let pos = match self.client.accept_challenge(cid) {
                    Ok(p) => p,
                    Err(Error::Tool(e)) => {
                        self.log(&format!("could not accept {cid} from {who}: {}", cut(&e.message, 120)));
                        self.seen.lock().unwrap().insert(cid.into());
                        return Ok(());
                    }
                    Err(e) => return Err(e),
                };
                self.accepted.fetch_add(1, Ordering::SeqCst);
                self.seen.lock().unwrap().insert(cid.into());
                self.log(&format!("accepted {cid} from {who} ({} {}); game {}", s(info, "speed"), s(info, "variant"), pos.game_id));
                self.spawn(&pos.game_id);
            }
            "decline" => {
                if first { self.log(&format!("declined {cid} from {who}: {}", d.reason)); }
                match self.client.decline_challenge(cid, &cut(&d.reason, 200)) { Err(e) if !matches!(e, Error::Tool(_)) => return Err(e), _ => {} }
                self.seen.lock().unwrap().insert(cid.into());
            }
            _ => if first {
                self.log(&format!("challenge {cid} from {who} left for the owner: {}", d.reason));
                let mut seen = self.seen.lock().unwrap();
                seen.insert(cid.into());
                if seen.len() > 500 { seen.clear(); }
            },
        }
        Ok(())
    }

    fn spawn(self: &Arc<Self>, game_id: &str) {
        if game_id.is_empty() { return; }
        if self.cooldown.lock().unwrap().get(game_id).is_some_and(|t| t.elapsed() < Duration::from_secs(5)) { return; }
        let mut games = self.games.lock().unwrap();
        if games.contains_key(game_id) { return; }
        let me = self.clone();
        let gid = game_id.to_string();
        games.insert(gid.clone(), std::thread::spawn({ let gid = gid.clone(); move || me.play_game(&gid) }));
        drop(games);
        self.log(&format!("playing game {game_id}"));
    }

    fn play_game(self: &Arc<Self>, game_id: &str) {
        let h = self.handler.clone();
        let po = PlayOptions { game_id: game_id.into(), max_illegal: self.o.max_illegal, max_retries: self.o.max_retries, wait_seconds: self.o.wait_seconds, ..Default::default() };
        match self.client.play(|p| h(p), &po) {
            Ok(fin) => { self.log(&format!("game {game_id} over: {}", fin.result_score)); self.played.fetch_add(1, Ordering::SeqCst); }
            Err(e @ Error::Auth { .. }) => { self.log(&format!("the API key was rejected while playing ({}); stopping", cut(&e.to_string(), 60))); self.stop(); }
            Err(e) => { self.log(&format!("game {game_id}: {}", cut(&e.to_string(), 120))); self.cooldown.lock().unwrap().insert(game_id.into(), Instant::now()); }
        }
    }
}

fn cut(s: &str, n: usize) -> String { s.chars().take(n).collect() }
