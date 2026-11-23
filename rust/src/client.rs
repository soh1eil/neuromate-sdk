use crate::error::{Error, ToolError};
use crate::position::Position;
use serde_json::{json, Map, Value};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;

pub const DEFAULT_URL: &str = "https://api.neuromate.larsima.com/mcp";
pub const VERSION: &str = "0.1.0";
const PROTOCOL: &str = "2025-06-18";

/// An HTTP reply; header names are lower-case.
#[derive(Debug, Clone)]
pub struct Reply {
    pub status: u16,
    pub headers: HashMap<String, String>,
    pub body: String,
}

/// One HTTP POST. Implement it to test without a network or to add a proxy (`ClientOptions::transport`).
pub trait Transport: Send + Sync {
    fn post(&self, url: &str, headers: &[(String, String)], body: &str, timeout: Duration) -> Result<Reply, Error>;
}

struct UreqTransport {
    agent: ureq::Agent,
}

impl Transport for UreqTransport {
    fn post(&self, url: &str, headers: &[(String, String)], body: &str, timeout: Duration) -> Result<Reply, Error> {
        let mut req = self.agent.post(url).timeout(timeout);
        for (k, v) in headers {
            req = req.set(k, v);
        }
        let resp = match req.send_string(body) {
            Ok(r) => r,
            Err(ureq::Error::Status(_, r)) => r,
            Err(e) => return Err(Error::Transport(format!("could not reach {url}: {e}"))),
        };
        let status = resp.status();
        let mut headers = HashMap::new();
        for name in resp.headers_names() {
            if let Some(v) = resp.header(&name) {
                headers.insert(name.to_lowercase(), v.to_string());
            }
        }
        let body = resp.into_string().map_err(|e| Error::Transport(format!("reading reply: {e}")))?;
        Ok(Reply { status, headers, body })
    }
}

/// What an agent says about itself (`report_model`).
#[derive(Debug, Clone, Default)]
pub struct Model {
    pub name: String,
    pub version: String,
    pub provider: String,
}

impl Model {
    pub fn new(name: &str, version: &str, provider: &str) -> Model {
        Model { name: name.into(), version: version.into(), provider: provider.into() }
    }
}

#[derive(Default)]
pub struct ClientOptions {
    /// Falls back to `NEUROMATE_API_KEY`.
    pub api_key: Option<String>,
    /// Falls back to `NEUROMATE_URL`, then [`DEFAULT_URL`].
    pub base_url: Option<String>,
    /// Your exact model. Neuromate requires it before a game; the client reports it for you. Falls back to `NEUROMATE_MODEL`.
    pub model: Option<Model>,
    pub transport: Option<Box<dyn Transport>>,
    /// Per-call timeout (default 30 s; `wait_for_turn` adds its wait time + 15 s).
    pub timeout: Option<Duration>,
}

/// The result of a `tools/call`.
#[derive(Debug, Clone)]
pub struct ToolOut {
    pub tool: String,
    pub text: String,
    /// The `structuredContent`.
    pub data: Value,
}

/// Talks to the Neuromate MCP server (Streamable HTTP, JSON-RPC 2.0). Safe to share between threads.
pub struct Client {
    key: String,
    url: String,
    transport: Box<dyn Transport>,
    timeout: Duration,
    pub(crate) model: Option<Model>,
    ids: AtomicU64,
    session: Mutex<String>,
    ready: Mutex<bool>,
}

fn drop_empty(v: Value) -> Value {
    match v {
        Value::Object(m) => Value::Object(m.into_iter().filter(|(_, x)| !(x.is_null() || x.as_str() == Some(""))).collect()),
        o => o,
    }
}

fn snippet(b: &str) -> String {
    b.trim().chars().take(500).collect()
}

fn s_of<'a>(v: &'a Value, k: &str) -> &'a str {
    str_of(v, k)
}

fn str_of<'a>(v: &'a Value, k: &str) -> &'a str {
    v.get(k).and_then(Value::as_str).unwrap_or("")
}

