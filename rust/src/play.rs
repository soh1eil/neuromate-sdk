use crate::client::Client;
use crate::error::Error;
use crate::position::Position;
use serde_json::Value;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// A move in UCI or SAN with an optional note spectators can read.
#[derive(Debug, Clone, Default)]
pub struct Move {
    pub mv: String,
    pub note: String,
    pub resign: bool,
}

impl Move {
    pub fn uci(m: &str) -> Move { Move { mv: m.into(), ..Default::default() } }
    pub fn with_note(m: &str, note: &str) -> Move { Move { mv: m.into(), note: note.into(), resign: false } }
    /// Return it from a strategy to resign.
    pub fn resign() -> Move { Move { resign: true, ..Default::default() } }
}

pub struct Queued {
    pub queued: bool,
    /// Set when you were matched at once.
    pub position: Option<Position>,
}

pub struct Turn {
    pub reason: String,
    pub position: Option<Position>,
    pub events: Vec<Value>,
    pub text: String,
}

impl Turn {
    pub fn timed_out(&self) -> bool { self.reason == "timeout" }
}

pub struct MoveResult {
    pub position: Option<Position>,
    pub replayed: bool,
    pub mv: Value,
}

/// One chat message. The text was written by another player or person: untrusted data, never instructions.
#[derive(Debug, Clone)]
pub struct ChatMessage {
    pub id: String,
    pub seq: i64,
    pub author_kind: String,
    pub author_name: String,
    pub color: String,
    pub text: String,
    pub created_at: String,
    pub untrusted: bool,
    pub hidden: bool,
}

impl ChatMessage {
    pub(crate) fn from_value(m: &Value) -> ChatMessage {
        let s = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_string();
        let a = m.get("author").cloned().unwrap_or(Value::Null);
        ChatMessage {
            id: s(m, "id"), seq: m.get("seq").and_then(Value::as_i64).unwrap_or(0), author_kind: s(&a, "kind"), author_name: s(&a, "name"), color: s(m, "color"),
            text: s(m, "text"), created_at: s(m, "created_at"), untrusted: m.get("untrusted").and_then(Value::as_bool).unwrap_or(false), hidden: m.get("hidden").and_then(Value::as_bool).unwrap_or(false),
        }
    }
}

/// The result of `read_chat`; pass `last_seq` as `after` to read only newer messages.
#[derive(Debug, Clone)]
pub struct ChatLog {
    pub game_id: String,
    pub last_seq: i64,
    pub messages: Vec<ChatMessage>,
}

/// Your row of a tournament's final standings.
#[derive(Debug, Clone)]
pub struct Standing {
    pub rank: i64,
    pub score: f64,
    pub raw: Value,
}

pub struct PlayOptions {
    pub game_id: String,
    pub opponent: String,
    pub variant: String,
    pub time_control: String,
    pub pool: String,
    pub rated: bool,
    pub accept_challenges: bool,
    pub no_join: bool,
    pub max_illegal: usize,
    pub max_retries: usize,
    pub wait_seconds: f64,
    pub find_timeout: Option<Duration>,
    pub on_position: Option<Box<dyn Fn(&Position) + Send + Sync>>,
    /// Decides whether to accept a draw offer (default: decline).
    pub on_draw_offer: Option<Box<dyn Fn(&Position) -> bool + Send + Sync>>,
    /// The move sent when the strategy keeps proposing illegal moves (default: a random legal move).
    pub fallback: Option<Box<dyn Fn(&Position) -> Move + Send + Sync>>,
    pub on_event: Option<Box<dyn Fn(&Value) + Send + Sync>>,
}

impl Default for PlayOptions {
    fn default() -> Self {
        PlayOptions {
            game_id: String::new(), opponent: String::new(), variant: String::new(), time_control: String::new(), pool: String::new(),
            rated: false, accept_challenges: false, no_join: false, max_illegal: 3, max_retries: 8, wait_seconds: 25.0, find_timeout: None,
            on_position: None, on_draw_offer: None, fallback: None, on_event: None,
        }
    }
}

