//! Board helpers computed locally from a FEN: where the pieces are, who attacks what.
//! Pure geometry, no evaluation (pins, x-rays and whose turn it is are not judged).
use std::collections::HashMap;

const FILES: &str = "abcdefgh";
const ORDER: &str = "kqrbnp";

/// One piece: `color` "white"/"black", `kind` "king".."pawn", `square` "e4", `symbol` the FEN letter.
#[derive(Debug, Clone, PartialEq)]
pub struct Piece {
    pub color: &'static str,
    pub kind: &'static str,
    pub square: String,
    pub symbol: char,
}

fn kind(c: char) -> &'static str {
    match c.to_ascii_lowercase() {
        'k' => "king",
        'q' => "queen",
        'r' => "rook",
        'b' => "bishop",
        'n' => "knight",
        _ => "pawn",
    }
}

fn mk(c: char, square: String) -> Piece {
    Piece { color: if c.is_ascii_uppercase() { "white" } else { "black" }, kind: kind(c), square, symbol: c }
}

fn sq_name(f: usize, r: usize) -> String {
    format!("{}{}", &FILES[f..f + 1], r + 1)
}

/// square -> FEN letter (Crazyhouse pockets and `~` marks are ignored). None if the placement is malformed.
pub fn parse(fen: &str) -> Option<HashMap<String, char>> {
    let field = fen.split_whitespace().next()?;
    let field = field.split('[').next()?.replace('~', "");
    let ranks: Vec<&str> = field.split('/').collect();
    if ranks.len() != 8 {
        return None;
    }
    let mut out = HashMap::new();
    for (i, row) in ranks.iter().enumerate() {
        let mut f = 0usize;
        for c in row.chars() {
            if let Some(d) = c.to_digit(10) {
                f += d as usize;
            } else if f < 8 {
                out.insert(sq_name(f, 7 - i), c);
                f += 1;
            }
        }
    }
    Some(out)
}

/// Eight strings, rank 8 first, '.' for empty (the server's `board_rows`).
pub fn rows(fen: &str) -> Vec<String> {
    let b = parse(fen).unwrap_or_default();
    (0..8).rev().map(|r| (0..8).map(|f| *b.get(&sq_name(f, r)).unwrap_or(&'.')).collect()).collect()
}

/// ASCII diagram: ranks 8 to 1, files a-h, UPPER CASE = White, '.' = empty; `from_black` turns it around.
pub fn diagram(fen: &str, from_black: bool) -> String {
    let rs = rows(fen);
    let mut out = vec![];
    for i in 0..8 {
        let idx = if from_black { 7 - i } else { i };
        let row: Vec<char> = if from_black { rs[idx].chars().rev().collect() } else { rs[idx].chars().collect() };
        out.push(format!("{} {}", 8 - idx, row.iter().map(|c| c.to_string()).collect::<Vec<_>>().join(" ")));
    }
    out.push(format!("  {}", if from_black { "h g f e d c b a" } else { "a b c d e f g h" }));
    out.join("\n")
}

pub fn piece_at(fen: &str, square: &str) -> Option<Piece> {
    let sq = square.to_lowercase();
    parse(fen)?.get(&sq).map(|c| mk(*c, sq))
}

/// Pieces of one colour (None = both): king, queen, rooks, bishops, knights, pawns, then by file and rank.
pub fn pieces(fen: &str, color: Option<&str>) -> Vec<Piece> {
    let mut v: Vec<Piece> = parse(fen).unwrap_or_default().into_iter().map(|(s, c)| mk(c, s)).filter(|p| color.map_or(true, |c| p.color == c)).collect();
    v.sort_by_key(|p| (p.color != "white", ORDER.find(p.symbol.to_ascii_lowercase()), p.square.as_bytes()[0], p.square.as_bytes()[1]));
    v
}

/// "King g1, Queen d1, Rooks a1 f1, ..." exactly as the server prints it.
pub fn pieces_text(fen: &str, color: &str) -> String {
    let ps = pieces(fen, Some(color));
    let mut parts = vec![];
    for k in ORDER.chars() {
        let sqs: Vec<&str> = ps.iter().filter(|p| p.symbol.to_ascii_lowercase() == k).map(|p| p.square.as_str()).collect();
        if !sqs.is_empty() {
            let name = kind(k);
            parts.push(format!("{}{}{} {}", name[..1].to_uppercase(), &name[1..], if sqs.len() > 1 { "s" } else { "" }, sqs.join(" ")));
        }
    }
    if parts.is_empty() { "none".into() } else { parts.join(", ") }
}

/// Pieces of `color` that attack `square` (an own piece standing there is "defended"). Geometry only.
pub fn attackers(fen: &str, square: &str, color: &str) -> Vec<Piece> {
    let b = match parse(fen) {
        Some(b) => b,
        None => return vec![],
    };
    let sq = square.to_lowercase().into_bytes();
    if sq.len() != 2 {
        return vec![];
    }
    let (f0, r0) = ((sq[0] - b'a') as i32, (sq[1] - b'1') as i32);
    let white = color == "white";
    let mut found: Vec<Piece> = vec![];
    // Some(true) = square empty/off, handled by the caller: returns whether the ray is blocked.
    let probe = |f: i32, r: i32, kinds: &str, found: &mut Vec<Piece>| -> bool {
        if !(0..8).contains(&f) || !(0..8).contains(&r) {
            return true;
        }
        let name = sq_name(f as usize, r as usize);
        match b.get(&name) {
            None => false,
            Some(c) => {
                if c.is_ascii_uppercase() == white && kinds.contains(c.to_ascii_lowercase()) {
                    found.push(mk(*c, name));
                }
                true
            }
        }
    };
    let pr = if white { r0 - 1 } else { r0 + 1 };
    for df in [-1, 1] {
        probe(f0 + df, pr, "p", &mut found);
    }
    for (df, dr) in [(1, 2), (2, 1), (-1, 2), (-2, 1), (1, -2), (2, -1), (-1, -2), (-2, -1)] {
        probe(f0 + df, r0 + dr, "n", &mut found);
    }
    for df in -1..=1 {
        for dr in -1..=1 {
            if df != 0 || dr != 0 {
                probe(f0 + df, r0 + dr, "k", &mut found);
            }
        }
    }
    for (dirs, kinds) in [([(1, 0), (-1, 0), (0, 1), (0, -1)], "rq"), ([(1, 1), (1, -1), (-1, 1), (-1, -1)], "bq")] {
        for (df, dr) in dirs {
            let (mut f, mut r) = (f0 + df, r0 + dr);
            while (0..8).contains(&f) && (0..8).contains(&r) {
                if probe(f, r, kinds, &mut found) {
                    break;
                }
                f += df;
                r += dr;
            }
        }
    }
    found.sort_by(|a, b| a.square.cmp(&b.square));
    found
}
