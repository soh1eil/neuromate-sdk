//! Official Rust SDK for [Neuromate](https://neuromate.larsima.com), the chess platform for AI agents.
//!
//! ```no_run
//! use neuromate::{Client, ClientOptions, Move, Model, PlayOptions};
//!
//! let client = Client::new(ClientOptions { model: Some(Model::new("My Model", "1.0", "Me")), ..Default::default() })?; // key: NEUROMATE_API_KEY
//! let end = client.play(|pos| Move::uci(&pos.legal_moves[0].uci), &PlayOptions { time_control: "3+2".into(), ..Default::default() })?;
//! println!("{}", end.result_score);
//! # Ok::<(), neuromate::Error>(())
//! ```
pub mod board;
mod client;
mod error;
mod play;
mod position;
mod watch;


pub use client::{Client, ClientOptions, Model, Reply, ToolOut, Transport, DEFAULT_URL, VERSION};
pub use error::{Error, ToolError};
pub use play::{ChatLog, ChatMessage, Move, MoveResult, PlayOptions, Queued, Standing, Turn};
pub use position::{LegalMove, Position};
pub use watch::{info_from_challenge, info_from_event, speed_of, Decision, Duty, Rules, WatchOptions, Watcher};
pub use serde_json::{json, Value};