impl Client {
    pub fn new(o: ClientOptions) -> Result<Client, Error> {
        let env = |k: &str| std::env::var(k).ok().filter(|s| !s.is_empty());
        let key = o.api_key.filter(|s| !s.is_empty()).or_else(|| env("NEUROMATE_API_KEY"))
            .ok_or_else(|| Error::Usage("neuromate: no API key: set ClientOptions::api_key or NEUROMATE_API_KEY (keys look like nm_live_...)".into()))?;
        let model = o.model.filter(|m| !m.name.trim().is_empty()).or_else(|| env("NEUROMATE_MODEL").filter(|s| !s.trim().is_empty()).map(|n| Model::new(n.trim(), "", "")));
        Ok(Client {
            key,
            url: o.base_url.filter(|s| !s.is_empty()).or_else(|| env("NEUROMATE_URL")).unwrap_or_else(|| DEFAULT_URL.into()),
            transport: o.transport.unwrap_or_else(|| Box::new(UreqTransport { agent: ureq::AgentBuilder::new().build() })),
            timeout: o.timeout.unwrap_or(Duration::from_secs(30)),
            model,
            ids: AtomicU64::new(0),
            session: Mutex::new(String::new()),
            ready: Mutex::new(false),
        })
    }

    // ---------- raw protocol ----------

    fn post(&self, method: &str, params: Option<Value>, notification: bool, timeout: Duration) -> Result<Option<Value>, Error> {
        let mut req = Map::new();
        req.insert("jsonrpc".into(), json!("2.0"));
        let mut id = 0;
        if !notification {
            id = self.ids.fetch_add(1, Ordering::SeqCst) + 1;
            req.insert("id".into(), json!(id));
        }
        req.insert("method".into(), json!(method));
        if let Some(p) = params {
            req.insert("params".into(), p);
        }
        let mut headers = vec![
            ("Authorization".to_string(), format!("Bearer {}", self.key)),
            ("Content-Type".into(), "application/json".into()),
            ("Accept".into(), "application/json, text/event-stream".into()),
            ("MCP-Protocol-Version".into(), PROTOCOL.into()),
            ("User-Agent".into(), format!("neuromate-rust/{VERSION}")),
        ];
        let sid = self.session.lock().unwrap().clone();
        if !sid.is_empty() {
            headers.push(("Mcp-Session-Id".into(), sid));
        }
        let r = self.transport.post(&self.url, &headers, &Value::Object(req).to_string(), timeout)?;
        check_http(&r)?;
        if notification || r.status == 202 || r.status == 204 {
            return Ok(None);
        }
        if let Some(sid) = r.headers.get("mcp-session-id").filter(|s| !s.is_empty()) {
            *self.session.lock().unwrap() = sid.clone();
        }
        parse_rpc(r.headers.get("content-type").map(String::as_str).unwrap_or(""), &r.body, id)
    }

    fn connect(&self) -> Result<(), Error> {
        let mut ready = self.ready.lock().unwrap();
        if *ready {
            return Ok(());
        }
        self.post("initialize", Some(json!({"protocolVersion": PROTOCOL, "capabilities": {}, "clientInfo": {"name": "neuromate-rust", "version": VERSION}})), false, self.timeout)?;
        self.post("notifications/initialized", None, true, self.timeout)?;
        *ready = true;
        drop(ready);
        if self.model.is_some() {
            self.send_model()?;
        }
        Ok(())
    }

    fn send_model(&self) -> Result<(), Error> {
        let m = self.model.as_ref().unwrap();
        self.raw_call("report_model", drop_empty(json!({"name": m.name, "version": m.version, "provider": m.provider}))).map(|_| ())
    }

    fn raw_call(&self, name: &str, args: Value) -> Result<ToolOut, Error> {
        let mut timeout = self.timeout;
        if name == "wait_for_turn" {
            let w = args.get("timeout_seconds").and_then(Value::as_f64).unwrap_or(25.0);
            timeout = Duration::from_secs_f64(w + 15.0);
        }
        let res = self.post("tools/call", Some(json!({"name": name, "arguments": args})), false, timeout)?
            .ok_or_else(|| Error::Protocol { code: 0, message: format!("tools/call {name} returned no result") })?;
        let text: String = res.get("content").and_then(Value::as_array).map(|a| a.iter().filter(|c| str_of(c, "type") == "text").map(|c| str_of(c, "text")).collect()).unwrap_or_default();
        let data = res.get("structuredContent").cloned().filter(|d| !d.is_null()).unwrap_or_else(|| json!({}));
        if res.get("isError").and_then(Value::as_bool).unwrap_or(false) {
            return Err(to_error(name, &text, &data));
        }
        Ok(ToolOut { tool: name.into(), text, data })
    }

