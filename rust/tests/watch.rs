//! Watcher tests against the recordings in ../testdata, through a scripted fake server (no network).
use neuromate::*;
use serde_json::json;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

#[derive(Default)]
struct State {
    script: HashMap<String, Vec<Value>>,
    names: Vec<String>,
    args: Vec<Value>,
}

#[derive(Clone, Default)]
struct Fake {
    st: Arc<Mutex<State>>,
    forced: Option<(u16, String)>,
}

impl Fake {
    fn on(self, tool: &str, results: Vec<Value>) -> Fake {
        self.st.lock().unwrap().script.insert(tool.into(), results);
        self
    }
    fn names(&self) -> Vec<String> { self.st.lock().unwrap().names.clone() }
    fn has(&self, n: &str) -> bool { self.names().iter().any(|x| x == n) }
    fn args(&self, i: usize) -> Value { self.st.lock().unwrap().args[i].clone() }
    fn last_args(&self) -> Value { self.st.lock().unwrap().args.last().cloned().unwrap() }
    fn client(&self) -> Arc<Client> {
        Arc::new(Client::new(ClientOptions { api_key: Some("nm_live_test".into()), transport: Some(Box::new(self.clone())), ..Default::default() }).unwrap())
    }
}

impl Transport for Fake {
    fn post(&self, _url: &str, _h: &[(String, String)], body: &str, _t: Duration) -> Result<Reply, Error> {
        if let Some((s, b)) = &self.forced {
            return Ok(Reply { status: *s, headers: HashMap::new(), body: b.clone() });
        }
        let req: Value = serde_json::from_str(body).unwrap();
        let method = req["method"].as_str().unwrap();
        if method == "notifications/initialized" {
            return Ok(Reply { status: 202, headers: HashMap::new(), body: String::new() });
        }
        let result = if method == "initialize" {
            json!({"serverInfo": {"name": "fake", "version": "1"}})
        } else {
            let name = req["params"]["name"].as_str().unwrap().to_string();
            let mut st = self.st.lock().unwrap();
            st.names.push(name.clone());
            st.args.push(req["params"]["arguments"].clone());
            let q = st.script.get_mut(&name).unwrap_or_else(|| panic!("no scripted reply for {name}"));
            if q.len() > 1 { q.remove(0) } else { q[0].clone() }
        };
        let json_body = json!({"jsonrpc": "2.0", "id": req["id"], "result": result}).to_string();
        Ok(Reply { status: 200, headers: HashMap::from([("content-type".to_string(), "application/json".to_string())]), body: json_body })
    }
}

fn fixture(name: &str) -> Value {
    let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..").join("testdata").join(format!("{name}.json"));
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}
fn result(name: &str) -> Value { fixture(name)["response"]["result"].clone() }
fn wait_result(reason: &str, game_from: &str) -> Value {
    json!({"content": [{"type": "text", "text": "WAIT"}], "structuredContent": {"reason": reason, "events": [], "game": result(game_from)["structuredContent"]["game"]}})
}
fn on_rules(patch: Value) -> Value {
    let mut r = json!({"enabled": true, "speeds": ["blitz"], "variants": ["standard"], "rated": "any", "ruleset": "any", "opponent_kinds": [], "max_concurrent": 1, "decline_others": false});
    for (k, v) in patch.as_object().into_iter().flatten() { r[k] = v.clone(); }
    r
}
fn standby(patch: Value) -> Value {
    let mut r = result("standby_on");
    r["structuredContent"]["rules"] = on_rules(patch);
    r
}
fn base(standby: Value, waits: Vec<Value>) -> Fake {
    Fake::default().on("standby", vec![standby]).on("whoami", vec![result("whoami_idle")]).on("list_challenges", vec![result("list_challenges_empty")])
        .on("wait_for_turn", waits).on("accept_challenge", vec![result("get_game_start")]).on("make_move", vec![result("move_ok")]).on("decline_challenge", vec![result("cancel")])
}

fn run(f: &Fake, h: impl Fn(&Position) -> Move + Send + Sync + 'static, rules: Option<Rules>, logs: Arc<Mutex<Vec<String>>>, until: impl Fn(&Watcher) -> bool) -> Arc<Watcher> {
    let l = logs.clone();
    let o = WatchOptions { rules, heartbeat_every: Duration::from_millis(50), busy_poll: Duration::from_millis(20), wait_seconds: 0.01,
                           log: Some(Arc::new(move |m: &str| l.lock().unwrap().push(m.to_string()))), ..Default::default() };
    let w = Arc::new(Watcher::new(f.client(), h, o));
    let w2 = w.clone();
    let t = std::thread::spawn(move || w2.run());
    let deadline = Instant::now() + Duration::from_secs(10);
    while Instant::now() < deadline && !until(&w) && !t.is_finished() { std::thread::sleep(Duration::from_millis(20)); }
    w.stop();
    let end = Instant::now() + Duration::from_secs(5);
    while !t.is_finished() && Instant::now() < end { std::thread::sleep(Duration::from_millis(10)); }
    assert!(t.is_finished(), "the watcher did not stop");
    let _ = t.join();
    w
}

fn e4(_: &Position) -> Move { Move::uci("e4") }

#[test]
fn rules_match_the_shared_vectors() {
    let cases = fixture_raw("auto_accept_cases");
    assert!(cases.as_array().unwrap().len() >= 15);
    for c in cases.as_array().unwrap() {
        let got = Rules::from_value(&c["rules"]).evaluate(&c["challenge"], c["playing"].as_u64().unwrap() as usize).action;
        assert_eq!(got, c["expect"].as_str().unwrap(), "{}", c["name"]);
    }
}

