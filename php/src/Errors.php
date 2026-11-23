<?php
declare(strict_types=1);

namespace Neuromate;

/** Base of every error the SDK throws. retryable() says whether repeating the call may help. */
class NeuromateException extends \RuntimeException
{
    public function retryable(): bool { return false; }
}

/** The request never got an answer (connection refused, timeout, DNS). */
class TransportException extends NeuromateException
{
    public function retryable(): bool { return true; }
}

/** An unexpected HTTP status. */
class HttpException extends NeuromateException
{
    public function __construct(public readonly int $status, string $body) { parent::__construct("HTTP $status: $body"); }
}

/** HTTP 401: the API key is missing, wrong or revoked. */
class AuthException extends NeuromateException
{
    public function __construct(string $message, public readonly string $errorCode = '', public readonly string $hint = '') { parent::__construct($message); }
}

/** HTTP 5xx or a platform INTERNAL ERROR. */
class ServerException extends NeuromateException
{
    public function retryable(): bool { return true; }
}

/** Too many requests; wait retryAfter seconds before retrying. */
class RateLimitedException extends NeuromateException
{
    public function __construct(string $message, public readonly float $retryAfter = 1.0) { parent::__construct($message); }
    public function retryable(): bool { return true; }
}

/** A JSON-RPC level error (unknown tool, invalid params, unreadable reply). */
class ProtocolException extends NeuromateException
{
    public function __construct(string $message, public readonly int $rpcCode = 0, public readonly mixed $data = null) { parent::__construct($message); }
}

/**
 * A tool ran and said no. getMessage() is the server's complete teaching text; $errorCode is the stable machine code
 * (illegal_move, not_your_turn, stale_ply, game_over, model_not_reported, invalid_params, ...).
 */
class ToolException extends NeuromateException
{
    /** @param string[] $legalMoves */
    public function __construct(
        public readonly string $tool, public readonly string $errorCode, public readonly string $detail, public readonly string $hint,
        public readonly array $legalMoves, public readonly ?Position $position, string $text, public readonly array $context = [],
    ) {
        parent::__construct($text !== '' ? $text : $detail);
    }

    /** @param array<string,mixed> $structured */
    public static function from(string $tool, string $text, array $structured): self
    {
        $err = is_array($structured['error'] ?? null) ? $structured['error'] : [];
        $code = (string)($err['code'] ?? '');
        $msg = (string)($err['message'] ?? '');
        if ($msg === '') { $msg = trim($text); }
        $hint = (string)($err['hint'] ?? '');
        if ($hint === '') { $hint = (string)($err['what_to_do'] ?? ''); }
        $legal = array_values(array_filter((array)($err['legal_moves'] ?? []), 'is_string'));
        $ctx = array_diff_key($err, array_flip(['code', 'message', 'hint', 'what_to_do', 'legal_moves']));
        $pos = is_array($structured['game'] ?? null) ? new Position($structured['game'], $text) : null;
        $cls = match ($code) {
            'illegal_move' => IllegalMoveException::class,
            'not_your_turn' => NotYourTurnException::class,
            'stale_ply' => StalePlyException::class,
            'game_over' => GameOverException::class,
            'model_not_reported' => ModelNotReportedException::class,
            'conflict' => ConflictException::class,
            default => self::class,
        };
        return new $cls($tool, $code, $msg, $hint, $legal, $pos, $text, $ctx);
    }
}

class IllegalMoveException extends ToolException {}
class NotYourTurnException extends ToolException {}
/** expected_ply no longer matches: re-read the game. */
class StalePlyException extends ToolException {}
class GameOverException extends ToolException {}
/** report_model has not been called in this session (the client does it for you when you give it a model). */
class ModelNotReportedException extends ToolException {}
class ConflictException extends ToolException {}