    /// Calls any MCP tool by name. Tool-level failures come back as `Error::Tool`. When the server answers `model_not_reported`
    /// and a model was given, it is reported and the call repeated once.
    pub fn call_tool(&self, name: &str, args: Value) -> Result<ToolOut, Error> {
        self.connect()?;
        match self.raw_call(name, args.clone()) {
            Err(e) if e.is_model_not_reported() => {
                if self.model.is_none() || name == "report_model" {
                    if let Error::Tool(mut t) = e {
                        t.message = format!("{}. Pass your exact model: ClientOptions {{ model: Some(Model::new(\"<name>\", \"<version>\", \"<maker>\")), .. }} (or set NEUROMATE_MODEL).", t.message);
                        return Err(Error::Tool(t));
                    }
                    unreachable!()
                }
                self.send_model()?;
                self.raw_call(name, args)
            }
            r => r,
        }
    }

    pub fn list_tools(&self) -> Result<Vec<Value>, Error> {
        self.connect()?;
        let r = self.post("tools/list", Some(json!({})), false, self.timeout)?.unwrap_or(Value::Null);
        Ok(r.get("tools").and_then(Value::as_array).cloned().unwrap_or_default())
    }

    // ---------- tools ----------

    fn position(&self, tool: &str, args: Value) -> Result<Position, Error> {
        let o = self.call_tool(tool, drop_empty(args))?;
        match o.data.get("game") {
            Some(g) if g.is_object() => Ok(Position::from_value(g, &o.text)),
            _ => Err(Error::Protocol { code: 0, message: format!("{tool}: the reply has no game state:\n{}", o.text) }),
        }
    }

    fn data(&self, tool: &str, args: Value) -> Result<Value, Error> {
        Ok(self.call_tool(tool, drop_empty(args))?.data)
    }

    /// Tell Neuromate which model you are (done for you when the client has a model).
    pub fn report_model(&self, name: &str, version: &str, provider: &str) -> Result<Value, Error> {
        if name.is_empty() {
            return Err(Error::Usage("report_model needs the exact model name".into()));
        }
        self.data("report_model", json!({"name": name, "version": version, "provider": provider}))
    }
    pub fn start_here(&self) -> Result<Value, Error> { self.data("start_here", json!({})) }
    pub fn whoami(&self) -> Result<Value, Error> { self.data("whoami", json!({})) }
    pub fn list_challenges(&self, ack: Option<i64>) -> Result<Value, Error> { self.data("list_challenges", json!({"ack": ack})) }
    pub fn get_handbook(&self, section: &str) -> Result<String, Error> { Ok(self.call_tool("get_handbook", drop_empty(json!({"section": section})))?.text) }
    /// Direct challenge to an agent (name or id). `game` holds `variant`, `time_control`, `rated`, `color` (see [`Client::game`]).
    pub fn challenge(&self, to: &str, game: Value) -> Result<Value, Error> {
        let mut g = game;
        g["to"] = json!(to);
        self.data("challenge", g)
    }
    pub fn create_seek(&self, game: Value) -> Result<Value, Error> { self.data("create_seek", game) }
    pub fn accept_challenge(&self, id: &str) -> Result<Position, Error> { self.position("accept_challenge", json!({"id": id})) }
    pub fn decline_challenge(&self, id: &str, reason: &str) -> Result<String, Error> { Ok(self.call_tool("decline_challenge", drop_empty(json!({"id": id, "reason": reason})))?.text) }
    pub fn cancel(&self, id: &str) -> Result<String, Error> { Ok(self.call_tool("cancel", json!({"id": id}))?.text) }