fn fixture_raw(name: &str) -> Value {
    let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..").join("testdata").join(format!("{name}.json"));
    serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap()
}

#[test]
fn speed_and_challenge_info() {
    let got: Vec<String> = ["1+0", "3+2", "10+0", "30+0", "2d", "untimed"].iter().map(|x| speed_of(&Value::Null, x)).collect();
    assert_eq!(got, ["bullet", "blitz", "rapid", "classical", "correspondence", "untimed"]);
    assert_eq!(speed_of(&json!({"kind": "realtime", "initial_ms": 180000, "increment_ms": 2000}), ""), "blitz");
    let info = info_from_event(&result("wait_challenge_received")["structuredContent"]["events"][0]);
    assert_eq!((info["speed"].as_str(), info["from_kind"].as_str(), info["variant"].as_str(), info["rated"].as_bool()), (Some("blitz"), Some("agent"), Some("standard"), Some(false)));
    assert_eq!(info_from_challenge(&result("list_challenges_busy")["structuredContent"]["incoming_challenges"][0])["speed"], "rapid");
}

#[test]
fn standby_parses_the_owners_rules() {
    let f = Fake::default().on("standby", vec![standby(json!({"speeds": ["bullet"]}))]);
    let d = f.client().standby("x", false).unwrap();
    assert!(d.on_duty);
    assert_eq!(d.rules.speeds, ["bullet"]);
    assert_eq!(d.heartbeat_every_seconds, 30);
}

#[test]
fn accepts_a_matching_challenge_and_plays_the_game() {
    let f = base(standby(json!({})), vec![result("wait_challenge_received"), wait_result("your_turn", "get_game_start"), wait_result("game_over", "resign"), result("wait_timeout")]);
    let w = run(&f, e4, None, Default::default(), |w| w.played() >= 1);
    assert_eq!((w.played(), w.accepted()), (1, 1));
    let names = f.names();
    assert_eq!(names[0], "standby");
    assert!(f.has("accept_challenge") && f.has("make_move"));
    assert_eq!(f.args(names.iter().position(|n| n == "accept_challenge").unwrap())["id"], "ch_c4v3a4vr");
    assert_eq!(f.last_args()["action"], "off", "a clean shutdown ends the duty");
    assert!(f.args(0)["client"].as_str().unwrap().starts_with("neuromate-rs/"));
}

#[test]
fn a_challenge_outside_the_rules_is_left_or_declined() {
    let f = base(standby(json!({"speeds": ["bullet"]})), vec![result("wait_challenge_received"), result("wait_timeout")]);
    let logs: Arc<Mutex<Vec<String>>> = Default::default();
    let l = logs.clone();
    run(&f, e4, None, logs, move |_| l.lock().unwrap().iter().any(|m| m.contains("left for the owner")));
    assert!(!f.has("accept_challenge") && !f.has("decline_challenge"));
    let f = base(standby(json!({"speeds": ["bullet"], "decline_others": true})), vec![result("wait_challenge_received"), result("wait_timeout")]);
    let ff = f.clone();
    run(&f, e4, None, Default::default(), move |_| ff.has("decline_challenge"));
    assert!(!f.has("accept_challenge"));
    let i = f.names().iter().position(|n| n == "decline_challenge").unwrap();
    assert!(f.args(i)["reason"].as_str().unwrap().contains("blitz"));
}

#[test]
fn local_rules_override_the_owners_and_a_panicking_handler_survives() {
    let f = base(standby(json!({"enabled": false})), vec![result("wait_challenge_received"), wait_result("your_turn", "get_game_start"), wait_result("game_over", "resign"), result("wait_timeout")]);
    let logs: Arc<Mutex<Vec<String>>> = Default::default();
    let w = run(&f, |_| panic!("model crashed"), Some(Rules::enabled()), logs.clone(), |w| w.played() >= 1);
    assert_eq!(w.accepted(), 1);
    assert!(f.has("make_move"), "a fallback move was played");
    assert!(logs.lock().unwrap().iter().any(|m| m.contains("handler panicked")));
}

#[test]
fn resumes_a_running_game_after_a_restart() {
    let gid = result("get_game_start")["structuredContent"]["game"]["game_id"].as_str().unwrap().to_string();
    let mut busy = result("whoami_idle");
    busy["structuredContent"]["active_games"] = json!([{"game_id": gid, "opponent": "Bob", "color": "white", "your_turn": true, "ply": 0, "variant": "standard"}]);
    let f = base(standby(json!({})), vec![wait_result("your_turn", "get_game_start"), wait_result("game_over", "resign"), result("wait_timeout")]).on("whoami", vec![busy]);
    let w = run(&f, e4, None, Default::default(), |w| w.played() >= 1);
    assert_eq!(w.played(), 1);
    assert!(!f.has("accept_challenge"));
}

#[test]
fn a_rejected_key_stops_the_watcher() {
    let f = Fake { forced: Some((401, fixture("http_401")["response"].to_string())), ..Default::default() };
    let w = Watcher::new(f.client(), e4, WatchOptions { log: Some(Arc::new(|_| {})), ..Default::default() });
    assert!(matches!(Arc::new(w).run(), Err(Error::Auth { .. })));
}
