<?php
declare(strict_types=1);

namespace Neuromate;

/**
 * Staying on duty: $client->watch($handler). A watcher sends a heartbeat every 30 s (so the agent shows as on duty and challenges
 * are held for it instead of expiring), reconnects with backoff, resumes a game that was running when it started, accepts
 * challenges by the auto-accept rules the owner set on the agent page and calls your handler for every move.
 *
 * PHP has no threads: the watcher plays one game at a time (so `max_concurrent` above 1 never matters here) and sends its
 * heartbeat between moves; while a game runs, `wait_for_turn` keeps the agent present by itself.
 */
final class Decision
{
    public function __construct(public readonly string $action, public readonly string $reason = '') {} // accept | decline | ignore
}

/** The owner's auto-accept rules (mirrors server/internal/duty/rules.go; tested against sdk/testdata/auto_accept_cases.json). Empty lists mean "any". */
final class Rules
{
    public function __construct(
        public readonly bool $enabled = false, public readonly array $speeds = [], public readonly array $variants = [],
        public readonly string $rated = 'any', public readonly string $ruleset = 'any', public readonly array $opponentKinds = [],
        public readonly int $maxConcurrent = 1, public readonly bool $declineOthers = false,
    ) {}

    public static function fromArray(?array $d): self
    {
        $d ??= [];
        return new self(
            ($d['enabled'] ?? false) === true, array_map('strval', (array)($d['speeds'] ?? [])), array_map('strval', (array)($d['variants'] ?? [])),
            (string)($d['rated'] ?? '') ?: 'any', (string)($d['ruleset'] ?? '') ?: 'any', array_map('strval', (array)($d['opponent_kinds'] ?? [])),
            max(1, (int)($d['max_concurrent'] ?? 1)), ($d['decline_others'] ?? false) === true,
        );
    }

    public function toArray(): array
    {
        return ['enabled' => $this->enabled, 'speeds' => $this->speeds, 'variants' => $this->variants, 'rated' => $this->rated, 'ruleset' => $this->ruleset,
                'opponent_kinds' => $this->opponentKinds, 'max_concurrent' => $this->maxConcurrent, 'decline_others' => $this->declineOthers];
    }

    /** $c: speed, variant, rated, ruleset (casual | fide_strict), from_kind (human | agent | bot). $playing: games running now. */
    public function evaluate(array $c, int $playing): Decision
    {
        if (!$this->enabled) { return new Decision('ignore', 'auto-accept is off'); }
        $refuse = fn(string $why): Decision => new Decision($this->declineOthers ? 'decline' : 'ignore', $why);
        $speed = (string)($c['speed'] ?? '');
        if ($this->speeds && !in_array($speed, $this->speeds, true)) { return $refuse("time control $speed is not accepted"); }
        $variant = (string)($c['variant'] ?? '');
        if ($this->variants && !in_array($variant, $this->variants, true)) { return $refuse("variant $variant is not accepted"); }
        $rated = ($c['rated'] ?? false) === true;
        if ($this->rated === 'casual' && $rated) { return $refuse('only casual games are accepted'); }
        if ($this->rated === 'rated' && !$rated) { return $refuse('only rated games are accepted'); }
        $strict = in_array($c['ruleset'] ?? '', ['fide_strict', 'strict'], true);
        if ($this->ruleset === 'casual' && $strict) { return $refuse('strict (FIDE) games are not accepted'); }
        if ($this->ruleset === 'strict' && !$strict) { return $refuse('only strict (FIDE) games are accepted'); }
        $kind = (string)($c['from_kind'] ?? '');
        if ($this->opponentKinds && !in_array($kind, $this->opponentKinds, true)) { return $refuse("opponents of kind $kind are not accepted"); }
        if ($playing >= max(1, $this->maxConcurrent)) { return new Decision('ignore', "already playing $playing game(s)"); }
        return new Decision('accept');
    }
}

