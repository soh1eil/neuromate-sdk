//! Unit tests against the recordings in ../testdata, through a scripted fake server (no network).
use neuromate::*;
use serde_json::json;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;

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
    content_type: String,
}

impl Fake {
    fn on(self, tool: &str, results: Vec<Value>) -> Fake {
        self.st.lock().unwrap().script.insert(tool.into(), results);
        self
    }
    fn names(&self) -> Vec<String> { self.st.lock().unwrap().names.clone() }
    fn args(&self, i: usize) -> Value { self.st.lock().unwrap().args[i].clone() }
    fn client(&self, model: Option<Model>) -> Client {
        Client::new(ClientOptions { api_key: Some("nm_live_test".into()), transport: Some(Box::new(self.clone())), model, ..Default::default() }).unwrap()
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
        let mut json_body = json!({"jsonrpc": "2.0", "id": req["id"], "result": result}).to_string();
        let ct = if self.content_type.is_empty() { "application/json" } else { &self.content_type };
        if ct.contains("event-stream") {
            json_body = format!("event: message\ndata: {json_body}\n\n");
        }
        Ok(Reply { status: 200, headers: HashMap::from([("content-type".to_string(), ct.to_string())]), body: json_body })
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

#[test]
fn queue_then_play_until_game_over() {
    let f = Fake::default().on("whoami", vec![result("whoami_idle")]).on("join_queue", vec![result("join_queue_waiting")])
        .on("wait_for_turn", vec![wait_result("your_turn", "get_game_start"), wait_result("game_over", "get_game_finished")])
        .on("make_move", vec![result("move_ok")]);
    let mut seen = 0;
    let end = f.client(None).play(|_| { seen += 1; Move::with_note("e4", "centre") }, &PlayOptions { time_control: "3+0".into(), ..Default::default() }).unwrap();
    assert!(end.is_over());
    assert_eq!(seen, 1);
    assert_eq!(f.names(), ["whoami", "join_queue", "wait_for_turn", "make_move", "wait_for_turn"]);
    let mv = f.args(3);
    assert_eq!(mv["move"], "e4");
    assert_eq!(mv["note"], "centre");
    assert_eq!(mv["expected_ply"], 0);
    assert!(mv["client_move_id"].as_str().unwrap().starts_with(&end.game_id));
    assert_eq!(f.args(1), json!({"time_control": "3+0"}));
}

#[test]
fn illegal_move_is_typed_and_carries_code() {
    let f = Fake::default().on("make_move", vec![result("err_illegal_move")]);
    let e = f.client(None).make_move("", "e2e5", "x2", None, "").err().unwrap();
    assert!(e.is_illegal_move());
    assert_eq!(e.code(), Some("illegal_move"));
    let t = e.tool().unwrap();
    assert_eq!(t.legal_moves.len(), 20);
    assert!(t.legal_moves.contains(&"e2e4".to_string()));
    assert!(t.position.is_some());
    assert!(e.to_string().contains("ERROR (illegal_move)"));
}

#[test]
fn rejected_moves_are_fed_back_then_fallback_is_used() {
    let f = Fake::default().on("wait_for_turn", vec![wait_result("your_turn", "get_game_start"), wait_result("game_over", "get_game_finished")])
        .on("make_move", vec![result("err_illegal_move"), result("err_illegal_move"), result("move_ok")]);
    let mut seen = vec![];
    f.client(None).play(|p| { seen.push(p.illegal_attempts.len()); Move::uci("e2e5") }, &PlayOptions { game_id: "g76p6aeov4".into(), max_illegal: 1, ..Default::default() }).unwrap();
    assert_eq!(seen, vec![0]);
    assert_eq!(f.names().iter().filter(|n| *n == "make_move").count(), 3);
}

#[test]
fn model_is_reported_before_the_first_call() {
    let f = Fake::default().on("report_model", vec![result("report_model")]).on("join_queue", vec![result("join_queue_waiting")]);
    f.client(Some(Model::new("Claude Opus 5", "2026-06", "Anthropic"))).join_queue(json!({}), "").unwrap();
    assert_eq!(f.names(), ["report_model", "join_queue"]);
    assert_eq!(f.args(0)["name"], "Claude Opus 5");
    assert_eq!(f.args(0)["provider"], "Anthropic");
}

#[test]
fn model_not_reported_is_repaired_once() {
    let f = Fake::default().on("report_model", vec![result("report_model")]).on("join_queue", vec![result("err_model_not_reported"), result("join_queue_waiting")]);
    f.client(Some(Model::new("Claude Opus 5", "", ""))).join_queue(json!({}), "").unwrap();
    assert_eq!(f.names(), ["report_model", "join_queue", "report_model", "join_queue"]);
}

#[test]
fn model_not_reported_without_model_explains_how_to_fix() {
    let f = Fake::default().on("join_queue", vec![result("err_model_not_reported")]);
    let e = f.client(None).join_queue(json!({}), "").err().unwrap();
    assert!(e.is_model_not_reported());
    assert!(e.tool().unwrap().message.contains("Model::new("));
}

#[test]
fn unauthorized_is_auth_error() {
    let f = Fake { forced: Some((401, fixture("http_401")["response"].to_string())), ..Default::default() };
    let e = f.client(None).whoami().err().unwrap();
    assert!(matches!(e, Error::Auth { .. }));
    assert_eq!(e.code(), Some("unauthorized"));
    assert!(!e.retryable());
    assert!(e.to_string().contains("Authorization: Bearer"));
}

#[test]
fn server_errors_are_retryable() {
    let f = Fake { forced: Some((503, "down".into())), ..Default::default() };
    assert!(f.client(None).whoami().err().unwrap().retryable());
}

#[test]
fn server_sent_events_reply_is_unwrapped() {
    let f = Fake { content_type: "text/event-stream".into(), ..Default::default() }.on("whoami", vec![result("whoami_idle")]);
    assert_eq!(f.client(None).whoami().unwrap()["you"]["name"], "Alice");
}

#[test]
fn tournaments_come_back_as_values() {
    let f = Fake::default().on("list_tournaments", vec![result("tournaments_list")]);
    let d = f.client(None).list_tournaments("registering,running", "swiss").unwrap();
    assert_eq!(d["tournaments"][0]["id"], "t_k3m9x2qa");
    assert_eq!(f.args(0), json!({"status": "registering,running", "kind": "swiss"}));
}

#[test]
fn position_parses_clocks_and_legal_moves() {
    let p = Position::from_value(&result("get_game_start")["structuredContent"]["game"], "");
    assert_eq!(p.legal_moves.len(), 20);
    assert_eq!(p.color, "white");
    assert!(p.your_turn);
    assert!(p.seconds_for_this_move().unwrap() > 80.0);
}

#[test]
fn chat_is_typed_and_untrusted() {
    let f = Fake::default().on("send_chat", vec![result("send_chat")]).on("read_chat", vec![result("read_chat")]).on("get_game", vec![result("get_game_strict")]);
    let c = f.client(None);
    let m = c.send_chat("gzcffjrmdb", "good luck!").unwrap();
    assert_eq!((m.text.as_str(), m.author_kind.as_str()), ("good luck!", "agent"));
    assert_eq!(f.args(0)["text"], "good luck!");
    let log = c.read_chat("gzcffjrmdb", 0).unwrap();
    assert_eq!(log.last_seq, 1);
    assert!(log.messages[0].untrusted);
    assert!(f.args(1).get("after").is_none());
    let p = c.get_game("").unwrap();
    assert!(p.strict() && !p.hints_allowed);
}
