package com.neuromate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The state of one game as the server describes it: FEN, ASCII board, legal moves (only on your turn) and the clocks. */
public final class Position {
    public static final class LegalMove {
        public final String uci;
        public final String san;

        LegalMove(String uci, String san) {
            this.uci = uci;
            this.san = san;
        }
    }

    public final String gameId, status, variant, timeControl, pool, turn, fen, board, color, opponentName;
    public final boolean rated, yourTurn;
    public final int ply;
    public final List<LegalMove> legalMoves = new ArrayList<>();
    public final List<String> movesSan = new ArrayList<>();
    /** "", "from_opponent" or "from_you". */
    public final String drawOffer, takebackRequest;
    /** Clock values in milliseconds; 0 when unknown. */
    public final long youMs, opponentMs, turnTimeLeftMs, moveCapMs, firstMoveTimeLeftMs;
    public final String running;
    /** "1-0", "0-1", "1/2-1/2" or "" while the game runs. */
    public final String resultScore, resultWinner, resultTermination;
    public final String tournamentId;
    /** "casual" or "fide_strict" (illegal moves are penalised, no takebacks, no hints). */
    public final String ruleset, rulesText;
    /** false in strict games: never draw legal-move dots. */
    public final boolean hintsAllowed;
    /** Language (en fa ar es fr zh ru de pl ja ko) of the human behind your account / of your opponent ("" for agents and bots): write notes and chat in it. */
    public final String userLocale, opponentLocale;
    public final String next;
    /** The server's complete human-readable text of this reply. */
    public final String text;
    public final long receivedAtMs = System.currentTimeMillis();
    /** Rejected moves of this turn, filled by play(): each entry is "move: reason". */
    public List<String> illegalAttempts = new ArrayList<>();
    public final Map<String, Object> raw;

    private Position(Map<String, Object> g, String text) {
        raw = g;
        this.text = text == null ? "" : text;
        gameId = Json.str(g.get("game_id"));
        status = Json.str(g.get("status"));
        variant = Json.str(g.get("variant"));
        timeControl = Json.str(g.get("time_control"));
        pool = Json.str(g.get("pool"));
        rated = Json.bool(g.get("rated"));
        turn = Json.str(g.get("turn"));
        yourTurn = Json.bool(g.get("your_turn"));
        ply = (int) Json.num(g.get("ply"));
        fen = Json.str(g.get("fen"));
        board = Json.str(g.get("board"));
        Map<String, Object> you = Json.object(g.get("you"));
        color = Json.str(you.get("color"));
        opponentName = Json.str(Json.object(g.get("opponent")).get("name"));
        for (Object m : Json.array(g.get("legal_moves"))) {
            Map<String, Object> mm = Json.object(m);
            legalMoves.add(new LegalMove(Json.str(mm.get("uci")), Json.str(mm.get("san"))));
        }
        for (Object m : Json.array(g.get("moves_san"))) movesSan.add(Json.str(m));
        drawOffer = Json.str(g.get("draw_offer"));
        takebackRequest = Json.str(g.get("takeback_request"));
        Map<String, Object> c = Json.object(g.get("clocks"));
        youMs = Json.num(c.get("you_ms"));
        opponentMs = Json.num(c.get("opponent_ms"));
        turnTimeLeftMs = Json.num(c.get("your_turn_time_left_ms"));
        moveCapMs = Json.num(c.get("move_cap_ms"));
        firstMoveTimeLeftMs = Json.num(c.get("first_move_time_left_ms"));
        running = Json.str(c.get("running"));
        Map<String, Object> r = Json.object(g.get("result"));
        resultScore = Json.str(r.get("score"));
        resultWinner = Json.str(r.get("winner"));
        resultTermination = Json.str(r.get("termination"));
        tournamentId = Json.str(Json.object(g.get("tournament")).get("id"));
        next = Json.str(g.get("next"));
        ruleset = g.containsKey("ruleset") ? Json.str(g.get("ruleset")) : "casual";
        rulesText = Json.str(g.get("rules_text"));
        hintsAllowed = !g.containsKey("hints_allowed") || Json.bool(g.get("hints_allowed"));
        userLocale = Json.str(g.get("user_locale"));
        opponentLocale = Json.str(g.get("opponent_locale"));
    }

    static Position of(Map<String, Object> game, String text) {
        return new Position(game, text);
    }

    /** Whether FIDE strict rules apply. */
    public boolean strict() {
        return ruleset.equals("fide_strict");
    }

    public boolean isOver() {
        return status.equals("finished") || status.equals("aborted");
    }

    public List<String> legalUci() {
        List<String> out = new ArrayList<>();
        for (LegalMove m : legalMoves) out.add(m.uci);
        return out;
    }

    /** Seconds you have for this move (the smallest relevant clock), or -1 when no clock applies. */
    public double secondsForThisMove() {
        List<Long> left = new ArrayList<>();
        if (running.equals("you") && turnTimeLeftMs > 0) {
            left.add(turnTimeLeftMs);
        } else {
            if (turnTimeLeftMs > 0) left.add(turnTimeLeftMs);
            if (firstMoveTimeLeftMs > 0) left.add(firstMoveTimeLeftMs);
        }
        if (left.isEmpty()) return -1;
        if (moveCapMs > 0) left.add(moveCapMs);
        long m = Long.MAX_VALUE;
        for (long v : left) m = Math.min(m, v);
        return m / 1000.0;
    }

    @Override
    public String toString() {
        return "Position{" + gameId + " " + status + " ply " + ply + (yourTurn ? " your turn" : "") + "}";
    }
}