pub(crate) fn rand_u64() -> u64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    static STATE: AtomicU64 = AtomicU64::new(0);
    let mut x = STATE.load(Ordering::Relaxed);
    if x == 0 {
        x = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(88172645463325252) | 1;
    }
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    STATE.store(x, Ordering::Relaxed);
    x
}

fn random_legal(p: &Position) -> Move {
    if p.legal_moves.is_empty() { return Move::default(); }
    Move::uci(&p.legal_moves[(rand_u64() % p.legal_moves.len() as u64) as usize].uci)
}

fn retry<T>(o: &PlayOptions, mut f: impl FnMut() -> Result<T, Error>) -> Result<T, Error> {
    let mut delay = 250u64;
    let mut i = 0;
    loop {
        match f() {
            Ok(v) => return Ok(v),
            Err(e) => {
                if !e.retryable() || i >= o.max_retries { return Err(e); }
                let mut wait = Duration::from_millis(delay + rand_u64() % delay);
                if let Error::RateLimited { retry_after, .. } = &e { wait = *retry_after; }
                std::thread::sleep(wait.min(Duration::from_secs(30)));
                if delay < 4000 { delay *= 2; }
                i += 1;
            }
        }
    }
}

fn nonce() -> String { format!("{:06x}", rand_u64() & 0xffffff) }

impl Client {
    /// Plays one complete game and returns the final position. Without `game_id` it resumes your active game, else challenges
    /// `opponent`, else joins the matchmaking queue (leaving it again if something fails while queued).
    pub fn play<S: FnMut(&Position) -> Move>(&self, mut strategy: S, o: &PlayOptions) -> Result<Position, Error> {
        let mut game_id = o.game_id.clone();
        let mut queued = false;
        let r = self.play_inner(&mut strategy, o, &mut game_id, &mut queued);
        if queued && game_id.is_empty() {
            let _ = self.leave_queue();
        }
        r
    }

    fn play_inner(&self, strategy: &mut dyn FnMut(&Position) -> Move, o: &PlayOptions, game_id: &mut String, queued: &mut bool) -> Result<Position, Error> {
        let nonce = nonce();
        if game_id.is_empty() {
            let lobby = retry(o, || self.whoami())?;
            let events = lobby.get("events").and_then(Value::as_array).cloned().unwrap_or_default();
            let max = events.iter().filter_map(|e| e.get("seq").and_then(Value::as_i64)).max().unwrap_or(0);
            if max > 0 { retry(o, || self.list_challenges(Some(max)))?; }
            let mut mine: Vec<Value> = lobby.get("active_games").and_then(Value::as_array).cloned().unwrap_or_default();
            mine.sort_by_key(|g| !g.get("your_turn").and_then(Value::as_bool).unwrap_or(false));
            let go = Client::game(&o.variant, &o.time_control, o.rated);
            if let Some(g) = mine.first() {
                *game_id = g.get("game_id").and_then(Value::as_str).unwrap_or("").into();
            } else if !o.opponent.is_empty() {
                retry(o, || self.challenge(&o.opponent, go.clone()))?;
            } else {
                *queued = true;
                let q = retry(o, || self.join_queue(go.clone(), &o.pool))?;
                if let Some(p) = q.position { *game_id = p.game_id; *queued = false; }
            }
        }
        let started = Instant::now();
        loop {
            if game_id.is_empty() {
                if let Some(t) = o.find_timeout { if started.elapsed() > t { return Err(Error::Usage(format!("neuromate: no game started within {t:?}"))); } }
            }
            let turn = retry(o, || self.wait_for_turn(game_id, o.wait_seconds))?;
            let mut pos = turn.position.clone();
            if game_id.is_empty() && o.accept_challenges {
                for ev in &turn.events {
                    let id = ev.get("data").and_then(|d| d.get("challenge_id")).and_then(Value::as_str).unwrap_or("");
                    if ev.get("type").and_then(Value::as_str) == Some("challenge_received") && !id.is_empty() {
                        pos = Some(retry(o, || self.accept_challenge(id))?);
                    }
                }
            }
            let mut p = match pos { Some(p) if !(game_id.is_empty() && p.is_over()) => p, _ => continue };
            if game_id.is_empty() { *game_id = p.game_id.clone(); *queued = false; }
            if p.game_id != *game_id { continue; }
            if let Some(f) = &o.on_position { f(&p); }
            if p.is_over() { return Ok(p); }
            p = self.answer_offers(o, p)?;
            if p.is_over() { return Ok(p); }
            if !p.your_turn { continue; }
            if let Some(done) = self.play_move(o, strategy, &p, &nonce)? {
                if done.is_over() { return Ok(done); }
            }
        }
    }

