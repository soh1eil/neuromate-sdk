using System;
using System.Collections.Generic;
using System.Linq;

namespace Neuromate;

/// <summary>
/// Board helpers computed locally from a FEN: where the pieces are. Pure geometry, no evaluation.
/// Models read a FEN badly; the server sends the same facts (describe_position, square_info).
/// </summary>
public static class Board
{
    private const string Files = "abcdefgh";
    private const string Order = "kqrbnp";

    /// <summary>One piece: Color "white"/"black", Kind "king".."pawn", Square "e4", Symbol the FEN letter.</summary>
    public sealed record Piece(string Color, string Kind, string Square, char Symbol);

    private static string KindOf(char c) => char.ToLowerInvariant(c) switch
    {
        'k' => "king", 'q' => "queen", 'r' => "rook", 'b' => "bishop", 'n' => "knight", _ => "pawn",
    };

    /// <summary>square to FEN letter (Crazyhouse pockets and "~" marks are ignored).</summary>
    public static Dictionary<string, char> Parse(string fen)
    {
        var field = fen.Trim().Split(' ')[0];
        var br = field.IndexOf('[');
        if (br >= 0) field = field[..br];
        field = field.Replace("~", "");
        var ranks = field.Split('/');
        if (ranks.Length != 8) throw new ArgumentException("not a FEN: " + fen);
        var outp = new Dictionary<string, char>();
        for (var i = 0; i < 8; i++)
        {
            var f = 0;
            foreach (var c in ranks[i])
            {
                if (char.IsDigit(c)) f += c - '0';
                else outp[Files[f++].ToString() + (8 - i)] = c;
            }
        }
        return outp;
    }

    /// <summary>Eight strings, rank 8 first, '.' for empty (the server's board_rows).</summary>
    public static List<string> Rows(string fen)
    {
        var b = Parse(fen);
        var rows = new List<string>();
        for (var r = 8; r >= 1; r--)
            rows.Add(new string(Enumerable.Range(0, 8).Select(f => b.TryGetValue(Files[f].ToString() + r, out var c) ? c : '.').ToArray()));
        return rows;
    }

    /// <summary>ASCII diagram: ranks 8 to 1, files a-h, UPPER CASE = White, '.' = empty; fromBlack turns it around.</summary>
    public static string Diagram(string fen, bool fromBlack = false)
    {
        var rs = Rows(fen);
        var lines = new List<string>();
        for (var i = 0; i < 8; i++)
        {
            var idx = fromBlack ? 7 - i : i;
            var row = fromBlack ? new string(rs[idx].Reverse().ToArray()) : rs[idx];
            lines.Add((8 - idx) + " " + string.Join(" ", row.ToCharArray()));
        }
        lines.Add("  " + (fromBlack ? "h g f e d c b a" : "a b c d e f g h"));
        return string.Join("\n", lines);
    }

    public static Piece? PieceAt(string fen, string square)
    {
        var sq = square.ToLowerInvariant();
        return Parse(fen).TryGetValue(sq, out var c) ? new Piece(char.IsUpper(c) ? "white" : "black", KindOf(c), sq, c) : null;
    }

    /// <summary>Pieces of one color (null = both): king, queen, rooks, bishops, knights, pawns, then by file and rank.</summary>
    public static List<Piece> Pieces(string fen, string? color = null) =>
        Parse(fen).Select(kv => new Piece(char.IsUpper(kv.Value) ? "white" : "black", KindOf(kv.Value), kv.Key, kv.Value))
            .Where(p => color == null || p.Color == color)
            .OrderBy(p => p.Color != "white").ThenBy(p => Order.IndexOf(char.ToLowerInvariant(p.Symbol))).ThenBy(p => p.Square[0]).ThenBy(p => p.Square[1])
            .ToList();

    /// <summary>"King g1, Queen d1, Rooks a1 f1, ..." exactly as the server prints it.</summary>
    public static string PiecesText(string fen, string color)
    {
        var ps = Pieces(fen, color);
        var parts = new List<string>();
        foreach (var k in Order)
        {
            var sqs = ps.Where(p => char.ToLowerInvariant(p.Symbol) == k).Select(p => p.Square).ToList();
            if (sqs.Count == 0) continue;
            var name = KindOf(k);
            parts.Add(char.ToUpperInvariant(name[0]) + name[1..] + (sqs.Count > 1 ? "s" : "") + " " + string.Join(" ", sqs));
        }
        return parts.Count == 0 ? "none" : string.Join(", ", parts);
    }
}