/** Reply of Client::standby(). */
final class Duty
{
    public function __construct(
        public readonly bool $onDuty, public readonly string $state, public readonly string $mode, public readonly Rules $rules,
        public readonly int $heartbeatEverySeconds = 30, public readonly int $ttlSeconds = 90, public readonly int $incomingChallenges = 0,
    ) {}

    public static function parse(array $d): self
    {
        $p = (array)($d['presence'] ?? []);
        return new self(($d['on_duty'] ?? false) === true, (string)($p['state'] ?? ''), (string)($p['mode'] ?? ''), Rules::fromArray($d['rules'] ?? null),
            (int)($d['heartbeat_every_seconds'] ?? 30) ?: 30, (int)($d['ttl_seconds'] ?? 90) ?: 90, (int)($d['incoming_challenges'] ?? 0));
    }
}

final class Watch
{
    /** bullet / blitz / rapid / classical / correspondence / untimed, from a control array or a label like "3+2". */
    public static function speedOf(?array $control = null, string $label = ''): string
    {
        $control ??= [];
        if (($control['kind'] ?? '') === 'correspondence' || preg_match('/\d\s*d/', $label) || str_contains($label, 'day')) { return 'correspondence'; }
        if (($control['kind'] ?? '') === 'untimed' || strtolower(trim($label)) === 'untimed') { return 'untimed'; }
        if ($control) {
            $inc = max((int)($control['increment_ms'] ?? 0), (int)($control['delay_ms'] ?? 0), (int)($control['bronstein_ms'] ?? 0));
            $est = intdiv((int)($control['initial_ms'] ?? 0) + 40 * $inc, 1000);
        } else {
            if (!preg_match('/^\s*(\d+(?:\.\d+)?)\s*(?:[+|]\s*(\d+))?/', $label, $m)) { return 'classical'; }
            $est = (int)((float)$m[1] * 60) + 40 * (int)($m[2] ?? 0);
        }
        return $est < 180 ? 'bullet' : ($est < 480 ? 'blitz' : ($est < 1500 ? 'rapid' : 'classical'));
    }

    /** The fields Rules::evaluate reads, from a challenge_received event. */
    public static function infoFromEvent(array $ev): array
    {
        $d = (array)($ev['data'] ?? []);
        return ['speed' => ($d['speed'] ?? '') ?: self::speedOf(null, (string)($d['time_control'] ?? '')), 'variant' => ($d['variant'] ?? '') ?: 'standard',
                'rated' => ($d['rated'] ?? false) === true, 'ruleset' => ($d['ruleset'] ?? '') ?: 'casual', 'from_kind' => ($d['from_kind'] ?? '') ?: 'agent'];
    }

    /** The same, from an entry of whoami()'s incoming_challenges. */
    public static function infoFromChallenge(array $c): array
    {
        $p = (array)($c['params'] ?? []);
        return ['speed' => self::speedOf(is_array($p['control'] ?? null) ? $p['control'] : null), 'variant' => ($p['variant'] ?? '') ?: 'standard',
                'rated' => ($p['rated'] ?? false) === true, 'ruleset' => ($p['ruleset'] ?? '') ?: 'casual', 'from_kind' => ((array)($c['from'] ?? []))['kind'] ?? 'agent'];
    }
}

/** The durable loop behind Client::watch(). */
final class Watcher
{
    public int $accepted = 0, $played = 0;
    public Rules $rules;
    private bool $stopped = false, $standbyOk = true;
    private float $lastBeat = 0.0;
    private array $seen = [], $cooldown = [];
    private $handler, $log;
    private string $clientName;
    private ?Rules $localRules;

    /**
     * Options: rules (Rules that override the owner's), server_rules (default true), heartbeat_every (30 s), max_backoff (60 s), wait_seconds (25),
     * log (callable taking a line), client_name, play_options (passed to Client::play), until (callable(Watcher): bool, polled between steps).
     */
    public function __construct(private Client $client, callable $handler, private array $o = [])
    {
        $this->handler = $handler;
        $this->localRules = $o['rules'] ?? null;
        $this->rules = $this->localRules ?? new Rules();
        $this->log = $o['log'] ?? function (string $m): void { fwrite(STDERR, date('H:i:s') . " $m\n"); };
        $this->clientName = $o['client_name'] ?? 'neuromate-php/' . Client::VERSION . ' watch';
        $this->o += ['server_rules' => true, 'heartbeat_every' => 30.0, 'max_backoff' => 60.0, 'wait_seconds' => 25.0, 'play_options' => [], 'until' => null];
    }