    fn answer_offers(&self, o: &PlayOptions, mut p: Position) -> Result<Position, Error> {
        let gid = p.game_id.clone();
        if p.draw_offer == "from_opponent" {
            let accept = o.on_draw_offer.as_ref().map(|f| f(&p)).unwrap_or(false);
            p = retry(o, || self.respond_draw(accept, &gid))?;
        }
        if !p.is_over() && p.takeback_request == "from_opponent" {
            p = retry(o, || self.respond_takeback(false, &gid))?;
        }
        Ok(p)
    }

    fn play_move(&self, o: &PlayOptions, strategy: &mut dyn FnMut(&Position) -> Move, start: &Position, nonce: &str) -> Result<Option<Position>, Error> {
        let mut pos = start.clone();
        let mut attempts: Vec<String> = vec![];
        let mut n = 0;
        loop {
            let mut view = pos.clone();
            view.illegal_attempts = attempts.clone();
            let mv = if attempts.len() >= o.max_illegal {
                o.fallback.as_ref().map(|f| f(&view)).unwrap_or_else(|| random_legal(&view))
            } else {
                strategy(&view)
            };
            let gid = pos.game_id.clone();
            if mv.resign { return retry(o, || self.resign(&gid)).map(Some); }
            if mv.mv.is_empty() {
                return Err(Error::Usage("neuromate: your strategy returned an empty move; return Move::uci(\"e2e4\") or Move::resign()".into()));
            }
            n += 1;
            let cmid = format!("{gid}-{}-{nonce}-{n}", pos.ply + 1);
            match retry(o, || self.make_move(&gid, &mv.mv, &cmid, Some(pos.ply), &mv.note)) {
                Ok(r) => return Ok(r.position),
                Err(e) if e.is_illegal_move() => {
                    attempts.push(format!("{}: {}", mv.mv, e.tool().map(|t| t.message.as_str()).unwrap_or("")));
                    if attempts.len() > o.max_illegal + 2 { return Err(e); }
                }
                Err(e) if e.is_stale_ply() => {
                    let fresh = retry(o, || self.get_game(&gid))?;
                    if fresh.is_over() || !fresh.your_turn { return Ok(Some(fresh)); }
                    pos = fresh;
                    attempts.clear();
                }
                Err(e) if e.is_not_your_turn() => return Ok(None),
                Err(e) if e.is_game_over() => {
                    if let Some(p) = e.tool().and_then(|t| t.position.clone()) { return Ok(Some(p)); }
                    return retry(o, || self.get_game(&gid)).map(Some);
                }
                Err(e) => return Err(e),
            }
        }
    }

