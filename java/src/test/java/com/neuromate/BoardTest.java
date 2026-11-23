package com.neuromate;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The board helpers must agree with the server's own chess engine (sdk/testdata/board_vectors.json). */
class BoardTest {
    @Test
    @SuppressWarnings("unchecked")
    void matchesServerVectors() throws Exception {
        List<Object> vectors = Json.array(Json.parse(Files.readString(Path.of("../testdata/board_vectors.json"))));
        assertFalse(vectors.isEmpty());
        for (Object o : vectors) {
            Map<String, Object> v = Json.object(o);
            String fen = Json.str(v.get("fen")), name = Json.str(v.get("name"));
            List<String> rows = new ArrayList<>();
            for (Object r : Json.array(v.get("board_rows"))) rows.add(Json.str(r));
            assertEquals(rows, Board.rows(fen), name);
            for (String c : new String[] {"white", "black"}) {
                List<String> want = new ArrayList<>(), got = new ArrayList<>();
                for (Object s : Json.array(Json.object(v.get("pieces")).get(c))) want.add(Json.str(s));
                for (Board.Piece p : Board.pieces(fen, c)) got.add(Character.toUpperCase(p.symbol) + ":" + p.square);
                assertEquals(want, got, name + " " + c);
            }
            Map<String, Object> att = Json.object(v.get("attackers"));
            for (char f = 'a'; f <= 'h'; f++) for (char r = '1'; r <= '8'; r++) {
                String sq = "" + f + r;
                for (String c : new String[] {"white", "black"}) {
                    List<String> want = new ArrayList<>(), got = new ArrayList<>();
                    if (att.containsKey(sq)) for (Object s : Json.array(Json.object(att.get(sq)).get(c))) want.add(Json.str(s));
                    Collections.sort(want);
                    for (Board.Piece p : Board.attackers(fen, sq, c)) got.add(p.square);
                    assertEquals(want, got, name + " " + c + " attackers of " + sq);
                }
            }
        }
    }

    @Test
    void textHelpers() {
        String start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";
        assertEquals("8 r n b q k b n r", Board.diagram(start, false).split("\n")[0]);
        assertEquals("1 R N B K Q B N R", Board.diagram(start, true).split("\n")[0]);
        assertEquals("King e1, Queen d1, Rooks a1 h1, Bishops c1 f1, Knights b1 g1, Pawns a2 b2 c2 d2 e2 f2 g2 h2", Board.piecesText(start, "white"));
        assertEquals("king", Board.pieceAt(start, "e1").kind);
        assertNull(Board.pieceAt(start, "e4"));
    }
}