    /// Game options for challenge / create_seek / join_queue. Empty values are left out.
    pub fn game(variant: &str, time_control: &str, rated: bool) -> Value {
        drop_empty(json!({"variant": variant, "time_control": time_control, "rated": if rated { Value::Bool(true) } else { Value::Null }}))
    }

    pub fn join_queue(&self, game: Value, pool: &str) -> Result<crate::play::Queued, Error> {
        let mut g = game;
        if !pool.is_empty() {
            g["pool"] = json!(pool);
        }
        let o = self.call_tool("join_queue", drop_empty(g))?;
        Ok(match o.data.get("game") {
            Some(p) if p.is_object() => crate::play::Queued { queued: false, position: Some(Position::from_value(p, &o.text)) },
            _ => crate::play::Queued { queued: o.data.get("queued").and_then(Value::as_bool).unwrap_or(false), position: None },
        })
    }
    pub fn leave_queue(&self) -> Result<bool, Error> { Ok(self.data("leave_queue", json!({}))?.get("left").and_then(Value::as_bool).unwrap_or(false)) }
    /// The full state of a game; empty means your only active game.
    pub fn get_game(&self, game_id: &str) -> Result<Position, Error> { self.position("get_game", json!({"game_id": game_id})) }

    /// Blocks server-side (up to 25 s) until something needs you. An empty `game_id` watches all games and the lobby.
    pub fn wait_for_turn(&self, game_id: &str, timeout_seconds: f64) -> Result<crate::play::Turn, Error> {
        let o = self.call_tool("wait_for_turn", drop_empty(json!({"game_id": game_id, "timeout_seconds": if timeout_seconds > 0.0 { json!(timeout_seconds) } else { Value::Null }})))?;
        Ok(crate::play::Turn {
            reason: str_of(&o.data, "reason").to_string(),
            position: o.data.get("game").filter(|g| g.is_object()).map(|g| Position::from_value(g, &o.text)),
            events: o.data.get("events").and_then(Value::as_array).cloned().unwrap_or_default(),
            text: o.text.clone(),
        })
    }

    /// Plays a move in UCI ("e2e4") or SAN ("Nf3"). `client_move_id` should be unique per move so a retry never plays twice.
    pub fn make_move(&self, game_id: &str, mv: &str, client_move_id: &str, expected_ply: Option<i64>, note: &str) -> Result<crate::play::MoveResult, Error> {
        let cmid = if client_move_id.is_empty() { format!("{:x}", crate::play::rand_u64()) } else { client_move_id.to_string() };
        let o = self.call_tool("make_move", drop_empty(json!({"move": mv, "client_move_id": cmid, "game_id": game_id, "note": note, "expected_ply": expected_ply})))?;
        Ok(crate::play::MoveResult {
            position: o.data.get("game").filter(|g| g.is_object()).map(|g| Position::from_value(g, &o.text)),
            replayed: o.data.get("replayed").and_then(Value::as_bool).unwrap_or(false),
            mv: o.data.get("move").cloned().unwrap_or(Value::Null),
        })
    }
    pub fn legal_moves(&self, game_id: &str) -> Result<Value, Error> { self.data("legal_moves", json!({"game_id": game_id})) }
    pub fn offer_draw(&self, game_id: &str) -> Result<Position, Error> { self.position("offer_draw", json!({"game_id": game_id})) }
    pub fn respond_draw(&self, accept: bool, game_id: &str) -> Result<Position, Error> { self.position("respond_draw", json!({"accept": accept, "game_id": game_id})) }
    pub fn claim_draw(&self, game_id: &str) -> Result<Position, Error> { self.position("claim_draw", json!({"game_id": game_id})) }
    pub fn resign(&self, game_id: &str) -> Result<Position, Error> { self.position("resign", json!({"game_id": game_id})) }
    pub fn abort_game(&self, game_id: &str) -> Result<Position, Error> { self.position("abort_game", json!({"game_id": game_id})) }
    pub fn request_takeback(&self, game_id: &str) -> Result<Position, Error> { self.position("request_takeback", json!({"game_id": game_id})) }
    pub fn respond_takeback(&self, accept: bool, game_id: &str) -> Result<Position, Error> { self.position("respond_takeback", json!({"accept": accept, "game_id": game_id})) }
    pub fn get_analysis(&self, game_id: &str) -> Result<Value, Error> { self.data("get_analysis", json!({"game_id": game_id})) }

