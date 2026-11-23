//! The board helpers must agree with the server's own chess engine (../testdata/board_vectors.json).
use neuromate::board::{attackers, diagram, piece_at, pieces, pieces_text, rows};
use serde_json::Value;

#[test]
fn matches_server_vectors() {
    let p = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("..").join("testdata").join("board_vectors.json");
    let vs: Vec<Value> = serde_json::from_str(&std::fs::read_to_string(p).unwrap()).unwrap();
    assert!(!vs.is_empty());
    for v in vs {
        let name = v["name"].as_str().unwrap();
        let fen = v["fen"].as_str().unwrap();
        let want_rows: Vec<String> = v["board_rows"].as_array().unwrap().iter().map(|x| x.as_str().unwrap().to_string()).collect();
        assert_eq!(rows(fen), want_rows, "{name}");
        for c in ["white", "black"] {
            let want: Vec<String> = v["pieces"][c].as_array().unwrap().iter().map(|x| x.as_str().unwrap().to_string()).collect();
            let got: Vec<String> = pieces(fen, Some(c)).iter().map(|p| format!("{}:{}", p.symbol.to_ascii_uppercase(), p.square)).collect();
            assert_eq!(got, want, "{name} {c}");
        }
        for f in "abcdefgh".chars() {
            for r in "12345678".chars() {
                let sq = format!("{f}{r}");
                for c in ["white", "black"] {
                    let mut want: Vec<String> = v["attackers"][&sq][c].as_array().map(|a| a.iter().map(|x| x.as_str().unwrap().to_string()).collect()).unwrap_or_default();
                    want.sort();
                    let got: Vec<String> = attackers(fen, &sq, c).iter().map(|p| p.square.clone()).collect();
                    assert_eq!(got, want, "{name} {c} attackers of {sq}");
                }
            }
        }
    }
}

#[test]
fn text_helpers() {
    let start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
    assert_eq!(diagram(start, false).lines().next().unwrap(), "8 r n b q k b n r");
    assert_eq!(diagram(start, true).lines().next().unwrap(), "1 R N B K Q B N R");
    assert_eq!(pieces_text(start, "white"), "King e1, Queen d1, Rooks a1 h1, Bishops c1 f1, Knights b1 g1, Pawns a2 b2 c2 d2 e2 f2 g2 h2");
    assert_eq!(piece_at(start, "e1").unwrap().kind, "king");
    assert!(piece_at(start, "e4").is_none());
}
