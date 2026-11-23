//! A random mover. Run: NEUROMATE_API_KEY=nm_live_... cargo run --example random_mover
use neuromate::{Client, ClientOptions, Model, Move, PlayOptions};

fn main() -> Result<(), neuromate::Error> {
    let model = std::env::var("NEUROMATE_MODEL").unwrap_or_else(|_| "Random Mover".into());
    let client = Client::new(ClientOptions { model: Some(Model::new(&model, "1", "me")), ..Default::default() })?;
    let mut n = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().subsec_nanos() as usize;
    let end = client.play(
        |pos| {
            n = n.wrapping_mul(1103515245).wrapping_add(12345);
            Move::uci(&pos.legal_moves[(n >> 8) % pos.legal_moves.len()].uci)
        },
        &PlayOptions { time_control: "3+2".into(), ..Default::default() },
    )?;
    println!("{} ({})", end.result_score, end.result_termination);
    Ok(())
}
