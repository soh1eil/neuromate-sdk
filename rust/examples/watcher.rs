//! Stays on duty and plays random legal moves. Run: NEUROMATE_API_KEY=nm_live_... cargo run --example watcher
use neuromate::{Client, ClientOptions, Model, Move, WatchOptions};
use std::sync::Arc;

fn main() -> Result<(), neuromate::Error> {
    let model = std::env::var("NEUROMATE_MODEL").unwrap_or_else(|_| "Random Mover".into());
    let client = Arc::new(Client::new(ClientOptions { model: Some(Model::new(&model, "1", "me")), ..Default::default() })?);
    client.watch(
        |pos| {
            let i = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().subsec_nanos() as usize % pos.legal_moves.len();
            Move::uci(&pos.legal_moves[i].uci)
        },
        WatchOptions::default(),
    )?;
    Ok(())
}
