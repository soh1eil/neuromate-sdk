<?php
declare(strict_types=1);

namespace Neuromate;

/** The state of one game: FEN, ASCII board, legal moves (only on your turn) and the clocks (milliseconds, 0 = unknown). */
final class Position
{
    public readonly string $gameId, $status, $variant, $timeControl, $pool, $turn, $fen, $board, $color, $opponentName;
    public readonly bool $rated, $yourTurn;
    public readonly int $ply;
    /** @var array<int,array{uci:string,san:string}> */
    public readonly array $legalMoves;
    /** @var string[] */
    public readonly array $movesSan;
    /** "", "from_opponent" or "from_you". */
    public readonly string $drawOffer, $takebackRequest;
    public readonly int $youMs, $opponentMs, $turnTimeLeftMs, $moveCapMs, $firstMoveTimeLeftMs;
    public readonly string $running, $resultScore, $resultWinner, $resultTermination, $tournamentId, $next;
    /** "casual" or "fide_strict" (illegal moves are penalised, no takebacks, no hints). */
    public readonly string $ruleset, $rulesText;
    /** false in strict games: never draw legal-move dots. */
    public readonly bool $hintsAllowed;
    /** Language (en fa ar es fr zh ru de pl ja ko) of the human behind your account / of your opponent ("" for agents and bots): write notes and chat in it. */
    public readonly string $userLocale, $opponentLocale;
    /** Rejected moves of this turn ("move: reason"), filled by play(). @var string[] */
    public array $illegalAttempts = [];

    /** @param array<string,mixed> $raw */
    public function __construct(public readonly array $raw, public readonly string $text = '')
    {
        $g = $raw;
        $s = fn(mixed $a, string $k): string => is_array($a) && is_string($a[$k] ?? null) ? $a[$k] : '';
        $n = fn(mixed $a, string $k): int => is_array($a) && is_numeric($a[$k] ?? null) ? (int)$a[$k] : 0;
        $this->gameId = $s($g, 'game_id'); $this->status = $s($g, 'status'); $this->variant = $s($g, 'variant');
        $this->timeControl = $s($g, 'time_control'); $this->pool = $s($g, 'pool'); $this->turn = $s($g, 'turn');
        $this->rated = (bool)($g['rated'] ?? false); $this->yourTurn = (bool)($g['your_turn'] ?? false); $this->ply = $n($g, 'ply');
        $this->fen = $s($g, 'fen'); $this->board = $s($g, 'board');
        $this->color = $s($g['you'] ?? null, 'color'); $this->opponentName = $s($g['opponent'] ?? null, 'name');
        $this->legalMoves = array_map(fn($m) => ['uci' => $s($m, 'uci'), 'san' => $s($m, 'san')], (array)($g['legal_moves'] ?? []));
        $this->movesSan = array_values(array_filter((array)($g['moves_san'] ?? []), 'is_string'));
        $this->drawOffer = $s($g, 'draw_offer'); $this->takebackRequest = $s($g, 'takeback_request');
        $c = $g['clocks'] ?? null;
        $this->youMs = $n($c, 'you_ms'); $this->opponentMs = $n($c, 'opponent_ms'); $this->running = $s($c, 'running');
        $this->turnTimeLeftMs = $n($c, 'your_turn_time_left_ms'); $this->moveCapMs = $n($c, 'move_cap_ms');
        $this->firstMoveTimeLeftMs = $n($c, 'first_move_time_left_ms');
        $r = $g['result'] ?? null;
        $this->resultScore = $s($r, 'score'); $this->resultWinner = $s($r, 'winner'); $this->resultTermination = $s($r, 'termination');
        $this->tournamentId = $s($g['tournament'] ?? null, 'id'); $this->next = $s($g, 'next');
        $this->ruleset = $s($g, 'ruleset') ?: 'casual'; $this->rulesText = $s($g, 'rules_text'); $this->hintsAllowed = (bool)($g['hints_allowed'] ?? true);
        $this->userLocale = $s($g, 'user_locale'); $this->opponentLocale = $s($g, 'opponent_locale');
    }

    /** Whether FIDE strict rules apply. */
    public function strict(): bool { return $this->ruleset === 'fide_strict'; }
    public function isOver(): bool { return $this->status === 'finished' || $this->status === 'aborted'; }

    /** @return string[] */
    public function legalUci(): array { return array_column($this->legalMoves, 'uci'); }

    /** Seconds you may think about this move (the smallest relevant clock), or null when no clock applies. */
    public function secondsForThisMove(): ?float
    {
        $left = [];
        if ($this->running === 'you' && $this->turnTimeLeftMs > 0) {
            $left[] = $this->turnTimeLeftMs;
        } else {
            if ($this->turnTimeLeftMs > 0) { $left[] = $this->turnTimeLeftMs; }
            if ($this->firstMoveTimeLeftMs > 0) { $left[] = $this->firstMoveTimeLeftMs; }
        }
        if (!$left) { return null; }
        if ($this->moveCapMs > 0) { $left[] = $this->moveCapMs; }
        return min($left) / 1000;
    }

    public function withAttempts(array $attempts): self
    {
        $p = new self($this->raw, $this->text);
        $p->illegalAttempts = $attempts;
        return $p;
    }

    public function __toString(): string { return "Position({$this->gameId} {$this->status} ply {$this->ply}" . ($this->yourTurn ? ' your turn' : '') . ')'; }
}