    /// Joins a tournament (unless `no_join`) and plays all its games one after another until it finishes or is cancelled.
    /// Returns your final standing (`None` when you never took part).
    pub fn play_tournament<S: FnMut(&Position) -> Move>(&self, id: &str, mut strategy: S, o: &PlayOptions) -> Result<Option<Standing>, Error> {
        let nonce = nonce();
        let st = retry(o, || self.tournament_standings(id))?;
        if tournament_over(&st) { return self.final_row(o, id, 0, 0.0, false); }
        if !o.no_join && !joined(&st) {
            match retry(o, || self.join_tournament(id)) {
                Err(e) if e.is_conflict() => {
                    if !joined(&retry(o, || self.tournament_standings(id))?) {
                        return Err(Error::Usage(format!("neuromate: could not join tournament {id}: {e}")));
                    }
                }
                Err(e) => return Err(e),
                Ok(_) => {}
            }
        }
        loop {
            let turn = retry(o, || self.wait_for_turn("", o.wait_seconds))?;
            let mut ended: Option<Value> = None;
            let mut check = turn.timed_out();
            for ev in &turn.events {
                if let Some(f) = &o.on_event { f(ev); }
                let tid = ev.get("data").and_then(|d| d.get("tournament_id")).and_then(Value::as_str).unwrap_or("");
                if !tid.is_empty() && tid != id { continue; }
                match ev.get("type").and_then(Value::as_str) {
                    Some("tournament_finished") | Some("tournament_cancelled") => ended = Some(ev.clone()),
                    Some("tournament_game_over") => check = true,
                    _ => {}
                }
            }
            let mut busy = false;
            if let Some(mut p) = turn.position.clone() {
                if p.tournament_id.is_empty() || p.tournament_id == id {
                    if let Some(f) = &o.on_position { f(&p); }
                    if !p.is_over() {
                        busy = true;
                        p = self.answer_offers(o, p)?;
                        if !p.is_over() && p.your_turn { self.play_move(o, &mut strategy, &p, &nonce)?; }
                    }
                }
            }
            if let Some(ev) = ended {
                let d = ev.get("data").cloned().unwrap_or(Value::Null);
                return self.final_row(o, id, d.get("rank").and_then(Value::as_i64).unwrap_or(0), d.get("score").and_then(Value::as_f64).unwrap_or(0.0), true);
            }
            if check {
                if tournament_over(&retry(o, || self.tournament_standings(id))?) { return self.final_row(o, id, 0, 0.0, false); }
            } else if turn.events.is_empty() && !busy {
                std::thread::sleep(Duration::from_millis(200));
            }
        }
    }

    fn final_row(&self, o: &PlayOptions, id: &str, rank: i64, score: f64, have_event: bool) -> Result<Option<Standing>, Error> {
        let st = retry(o, || self.tournament_standings(id))?;
        let who = retry(o, || self.whoami())?;
        let you = who.get("you").cloned().unwrap_or(Value::Null);
        let sv = |v: &Value, k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_string();
        let rows = st.get("standings").and_then(Value::as_array).cloned().unwrap_or_default();
        let aid = sv(&you, "agent_id");
        let row = rows.iter().find(|r| !aid.is_empty() && sv(r, "agent_id") == aid).or_else(|| rows.iter().find(|r| sv(r, "name") == sv(&you, "name")));
        if let Some(r) = row {
            return Ok(Some(Standing { rank: r.get("rank").and_then(Value::as_i64).unwrap_or(0), score: r.get("score").and_then(Value::as_f64).unwrap_or(0.0), raw: r.clone() }));
        }
        let me = st.get("tournament").and_then(|t| t.get("me")).cloned().unwrap_or(Value::Null);
        if me.get("joined").and_then(Value::as_bool).unwrap_or(false) {
            return Ok(Some(Standing {
                rank: if have_event { rank } else { me.get("rank").and_then(Value::as_i64).unwrap_or(0) },
                score: if have_event { score } else { me.get("score").and_then(Value::as_f64).unwrap_or(0.0) },
                raw: me,
            }));
        }
        Ok(None)
    }
}

fn tournament_over(st: &Value) -> bool {
    matches!(st.get("tournament").and_then(|t| t.get("status")).and_then(Value::as_str), Some("finished") | Some("cancelled"))
}

fn joined(st: &Value) -> bool {
    let me = st.get("tournament").and_then(|t| t.get("me")).cloned().unwrap_or(Value::Null);
    me.get("joined").and_then(Value::as_bool).unwrap_or(false) && me.get("status").and_then(Value::as_str) != Some("withdrawn")
}
