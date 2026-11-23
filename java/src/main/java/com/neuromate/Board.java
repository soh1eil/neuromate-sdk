package com.neuromate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Board helpers computed locally from a FEN: where the pieces are, who attacks what.
 * Pure geometry, no evaluation (pins, x-rays and whose turn it is are not judged).
 * Models read a FEN badly; the server sends the same facts (describe_position, square_info).
 */
public final class Board {
    private Board() {}

    private static final String FILES = "abcdefgh";
    private static final String ORDER = "kqrbnp";

    /** One piece: color "white"/"black", kind "king".."pawn", square "e4", symbol the FEN letter. */
    public static final class Piece {
        public final String color, kind, square;
        public final char symbol;

        Piece(char symbol, String square) {
            this.symbol = symbol;
            this.square = square;
            this.color = Character.isUpperCase(symbol) ? "white" : "black";
            this.kind = kind(Character.toLowerCase(symbol));
        }

        @Override public String toString() { return kind + " " + square; }
    }

    private static String kind(char c) {
        switch (c) {
            case 'k': return "king";
            case 'q': return "queen";
            case 'r': return "rook";
            case 'b': return "bishop";
            case 'n': return "knight";
            default: return "pawn";
        }
    }

    /** square to FEN letter (Crazyhouse pockets and "~" marks are ignored). */
    public static Map<String, Character> parse(String fen) {
        String field = fen.trim().split(" ")[0];
        int br = field.indexOf('[');
        if (br >= 0) field = field.substring(0, br);
        field = field.replace("~", "");
        String[] ranks = field.split("/");
        if (ranks.length != 8) throw new IllegalArgumentException("not a FEN: " + fen);
        Map<String, Character> out = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            int f = 0;
            for (char c : ranks[i].toCharArray()) {
                if (Character.isDigit(c)) f += c - '0';
                else out.put("" + FILES.charAt(f++) + (8 - i), c);
            }
        }
        return out;
    }

    /** Eight strings, rank 8 first, '.' for empty: the server's board_rows. */
    public static List<String> rows(String fen) {
        Map<String, Character> b = parse(fen);
        List<String> rows = new ArrayList<>();
        for (int r = 8; r >= 1; r--) {
            StringBuilder sb = new StringBuilder();
            for (int f = 0; f < 8; f++) sb.append(b.getOrDefault("" + FILES.charAt(f) + r, '.'));
            rows.add(sb.toString());
        }
        return rows;
    }

    /** ASCII diagram: ranks 8 to 1, files a-h, UPPER CASE = White, '.' = empty; fromBlack turns it around. */
    public static String diagram(String fen, boolean fromBlack) {
        List<String> rows = rows(fen);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            int idx = fromBlack ? 7 - i : i;
            String row = rows.get(idx);
            if (fromBlack) row = new StringBuilder(row).reverse().toString();
            out.append(8 - idx);
            for (char c : row.toCharArray()) out.append(' ').append(c);
            out.append('\n');
        }
        String files = fromBlack ? "h g f e d c b a" : "a b c d e f g h";
        return out.append("  ").append(files).toString();
    }

    public static Piece pieceAt(String fen, String square) {
        Character c = parse(fen).get(square.toLowerCase());
        return c == null ? null : new Piece(c, square.toLowerCase());
    }

    /** Pieces of one color (null = both): king, queen, rooks, bishops, knights, pawns, then by file and rank. */
    public static List<Piece> pieces(String fen, String color) {
        List<Piece> out = new ArrayList<>();
        for (Map.Entry<String, Character> e : parse(fen).entrySet()) {
            Piece p = new Piece(e.getValue(), e.getKey());
            if (color == null || p.color.equals(color)) out.add(p);
        }
        out.sort((a, b) -> {
            if (!a.color.equals(b.color)) return a.color.equals("white") ? -1 : 1;
            int oa = ORDER.indexOf(Character.toLowerCase(a.symbol)), ob = ORDER.indexOf(Character.toLowerCase(b.symbol));
            if (oa != ob) return Integer.compare(oa, ob);
            if (a.square.charAt(0) != b.square.charAt(0)) return Character.compare(a.square.charAt(0), b.square.charAt(0));
            return Character.compare(a.square.charAt(1), b.square.charAt(1));
        });
        return out;
    }

    /** "King g1, Queen d1, Rooks a1 f1, ..." exactly as the server prints it. */
    public static String piecesText(String fen, String color) {
        List<Piece> ps = pieces(fen, color);
        List<String> parts = new ArrayList<>();
        for (char k : ORDER.toCharArray()) {
            StringBuilder sqs = new StringBuilder();
            int n = 0;
            for (Piece p : ps) if (Character.toLowerCase(p.symbol) == k) { sqs.append(' ').append(p.square); n++; }
            if (n > 0) {
                String name = kind(k);
                parts.add(Character.toUpperCase(name.charAt(0)) + name.substring(1) + (n > 1 ? "s" : "") + sqs);
            }
        }
        return parts.isEmpty() ? "none" : String.join(", ", parts);
    }

    /** Pieces of color that attack square (an own piece standing there is "defended"). Geometry only. */
    public static List<Piece> attackers(String fen, String square, String color) {
        Map<String, Character> b = parse(fen);
        String sq = square.toLowerCase();
        int f0 = FILES.indexOf(sq.charAt(0)), r0 = sq.charAt(1) - '1';
        boolean white = color.equals("white");
        List<Piece> found = new ArrayList<>();
        int pr = white ? r0 - 1 : r0 + 1;
        for (int df : new int[] {-1, 1}) add(found, b, f0 + df, pr, "p", white);
        int[][] knight = {{1, 2}, {2, 1}, {-1, 2}, {-2, 1}, {1, -2}, {2, -1}, {-1, -2}, {-2, -1}};
        for (int[] d : knight) add(found, b, f0 + d[0], r0 + d[1], "n", white);
        for (int df = -1; df <= 1; df++) for (int dr = -1; dr <= 1; dr++) if (df != 0 || dr != 0) add(found, b, f0 + df, r0 + dr, "k", white);
        int[][] straight = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}, diagonal = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        ray(found, b, f0, r0, straight, "rq", white);
        ray(found, b, f0, r0, diagonal, "bq", white);
        found.sort((x, y) -> x.square.compareTo(y.square));
        return found;
    }

    private static boolean add(List<Piece> out, Map<String, Character> b, int f, int r, String kinds, boolean white) {
        if (f < 0 || f > 7 || r < 0 || r > 7) return false;
        Character c = b.get("" + FILES.charAt(f) + (r + 1));
        if (c == null) return false;
        if (Character.isUpperCase(c) == white && kinds.indexOf(Character.toLowerCase(c)) >= 0) out.add(new Piece(c, "" + FILES.charAt(f) + (r + 1)));
        return true;
    }

    private static void ray(List<Piece> out, Map<String, Character> b, int f0, int r0, int[][] dirs, String kinds, boolean white) {
        for (int[] d : dirs) {
            int f = f0 + d[0], r = r0 + d[1];
            while (f >= 0 && f < 8 && r >= 0 && r < 8) {
                if (add(out, b, f, r, kinds, white) || b.containsKey("" + FILES.charAt(f) + (r + 1))) break;
                f += d[0];
                r += d[1];
            }
        }
    }

    /** Legal moves (UCI) grouped by origin square, as "Knight g1: f3 h3" lines (captures and checks need SAN: see Position.legalMovesByPiece). */
    public static Map<String, List<String>> legalByOrigin(String fen, List<String> uci) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        for (String u : uci) out.computeIfAbsent(u.contains("@") ? "@" : u.substring(0, 2), k -> new ArrayList<>()).add(u);
        return out;
    }
}
