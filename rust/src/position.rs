use serde_json::Value;

#[derive(Debug, Clone, PartialEq)]
pub struct LegalMove {
    pub uci: String,
    pub san: String,
}

fn s(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).unwrap_or("").to_string()
}
fn i(v: &Value, k: &str) -> i64 {
    v.get(k).and_then(Value::as_f64).map(|f| f as i64).unwrap_or(0)
}
fn b(v: &Value, k: &str) -> bool {
    v.get(k).and_then(Value::as_bool).unwrap_or(false)
}

/// The state of one game: FEN, ASCII board, legal moves (only on your turn) and the clocks (milliseconds, 0 = unknown).
#[derive(Debug, Clone)]
pub struct Position {
    pub raw: Value,
    /// The server's complete human-readable text of this reply.
    pub text: String,
    pub game_id: String,
    pub status: String,
    pub variant: String,
    pub time_control: String,
    pub pool: String,
    pub rated: bool,
    pub turn: String,
    pub your_turn: bool,
    pub ply: i64,
    pub fen: String,
    pub board: String,
    pub color: String,
    pub opponent_name: String,
    pub legal_moves: Vec<LegalMove>,
    pub moves_san: Vec<String>,
    /// "", "from_opponent" or "from_you".
    pub draw_offer: String,
    pub takeback_request: String,
    pub you_ms: i64,
    pub opponent_ms: i64,
    pub running: String,
    pub turn_time_left_ms: i64,
    pub move_cap_ms: i64,
    pub first_move_time_left_ms: i64,
    pub result_score: String,
    pub result_winner: String,
    pub result_termination: String,
    pub tournament_id: String,
    pub next: String,
    /// "casual" or "fide_strict" (illegal moves are penalised, no takebacks, no hints).
    pub ruleset: String,
    pub rules_text: String,
    /// false in strict games: never draw legal-move dots.
    pub hints_allowed: bool,
    /// Language (en fa ar es fr zh ru de pl ja ko) of the human behind your account / of your opponent ("" for agents and bots).
    pub user_locale: String,
    pub opponent_locale: String,
    /// Rejected moves of this turn ("move: reason"), filled by `play`.
    pub illegal_attempts: Vec<String>,
}

impl Position {
    /// ASCII diagram computed from the FEN: rank 8 on top, UPPER CASE = White. `from_your_side` turns it for Black.
    pub fn board_diagram(&self, from_your_side: bool) -> String {
        crate::board::diagram(&self.fen, from_your_side && self.color == "black")
    }
    /// Eight strings, rank 8 first, '.' for empty (the server's `board_rows`).
    pub fn board_rows(&self) -> Vec<String> { crate::board::rows(&self.fen) }
    pub fn piece_at(&self, square: &str) -> Option<crate::board::Piece> { crate::board::piece_at(&self.fen, square) }
    pub fn pieces(&self, color: Option<&str>) -> Vec<crate::board::Piece> { crate::board::pieces(&self.fen, color) }
    /// "King g1, Queen d1, Rooks a1 f1, ..."
    pub fn pieces_text(&self, color: &str) -> String { crate::board::pieces_text(&self.fen, color) }
    /// Pieces of `color` that attack (or, for its own piece, defend) `square`. Geometry only.
    pub fn attackers(&self, square: &str, color: &str) -> Vec<crate::board::Piece> { crate::board::attackers(&self.fen, square, color) }
    /// Board diagram plus both piece lists: a prompt-ready block for an LLM.
    pub fn describe(&self) -> String {
        let mut s = format!("BOARD (rank 8 on top; UPPER CASE = White, lowercase = Black, . = empty):\n{}\n", self.board_diagram(false));
        if self.color == "black" {
            s += &format!("BOARD FROM YOUR SIDE (you are Black):\n{}\n", self.board_diagram(true));
        }
        s + &format!("PIECES White: {}\nPIECES Black: {}\nTO MOVE: {}", self.pieces_text("white"), self.pieces_text("black"), self.turn)
    }

    pub fn from_value(g: &Value, text: &str) -> Position {
        let clocks = g.get("clocks").cloned().unwrap_or(Value::Null);
        let result = g.get("result").cloned().unwrap_or(Value::Null);
        let arr = |k: &str| g.get(k).and_then(Value::as_array).cloned().unwrap_or_default();
        Position {
            raw: g.clone(),
            text: text.to_string(),
            game_id: s(g, "game_id"),
            status: s(g, "status"),
            variant: s(g, "variant"),
            time_control: s(g, "time_control"),
            pool: s(g, "pool"),
            rated: b(g, "rated"),
            turn: s(g, "turn"),
            your_turn: b(g, "your_turn"),
            ply: i(g, "ply"),
            fen: s(g, "fen"),
            board: s(g, "board"),
            color: s(&g.get("you").cloned().unwrap_or(Value::Null), "color"),
            opponent_name: s(&g.get("opponent").cloned().unwrap_or(Value::Null), "name"),
            legal_moves: arr("legal_moves").iter().map(|m| LegalMove { uci: s(m, "uci"), san: s(m, "san") }).collect(),
            moves_san: arr("moves_san").iter().filter_map(|m| m.as_str().map(String::from)).collect(),
            draw_offer: s(g, "draw_offer"),
            takeback_request: s(g, "takeback_request"),
            you_ms: i(&clocks, "you_ms"),
            opponent_ms: i(&clocks, "opponent_ms"),
            running: s(&clocks, "running"),
            turn_time_left_ms: i(&clocks, "your_turn_time_left_ms"),
            move_cap_ms: i(&clocks, "move_cap_ms"),
            first_move_time_left_ms: i(&clocks, "first_move_time_left_ms"),
            result_score: s(&result, "score"),
            result_winner: s(&result, "winner"),
            result_termination: s(&result, "termination"),
            tournament_id: s(&g.get("tournament").cloned().unwrap_or(Value::Null), "id"),
            next: s(g, "next"),
            ruleset: { let r = s(g, "ruleset"); if r.is_empty() { "casual".into() } else { r } },
            rules_text: s(g, "rules_text"),
            hints_allowed: g.get("hints_allowed").and_then(Value::as_bool).unwrap_or(true),
            user_locale: s(g, "user_locale"),
            opponent_locale: s(g, "opponent_locale"),
            illegal_attempts: vec![],
        }
    }

    /// Whether FIDE strict rules apply.
    pub fn strict(&self) -> bool {
        self.ruleset == "fide_strict"
    }

    pub fn is_over(&self) -> bool {
        self.status == "finished" || self.status == "aborted"
    }

    pub fn legal_uci(&self) -> Vec<&str> {
        self.legal_moves.iter().map(|m| m.uci.as_str()).collect()
    }

    /// Seconds you may think about this move (the smallest relevant clock), or `None` when no clock applies.
    pub fn seconds_for_this_move(&self) -> Option<f64> {
        let mut left = vec![];
        if self.running == "you" && self.turn_time_left_ms > 0 {
            left.push(self.turn_time_left_ms);
        } else {
            if self.turn_time_left_ms > 0 { left.push(self.turn_time_left_ms); }
            if self.first_move_time_left_ms > 0 { left.push(self.first_move_time_left_ms); }
        }
        if left.is_empty() { return None; }
        if self.move_cap_ms > 0 { left.push(self.move_cap_ms); }
        left.into_iter().min().map(|m| m as f64 / 1000.0)
    }
}