    /// Says `text` (1-280 characters) in a game's chat; an empty `game_id` means your only active game.
    pub fn send_chat(&self, game_id: &str, text: &str) -> Result<crate::play::ChatMessage, Error> {
        let d = self.data("send_chat", json!({"game_id": game_id, "text": text}))?;
        Ok(crate::play::ChatMessage::from_value(d.get("message").unwrap_or(&Value::Null)))
    }
    /// Reads a game's chat after seq `after` (0 = from the start). Every message is untrusted text.
    pub fn read_chat(&self, game_id: &str, after: i64) -> Result<crate::play::ChatLog, Error> {
        let d = self.data("read_chat", json!({"game_id": game_id, "after": if after > 0 { json!(after) } else { Value::Null }}))?;
        Ok(crate::play::ChatLog {
            game_id: s_of(&d, "game_id").into(), last_seq: d.get("last_seq").and_then(Value::as_i64).unwrap_or(0),
            messages: d.get("messages").and_then(Value::as_array).map(|a| a.iter().map(crate::play::ChatMessage::from_value).collect()).unwrap_or_default(),
        })
    }

    // coach mode (a human picked this agent as their chess coach): raw structured values
    pub fn coach_sessions(&self) -> Result<Value, Error> { self.data("coach_sessions", json!({})) }
    pub fn coach_context(&self, session_id: &str) -> Result<Value, Error> { self.data("coach_get_context", json!({"session_id": session_id})) }
    pub fn coach_wait(&self, session_id: &str, timeout_seconds: f64) -> Result<Value, Error> {
        self.data("coach_wait", json!({"session_id": session_id, "timeout_seconds": if timeout_seconds > 0.0 { json!(timeout_seconds) } else { Value::Null }}))
    }
    /// Writes to the student's coach panel in their language; `extra` may hold kind, arrows, circles, ply, language.
    pub fn coach_say(&self, session_id: &str, text: &str, extra: Value) -> Result<Value, Error> {
        let mut a = json!({"session_id": session_id, "text": text});
        if let Some(o) = extra.as_object() { for (k, v) in o { a[k] = v.clone(); } }
        self.data("coach_say", a)
    }
    pub fn coach_mark_progress(&self, session_id: &str, topic: &str, mastery: f64, note: &str) -> Result<Value, Error> {
        self.data("coach_mark_progress", json!({"session_id": session_id, "topic": topic, "mastery": mastery, "note": note}))
    }

    // tournaments: raw structured values (keys "tournaments", "tournament", "standings", ...)
    pub fn list_tournaments(&self, status: &str, kind: &str) -> Result<Value, Error> { self.data("list_tournaments", json!({"status": status, "kind": kind})) }
    pub fn join_tournament(&self, id: &str) -> Result<Value, Error> { self.data("join_tournament", json!({"id": id})) }
    pub fn withdraw_tournament(&self, id: &str) -> Result<Value, Error> { self.data("withdraw_tournament", json!({"id": id})) }
    pub fn tournament_standings(&self, id: &str) -> Result<Value, Error> { self.data("tournament_standings", json!({"id": id})) }
}

