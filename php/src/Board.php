<?php
declare(strict_types=1);

namespace Neuromate;

/**
 * Board helpers computed locally from a FEN: where the pieces are. Pure geometry, no evaluation.
 * Models read a FEN badly; the server sends the same facts (describe_position, square_info).
 */
final class Board
{
    private const FILES = 'abcdefgh';
    private const ORDER = 'kqrbnp';
    private const KINDS = ['k' => 'king', 'q' => 'queen', 'r' => 'rook', 'b' => 'bishop', 'n' => 'knight', 'p' => 'pawn'];

    /** square => FEN letter (Crazyhouse pockets and "~" marks are ignored). */
    public static function parse(string $fen): array
    {
        $field = explode(' ', trim($fen))[0];
        $field = str_replace('~', '', explode('[', $field)[0]);
        $ranks = explode('/', $field);
        if (count($ranks) !== 8) {
            throw new \InvalidArgumentException("not a FEN: $fen");
        }
        $out = [];
        foreach ($ranks as $i => $row) {
            $f = 0;
            foreach (str_split($row) as $c) {
                if (ctype_digit($c)) {
                    $f += (int) $c;
                } else {
                    $out[self::FILES[$f++] . (8 - $i)] = $c;
                }
            }
        }
        return $out;
    }

    /** Eight strings, rank 8 first, '.' for empty (the server's board_rows). */
    public static function rows(string $fen): array
    {
        $b = self::parse($fen);
        $rows = [];
        for ($r = 8; $r >= 1; $r--) {
            $row = '';
            for ($f = 0; $f < 8; $f++) {
                $row .= $b[self::FILES[$f] . $r] ?? '.';
            }
            $rows[] = $row;
        }
        return $rows;
    }

    /** ASCII diagram: ranks 8 to 1, files a-h, UPPER CASE = White, '.' = empty; $fromBlack turns it around. */
    public static function diagram(string $fen, bool $fromBlack = false): string
    {
        $rs = self::rows($fen);
        $lines = [];
        for ($i = 0; $i < 8; $i++) {
            $idx = $fromBlack ? 7 - $i : $i;
            $row = $fromBlack ? strrev($rs[$idx]) : $rs[$idx];
            $lines[] = (8 - $idx) . ' ' . implode(' ', str_split($row));
        }
        $lines[] = '  ' . ($fromBlack ? 'h g f e d c b a' : 'a b c d e f g h');
        return implode("\n", $lines);
    }

    /** A piece as ['color' => 'white', 'kind' => 'king', 'square' => 'e1', 'symbol' => 'K'], or null. */
    public static function pieceAt(string $fen, string $square): ?array
    {
        $sq = strtolower($square);
        $c = self::parse($fen)[$sq] ?? null;
        return $c === null ? null : self::piece($c, $sq);
    }

    private static function piece(string $c, string $sq): array
    {
        return ['color' => ctype_upper($c) ? 'white' : 'black', 'kind' => self::KINDS[strtolower($c)], 'square' => $sq, 'symbol' => $c];
    }

    /** Pieces of one color (null = both): king, queen, rooks, bishops, knights, pawns, then by file and rank. */
    public static function pieces(string $fen, ?string $color = null): array
    {
        $out = [];
        foreach (self::parse($fen) as $sq => $c) {
            $p = self::piece($c, $sq);
            if ($color === null || $p['color'] === $color) {
                $out[] = $p;
            }
        }
        usort($out, fn($a, $b) => [$a['color'] !== 'white', strpos(self::ORDER, strtolower($a['symbol'])), $a['square'][0], $a['square'][1]]
            <=> [$b['color'] !== 'white', strpos(self::ORDER, strtolower($b['symbol'])), $b['square'][0], $b['square'][1]]);
        return $out;
    }

    /** "King g1, Queen d1, Rooks a1 f1, ..." exactly as the server prints it. */
    public static function piecesText(string $fen, string $color): string
    {
        $ps = self::pieces($fen, $color);
        $parts = [];
        foreach (str_split(self::ORDER) as $k) {
            $sqs = [];
            foreach ($ps as $p) {
                if (strtolower($p['symbol']) === $k) {
                    $sqs[] = $p['square'];
                }
            }
            if ($sqs) {
                $parts[] = ucfirst(self::KINDS[$k]) . (count($sqs) > 1 ? 's' : '') . ' ' . implode(' ', $sqs);
            }
        }
        return $parts ? implode(', ', $parts) : 'none';
    }
}
