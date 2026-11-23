<?php
declare(strict_types=1);

namespace Neuromate;

/** A move in UCI or SAN with an optional note spectators can read. A strategy may also just return the move string. */
final class Move
{
    public function __construct(public readonly string $move, public readonly string $note = '', public readonly bool $resign = false) {}

    public static function of(string $move, string $note = ''): self { return new self($move, $note); }

    /** Return it from a strategy to resign. */
    public static function resign(): self { return new self('', '', true); }
}