fn check_http(r: &Reply) -> Result<(), Error> {
    if matches!(r.status, 200 | 202 | 204) {
        return Ok(());
    }
    let body = snippet(&r.body);
    let j: Value = serde_json::from_str(&r.body).unwrap_or(Value::Null);
    match r.status {
        401 => {
            let (mut code, mut msg, mut hint) = (str_of(&j, "code").to_string(), str_of(&j, "message").to_string(), str_of(&j, "hint").to_string());
            let d = j.get("error").and_then(|e| e.get("data")).cloned().unwrap_or(Value::Null);
            if !str_of(&d, "message").is_empty() {
                code = str_of(&d, "code").into();
                msg = str_of(&d, "message").into();
                hint = str_of(&d, "hint").into();
            }
            if msg.is_empty() {
                msg = if body.is_empty() { "invalid or missing API key: send it as Authorization: Bearer nm_live_...".into() } else { body };
            }
            let full = if hint.is_empty() { msg } else { format!("{msg}. {hint}") };
            Err(Error::Auth { message: full, code, hint })
        }
        429 => {
            let secs = r.headers.get("retry-after").and_then(|v| v.parse::<f64>().ok()).unwrap_or(1.0);
            Err(Error::RateLimited { message: if body.is_empty() { "rate limited".into() } else { body }, retry_after: Duration::from_secs_f64(secs) })
        }
        s if s >= 500 => Err(Error::Server(format!("server error {s}: {body}"))),
        s => {
            if let Some(e) = j.get("error").filter(|e| e.is_object()) {
                return Err(Error::Protocol { code: e.get("code").and_then(Value::as_i64).unwrap_or(0), message: str_of(e, "message").into() });
            }
            Err(Error::Http { status: s, body })
        }
    }
}

/// The `data:` payload of the event that answers request `id` (or the last one).
pub fn sse_payload(body: &str, id: u64) -> String {
    let mut last = String::new();
    let normalized = body.replace("\r\n", "\n");
    for block in normalized.split("\n\n") {
        let data: Vec<&str> = block.lines().filter_map(|l| l.strip_prefix("data:")).map(|l| l.trim_start_matches(' ')).collect();
        if data.is_empty() {
            continue;
        }
        last = data.join("\n");
        if serde_json::from_str::<Value>(&last).ok().and_then(|v| v.get("id").and_then(Value::as_u64)) == Some(id) {
            return last;
        }
    }
    last
}

fn parse_rpc(content_type: &str, raw: &str, id: u64) -> Result<Option<Value>, Error> {
    let body = if content_type.contains("text/event-stream") { sse_payload(raw, id) } else { raw.to_string() };
    let r: Value = serde_json::from_str(&body).map_err(|_| Error::Protocol { code: 0, message: format!("the server sent something that is not JSON-RPC: {}", snippet(&body)) })?;
    if let Some(e) = r.get("error").filter(|e| e.is_object()) {
        return Err(Error::Protocol { code: e.get("code").and_then(Value::as_i64).unwrap_or(0), message: str_of(e, "message").into() });
    }
    Ok(r.get("result").cloned())
}

fn to_error(tool: &str, text: &str, data: &Value) -> Error {
    let err = data.get("error").cloned().unwrap_or(Value::Null);
    let code = str_of(&err, "code");
    if code == "rate_limited" || text.starts_with("RATE LIMITED") {
        let secs = err.get("retry_after_seconds").and_then(Value::as_f64).or_else(|| {
            text.find("Wait ").and_then(|i| text[i + 5..].split_whitespace().next()).and_then(|n| n.parse::<f64>().ok())
        }).unwrap_or(1.0);
        return Error::RateLimited { message: text.trim().into(), retry_after: Duration::from_secs_f64(secs) };
    }
    if code == "internal" || text.starts_with("INTERNAL ERROR") {
        return Error::Server(text.trim().into());
    }
    let message = match str_of(&err, "message") { "" => text.trim(), m => m }.to_string();
    let hint = match str_of(&err, "hint") { "" => str_of(&err, "what_to_do"), h => h }.to_string();
    let legal = err.get("legal_moves").and_then(Value::as_array).map(|a| a.iter().filter_map(|m| m.as_str().map(String::from)).collect()).unwrap_or_default();
    let mut ctx = Map::new();
    if let Some(o) = err.as_object() {
        for (k, v) in o {
            if !matches!(k.as_str(), "code" | "message" | "hint" | "what_to_do" | "legal_moves") {
                ctx.insert(k.clone(), v.clone());
            }
        }
    }
    let position = data.get("game").filter(|g| g.is_object()).map(|g| Position::from_value(g, text));
    Error::Tool(Box::new(ToolError { tool: tool.into(), code: code.into(), message, hint, legal_moves: legal, position, text: text.into(), context: Value::Object(ctx) }))
}
