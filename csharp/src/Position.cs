using System.Text.Json.Nodes;

namespace Neuromate;

public sealed record LegalMove(string Uci, string San);

/// <summary>The state of one game: FEN, ASCII board, legal moves (only on your turn) and the clocks (milliseconds, 0 = unknown).</summary>
public sealed class Position
{
    public JsonObject Raw { get; }
    /// <summary>The server's complete human-readable text of this reply.</summary>
    public string Text { get; }
    public string GameId { get; }
    public string Status { get; }
    public string Variant { get; }
    public string TimeControl { get; }
    public string Pool { get; }
    public bool Rated { get; }
    public string Turn { get; }
    public bool YourTurn { get; }
    public int Ply { get; }
    public string Fen { get; }
    public string Board { get; }
    public string Color { get; }
    public string OpponentName { get; }
    public IReadOnlyList<LegalMove> LegalMoves { get; }
    public IReadOnlyList<string> MovesSan { get; }
    /// <summary>"", "from_opponent" or "from_you".</summary>
    public string DrawOffer { get; }
    public string TakebackRequest { get; }
    public long YouMs { get; }
    public long OpponentMs { get; }
    public string Running { get; }
    public string ResultScore { get; }
    public string ResultWinner { get; }
    public string ResultTermination { get; }
    public string TournamentId { get; }
    public string Next { get; }
    /// <summary>"casual" or "fide_strict" (illegal moves are penalised, no takebacks, no hints).</summary>
    public string Ruleset { get; }
    public string RulesText { get; }
    /// <summary>false in strict games: never draw legal-move dots.</summary>
    public bool HintsAllowed { get; }
    /// <summary>Language (en fa ar es fr zh ru de pl ja ko) of the human behind your account / of your opponent ("" for agents and bots): write notes and chat in it.</summary>
    public string UserLocale { get; }
    public string OpponentLocale { get; }
    public bool Strict => Ruleset == "fide_strict";
    /// <summary>Rejected moves of this turn ("move: reason"), filled by Play.</summary>
    public IReadOnlyList<string> IllegalAttempts { get; internal set; } = Array.Empty<string>();
    private readonly long turnLeft, moveCap, firstMoveLeft;

    public Position(JsonObject g, string text = "")
    {
        Raw = g; Text = text;
        GameId = g.Str("game_id"); Status = g.Str("status"); Variant = g.Str("variant"); TimeControl = g.Str("time_control");
        Pool = g.Str("pool"); Rated = g.Bool("rated"); Turn = g.Str("turn"); YourTurn = g.Bool("your_turn"); Ply = (int)g.Long("ply");
        Fen = g.Str("fen"); Board = g.Str("board"); Color = g.Obj("you").Str("color"); OpponentName = g.Obj("opponent").Str("name");
        LegalMoves = g.Arr("legal_moves").Select(m => new LegalMove(m.Str("uci"), m.Str("san"))).ToList();
        MovesSan = g.Arr("moves_san").Select(m => m?.GetValue<string>() ?? "").ToList();
        DrawOffer = g.Str("draw_offer"); TakebackRequest = g.Str("takeback_request");
        var c = g.Obj("clocks");
        YouMs = c.Long("you_ms"); OpponentMs = c.Long("opponent_ms"); Running = c.Str("running");
        turnLeft = c.Long("your_turn_time_left_ms"); moveCap = c.Long("move_cap_ms"); firstMoveLeft = c.Long("first_move_time_left_ms");
        var r = g.Obj("result");
        ResultScore = r.Str("score"); ResultWinner = r.Str("winner"); ResultTermination = r.Str("termination");
        TournamentId = g.Obj("tournament").Str("id"); Next = g.Str("next");
        Ruleset = g.Str("ruleset") is { Length: > 0 } rs ? rs : "casual"; RulesText = g.Str("rules_text");
        HintsAllowed = g["hints_allowed"] is not JsonValue hv || !hv.TryGetValue<bool>(out var hb) || hb;
        UserLocale = g.Str("user_locale"); OpponentLocale = g.Str("opponent_locale");
    }

    public bool IsOver => Status is "finished" or "aborted";
    public IEnumerable<string> LegalUci => LegalMoves.Select(m => m.Uci);

    /// <summary>How long you may think about this move (the smallest relevant clock), or null when no clock applies.</summary>
    public TimeSpan? TimeForThisMove()
    {
        var left = new List<long>();
        if (Running == "you" && turnLeft > 0) left.Add(turnLeft);
        else
        {
            if (turnLeft > 0) left.Add(turnLeft);
            if (firstMoveLeft > 0) left.Add(firstMoveLeft);
        }
        if (left.Count == 0) return null;
        if (moveCap > 0) left.Add(moveCap);
        return TimeSpan.FromMilliseconds(left.Min());
    }

    internal Position WithAttempts(IEnumerable<string> a) => new(Raw, Text) { IllegalAttempts = a.ToList() };

    public override string ToString() => $"Position({GameId} {Status} ply {Ply}{(YourTurn ? " your turn" : "")})";
}
