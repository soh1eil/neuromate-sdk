package com.neuromate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Base class of every error the SDK throws. {@link #retryable()} says whether repeating the call may help. */
public class NeuromateException extends RuntimeException {
    public NeuromateException(String message) {
        super(message);
    }

    public NeuromateException(String message, Throwable cause) {
        super(message, cause);
    }

    public boolean retryable() {
        return false;
    }

    /** The request never got an answer (connection refused, timeout, DNS). */
    public static class Transport extends NeuromateException {
        public Transport(String m, Throwable c) {
            super(m, c);
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /** An unexpected HTTP status. */
    public static class Http extends NeuromateException {
        public final int status;

        public Http(int status, String body) {
            super("HTTP " + status + ": " + body);
            this.status = status;
        }
    }

    /** HTTP 401: the API key is missing, wrong or revoked. */
    public static class Auth extends NeuromateException {
        public final String code;
        public final String hint;

        public Auth(String message, String code, String hint) {
            super(message);
            this.code = code;
            this.hint = hint;
        }
    }

    /** HTTP 5xx or a platform INTERNAL ERROR. */
    public static class Server extends NeuromateException {
        public Server(String m) {
            super(m);
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /** Too many requests; wait {@link #retryAfterMs} before retrying. */
    public static class RateLimited extends NeuromateException {
        public final long retryAfterMs;

        public RateLimited(String m, long retryAfterMs) {
            super(m);
            this.retryAfterMs = retryAfterMs;
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /** A JSON-RPC level error (unknown tool, invalid params, unreadable reply). */
    public static class Protocol extends NeuromateException {
        public final int code;
        public final Object data;

        public Protocol(int code, String m, Object data) {
            super(m);
            this.code = code;
            this.data = data;
        }
    }

    /** A tool ran and said no. getMessage() is the server's complete teaching text; {@link #code} is the stable machine code. */
    public static class Tool extends NeuromateException {
        public final String tool;
        public final String code;
        public final String detail;
        public final String hint;
        public final List<String> legalMoves;
        public final Position position;
        public final Map<String, Object> context;

        public Tool(String tool, String code, String detail, String hint, List<String> legalMoves, Position position,
                    String text, Map<String, Object> context) {
            super(text == null || text.isEmpty() ? detail : text);
            this.tool = tool;
            this.code = code;
            this.detail = detail;
            this.hint = hint;
            this.legalMoves = legalMoves;
            this.position = position;
            this.context = context;
        }
    }

    public static class IllegalMove extends Tool {
        IllegalMove(Tool t) {
            super(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.getMessage(), t.context);
        }
    }

    public static class NotYourTurn extends Tool {
        NotYourTurn(Tool t) {
            super(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.getMessage(), t.context);
        }
    }

    /** expected_ply no longer matches: re-read the game. */
    public static class StalePly extends Tool {
        StalePly(Tool t) {
            super(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.getMessage(), t.context);
        }
    }

    public static class GameOver extends Tool {
        GameOver(Tool t) {
            super(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.getMessage(), t.context);
        }
    }

    /** report_model has not been called in this session (the client does it for you when you pass a model). */
    public static class ModelNotReported extends Tool {
        ModelNotReported(Tool t) {
            super(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.getMessage(), t.context);
        }
    }

    public static class Conflict extends Tool {
        Conflict(Tool t) {
            super(t.tool, t.code, t.detail, t.hint, t.legalMoves, t.position, t.getMessage(), t.context);
        }
    }

    static Tool tool(String name, String text, Map<String, Object> structured) {
        Map<String, Object> err = Json.object(structured.get("error"));
        String code = Json.str(err.get("code"));
        String msg = Json.str(err.get("message"));
        if (msg.isEmpty()) msg = text == null ? "" : text.trim();
        String hint = Json.str(err.get("hint"));
        if (hint.isEmpty()) hint = Json.str(err.get("what_to_do"));
        List<String> legal = new ArrayList<>();
        for (Object m : Json.array(err.get("legal_moves"))) if (m instanceof String) legal.add((String) m);
        Map<String, Object> ctx = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : err.entrySet()) {
            switch (e.getKey()) {
                case "code": case "message": case "hint": case "what_to_do": case "legal_moves": break;
                default: ctx.put(e.getKey(), e.getValue());
            }
        }
        Position p = structured.get("game") instanceof Map ? Position.of(Json.object(structured.get("game")), text) : null;
        Tool t = new Tool(name, code, msg, hint, legal, p, text, ctx);
        switch (code) {
            case "illegal_move": return new IllegalMove(t);
            case "not_your_turn": return new NotYourTurn(t);
            case "stale_ply": return new StalePly(t);
            case "game_over": return new GameOver(t);
            case "model_not_reported": return new ModelNotReported(t);
            case "conflict": return new Conflict(t);
            default: return t;
        }
    }
}
