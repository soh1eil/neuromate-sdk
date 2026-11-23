use crate::position::Position;
use serde_json::Value;
use std::time::Duration;

/// A tool ran and said no. `text` is the server's complete teaching text; `code` is the stable machine code
/// (`illegal_move`, `not_your_turn`, `stale_ply`, `game_over`, `model_not_reported`, `invalid_params`, ...).
#[derive(Debug, Clone)]
pub struct ToolError {
    pub tool: String,
    pub code: String,
    pub message: String,
    pub hint: String,
    pub legal_moves: Vec<String>,
    pub position: Option<Position>,
    pub text: String,
    /// Every other field of the server's error object.
    pub context: Value,
}

/// Every error the SDK returns. [`Error::retryable`] says whether repeating the call may help.
#[derive(Debug, Clone)]
pub enum Error {
    /// The request never got an answer (connection refused, timeout, DNS).
    Transport(String),
    /// An unexpected HTTP status.
    Http { status: u16, body: String },
    /// HTTP 401: the API key is missing, wrong or revoked.
    Auth { message: String, code: String, hint: String },
    /// HTTP 5xx or a platform INTERNAL ERROR.
    Server(String),
    /// Too many requests; wait `retry_after` before retrying.
    RateLimited { message: String, retry_after: Duration },
    /// A JSON-RPC level error (unknown tool, invalid params, unreadable reply).
    Protocol { code: i64, message: String },
    /// A tool ran and said no.
    Tool(Box<ToolError>),
    /// Misuse of the SDK (no API key, empty move, ...).
    Usage(String),
}

impl Error {
    pub fn retryable(&self) -> bool {
        matches!(self, Error::Transport(_) | Error::Server(_) | Error::RateLimited { .. })
    }
    /// The stable server code of a tool error (`illegal_move`, `stale_ply`, ...), if this is one.
    pub fn code(&self) -> Option<&str> {
        match self {
            Error::Tool(t) => Some(&t.code),
            Error::Auth { code, .. } if !code.is_empty() => Some(code),
            _ => None,
        }
    }
    pub fn tool(&self) -> Option<&ToolError> {
        if let Error::Tool(t) = self { Some(t) } else { None }
    }
    pub fn is_illegal_move(&self) -> bool { self.code() == Some("illegal_move") }
    pub fn is_not_your_turn(&self) -> bool { self.code() == Some("not_your_turn") }
    pub fn is_stale_ply(&self) -> bool { self.code() == Some("stale_ply") }
    pub fn is_game_over(&self) -> bool { self.code() == Some("game_over") }
    pub fn is_model_not_reported(&self) -> bool { self.code() == Some("model_not_reported") }
    pub fn is_conflict(&self) -> bool { self.code() == Some("conflict") }
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Transport(m) | Error::Server(m) | Error::Usage(m) => write!(f, "{m}"),
            Error::Http { status, body } => write!(f, "HTTP {status}: {body}"),
            Error::Auth { message, .. } => write!(f, "{message}"),
            Error::RateLimited { message, .. } => write!(f, "{message}"),
            Error::Protocol { message, .. } => write!(f, "{message}"),
            Error::Tool(t) => write!(f, "{}", if t.text.is_empty() { &t.message } else { &t.text }),
        }
    }
}

impl std::error::Error for Error {}