    public function stop(): void { $this->stopped = true; }
    public function stopped(): bool { return $this->stopped || ($this->o['until'] !== null && ($this->o['until'])($this)); }
    private function log(string $m): void { ($this->log)($m); }

    private function nap(float $s): void
    {
        $end = microtime(true) + $s;
        while (!$this->stopped() && microtime(true) < $end) { usleep((int)(min(0.25, max(0.01, $end - microtime(true))) * 1_000_000)); }
    }

    private function beat(bool $force = false): void
    {
        if (!$this->standbyOk || (!$force && microtime(true) - $this->lastBeat < $this->o['heartbeat_every'])) { return; }
        $this->lastBeat = microtime(true);
        try {
            $d = $this->client->standby($this->clientName);
        } catch (ProtocolException $e) {
            if (str_contains($e->getMessage(), 'unknown tool')) {
                $this->standbyOk = false;
                $this->log('this server has no standby tool: running without heartbeats (tool calls still keep you present for two minutes)');
                return;
            }
            throw $e;
        }
        if ($this->o['server_rules'] && $this->localRules === null) {
            if ($d->rules != $this->rules) { $this->log('auto-accept rules updated: ' . ($d->rules->enabled ? 'on' : 'off')); }
            $this->rules = $d->rules;
        }
    }

    /** Runs until stop(), the `until` option or a rejected key (AuthException). */
    public function run(): Watcher
    {
        $this->log("on duty as {$this->clientName}");
        $backoff = 1.0;
        try {
            try { $this->beat(true); } catch (AuthException $e) { throw $e; } catch (NeuromateException $e) { $this->log('first heartbeat failed (' . substr($e->getMessage(), 0, 100) . '); will keep trying'); }
            $first = true;
            while (!$this->stopped()) {
                try {
                    $this->beat();
                    $this->reconcile($first);
                    $first = false;
                    $turn = $this->client->waitForTurn('', (float)$this->o['wait_seconds']);
                    $this->events($turn['events']);
                    $pos = $turn['position'];
                    if ($pos !== null && !$pos->isOver()) { $this->playGame($pos->gameId); }
                    $backoff = 1.0;
                } catch (AuthException $e) {
                    $this->log('the API key was rejected (' . substr($e->getMessage(), 0, 80) . '); stopping');
                    throw $e;
                } catch (RateLimitedException $e) {
                    $this->nap(min($e->retryAfter, 30.0));
                } catch (NeuromateException $e) {
                    $this->log('connection problem (' . substr($e->getMessage(), 0, 100) . '); retrying in ' . round($backoff) . 's');
                    $this->nap($backoff * (0.5 + mt_rand() / mt_getrandmax()));
                    $backoff = min($backoff * 2, (float)$this->o['max_backoff']);
                }
            }
        } finally {
            $this->stopped = true;
            if ($this->standbyOk) { try { $this->client->standby($this->clientName, true); $this->log('off duty'); } catch (\Throwable) { } }
        }
        return $this;
    }

    private function reconcile(bool $first): void
    {
        $lobby = $this->client->whoami();
        foreach ((array)($lobby['active_games'] ?? []) as $g) { $this->playGame((string)($g['game_id'] ?? '')); }
        $evs = (array)($lobby['events'] ?? []);
        $this->events($evs);
        $pending = [];
        foreach ($evs as $e) { if (($e['type'] ?? '') === 'challenge_received') { $pending[] = $e['data']['challenge_id'] ?? ''; } }
        foreach ((array)($lobby['incoming_challenges'] ?? []) as $ch) {
            $st = (string)($ch['status'] ?? '');
            if (($st === '' || $st === 'pending') && !in_array($ch['id'] ?? '', $pending, true)) {
                $this->challenge((string)$ch['id'], Watch::infoFromChallenge($ch), (string)(((array)($ch['from'] ?? []))['name'] ?? ''));
            }
        }
        if ($evs) { $this->client->listChallenges(max(array_map(fn($e) => (int)($e['seq'] ?? 0), $evs))); }
    }

    private function events(array $evs): void
    {
        foreach ($evs as $ev) {
            $d = (array)($ev['data'] ?? []);
            switch ($ev['type'] ?? '') {
                case 'challenge_received': $this->challenge((string)($d['challenge_id'] ?? ''), Watch::infoFromEvent($ev), (string)($d['from'] ?? '?')); break;
                case 'game_started': if (($d['game_id'] ?? '') !== '') { $this->playGame((string)$d['game_id']); } break;
                case 'tournament_offline_warning': case 'tournament_withdrawn': case 'queue_dropped': $this->log(($ev['type']) . ': ' . ($d['next'] ?? $d['message'] ?? '')); break;
                case 'challenge_declined': $this->log('challenge declined: ' . ($d['reason'] ?? '')); break;
            }
        }
    }

    private function challenge(string $cid, array $info, string $who): void
    {
        if ($cid === '') { return; }
        $d = $this->rules->evaluate($info, 0);
        $first = !isset($this->seen[$cid]);
        if ($d->action === 'accept') {
            try { $pos = $this->client->acceptChallenge($cid); } catch (ToolException $e) {
                $this->log("could not accept $cid from $who: " . substr($e->getMessage(), 0, 120));
                $this->seen[$cid] = true;
                return;
            }
            $this->accepted++;
            $this->seen[$cid] = true;
            $this->log("accepted $cid from $who ({$info['speed']} {$info['variant']}); game {$pos->gameId}");
            $this->playGame($pos->gameId);
        } elseif ($d->action === 'decline') {
            if ($first) { $this->log("declined $cid from $who: {$d->reason}"); }
            try { $this->client->declineChallenge($cid, substr($d->reason, 0, 200)); } catch (ToolException) { }
            $this->seen[$cid] = true;
        } elseif ($first) {
            $this->log("challenge $cid from $who left for the owner: {$d->reason}");
            $this->seen[$cid] = true;
            if (count($this->seen) > 500) { $this->seen = []; }
        }
    }

    private function playGame(string $gameId): void
    {
        if ($gameId === '' || $this->stopped() || microtime(true) - ($this->cooldown[$gameId] ?? -100.0) < 5) { return; }
        $this->log("playing game $gameId");
        $h = $this->handler;
        $safe = function (Position $pos) use ($h) {
            try { return $h($pos); } catch (\Throwable $e) {
                $this->log("handler error in {$pos->gameId}: " . substr($e->getMessage(), 0, 100) . '; playing a fallback move');
                return $pos->legalMoves ? $pos->legalMoves[array_rand($pos->legalMoves)]['uci'] : '';
            }
        };
        $opts = ['game_id' => $gameId, 'on_position' => function (Position $p): void {
            $this->beat();
            if ($this->stopped()) { throw new WatchStopped(); }
        }] + (array)$this->o['play_options'];
        try {
            $final = $this->client->play($safe, $opts);
            $this->log("game $gameId over: {$final->resultScore}");
            $this->played++;
        } catch (WatchStopped) {
            // leaving the game open on purpose; it resumes on the next start
        } catch (AuthException $e) {
            $this->log('the API key was rejected while playing; stopping');
            $this->stop();
            throw $e;
        } catch (NeuromateException $e) {
            $this->log("game $gameId: " . substr($e->getMessage(), 0, 120));
            $this->cooldown[$gameId] = microtime(true);
        }
    }
}

/** Thrown inside a game to leave it when the watcher is stopped. */
final class WatchStopped extends \RuntimeException {}
