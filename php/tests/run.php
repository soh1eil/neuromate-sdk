<?php
declare(strict_types=1);
// Unit tests against the recordings in ../testdata, through a scripted fake server (no network, no dependencies).
// Run: php tests/run.php
require __DIR__ . '/../src/autoload.php';

use Neuromate\{AuthException, Client, IllegalMoveException, Move, Position, ServerException, ToolException};

function fixture(string $name): array { return json_decode(file_get_contents(__DIR__ . "/../../testdata/$name.json"), true); }
function result(string $name): array { return fixture($name)['response']['result']; }
function waitResult(string $reason, string $gameFrom): array
{
    return ['content' => [['type' => 'text', 'text' => 'WAIT']], 'structuredContent' => ['reason' => $reason, 'events' => [], 'game' => result($gameFrom)['structuredContent']['game']]];
}

final class Fake
{
    public array $script = [], $names = [], $args = [];
    public ?array $forced = null;
    public string $contentType = 'application/json';

    public function on(string $tool, array ...$results): self { $this->script[$tool] = $results; return $this; }

    public function client(string $key = 'nm_live_test', array|string|null $model = null): Client
    {
        return new Client($key, null, $model, function (string $url, array $h, string $body, float $t): array {
            if ($this->forced) { return $this->forced; }
            $req = json_decode($body, true);
            if ($req['method'] === 'notifications/initialized') { return ['status' => 202, 'headers' => [], 'body' => '']; }
            if ($req['method'] === 'initialize') {
                $result = ['serverInfo' => ['name' => 'fake', 'version' => '1']];
            } else {
                $name = $req['params']['name'];
                $this->names[] = $name;
                $this->args[] = $req['params']['arguments'];
                if (!isset($this->script[$name])) { throw new RuntimeException("no scripted reply for $name"); }
                $result = count($this->script[$name]) > 1 ? array_shift($this->script[$name]) : $this->script[$name][0];
            }
            $json = json_encode(['jsonrpc' => '2.0', 'id' => $req['id'], 'result' => $result]);
            if (str_contains($this->contentType, 'event-stream')) { $json = "event: message\ndata: $json\n\n"; }
            return ['status' => 200, 'headers' => ['content-type' => $this->contentType], 'body' => $json];
        });
    }
}

$failures = 0;
function test(string $name, callable $fn): void
{
    global $failures;
    try { $fn(); echo "ok   $name\n"; } catch (Throwable $e) { $failures++; echo "FAIL $name: " . get_class($e) . ': ' . $e->getMessage() . "\n"; }
}
function eq(mixed $a, mixed $b, string $what = ''): void { if ($a !== $b) { throw new Exception("$what expected " . json_encode($b) . ' got ' . json_encode($a)); } }
function yes(bool $c, string $what = ''): void { if (!$c) { throw new Exception("expected true: $what"); } }
function throws(string $cls, callable $fn): Throwable
{
    try { $fn(); } catch (Throwable $e) { if ($e instanceof $cls) { return $e; } throw new Exception("expected $cls, got " . get_class($e) . ': ' . $e->getMessage()); }
    throw new Exception("expected $cls, nothing thrown");
}

test('queue then play until game over', function () {
    $f = (new Fake)->on('whoami', result('whoami_idle'))->on('join_queue', result('join_queue_waiting'))
        ->on('wait_for_turn', waitResult('your_turn', 'get_game_start'), waitResult('game_over', 'get_game_finished'))->on('make_move', result('move_ok'));
    $seen = 0;
    $end = $f->client()->play(function (Position $p) use (&$seen) { $seen++; return Move::of('e4', 'centre'); }, ['time_control' => '3+0']);
    yes($end->isOver());
    eq($seen, 1);
    eq($f->names, ['whoami', 'join_queue', 'wait_for_turn', 'make_move', 'wait_for_turn']);
    $mv = $f->args[3];
    eq($mv['move'], 'e4'); eq($mv['note'], 'centre'); eq($mv['expected_ply'], 0);
    yes(str_starts_with($mv['client_move_id'], $end->gameId));
    eq($f->args[1], ['time_control' => '3+0']);
});

test('illegal move is typed and carries code', function () {
    $f = (new Fake)->on('make_move', result('err_illegal_move'));
    $e = throws(IllegalMoveException::class, fn() => $f->client()->makeMove('e2e5', '', 'x2'));
    eq($e->errorCode, 'illegal_move');
    eq(count($e->legalMoves), 20);
    yes(in_array('e2e4', $e->legalMoves, true));
    yes($e->position !== null);
    yes(str_contains($e->getMessage(), 'ERROR (illegal_move)'));
});

test('rejected moves are fed back then fallback is used', function () {
    $f = (new Fake)->on('wait_for_turn', waitResult('your_turn', 'get_game_start'), waitResult('game_over', 'get_game_finished'))
        ->on('make_move', result('err_illegal_move'), result('err_illegal_move'), result('move_ok'));
    $seen = [];
    $f->client()->play(function (Position $p) use (&$seen) { $seen[] = count($p->illegalAttempts); return 'e2e5'; }, ['game_id' => 'g76p6aeov4', 'max_illegal' => 1]);
    eq($seen, [0]);
    eq(count(array_filter($f->names, fn($n) => $n === 'make_move')), 3);
});

test('model is reported before the first call', function () {
    $f = (new Fake)->on('report_model', result('report_model'))->on('join_queue', result('join_queue_waiting'));
    $f->client('nm_live_test', ['name' => 'Claude Opus 5', 'version' => '2026-06', 'provider' => 'Anthropic'])->joinQueue();
    eq($f->names, ['report_model', 'join_queue']);
    eq($f->args[0]['name'], 'Claude Opus 5'); eq($f->args[0]['provider'], 'Anthropic');
});

test('model_not_reported is repaired once', function () {
    $f = (new Fake)->on('report_model', result('report_model'))->on('join_queue', result('err_model_not_reported'), result('join_queue_waiting'));
    $f->client('nm_live_test', 'Claude Opus 5')->joinQueue();
    eq($f->names, ['report_model', 'join_queue', 'report_model', 'join_queue']);
});

test('model_not_reported without a model explains how to fix', function () {
    $f = (new Fake)->on('join_queue', result('err_model_not_reported'));
    $e = throws(ToolException::class, fn() => $f->client()->joinQueue());
    eq($e->errorCode, 'model_not_reported');
    yes(str_contains($e->detail, "new Client(model:"));
});

test('401 is AuthException', function () {
    $f = new Fake;
    $f->forced = ['status' => 401, 'headers' => [], 'body' => json_encode(fixture('http_401')['response'])];
    $e = throws(AuthException::class, fn() => $f->client()->whoami());
    eq($e->errorCode, 'unauthorized'); yes(!$e->retryable()); yes(str_contains($e->getMessage(), 'Authorization: Bearer'));
});

test('5xx is retryable', function () {
    $f = new Fake;
    $f->forced = ['status' => 503, 'headers' => [], 'body' => 'down'];
    yes(throws(ServerException::class, fn() => $f->client()->whoami())->retryable());
});

test('server-sent events reply is unwrapped', function () {
    $f = (new Fake)->on('whoami', result('whoami_idle'));
    $f->contentType = 'text/event-stream';
    eq($f->client()->whoami()['you']['name'], 'Alice');
});

test('key is never printed', function () { yes(!str_contains((string)(new Fake)->client('nm_live_SECRETSECRETSECRET'), 'SECRETSECRET')); });

test('tournaments come back as arrays', function () {
    $f = (new Fake)->on('list_tournaments', result('tournaments_list'));
    $d = $f->client()->listTournaments('registering,running', 'swiss');
    eq($d['tournaments'][0]['id'], 't_k3m9x2qa');
    eq($f->args[0], ['status' => 'registering,running', 'kind' => 'swiss']);
});

test('position parses clocks and legal moves', function () {
    $p = new Position(result('get_game_start')['structuredContent']['game']);
    eq(count($p->legalMoves), 20); eq($p->color, 'white'); yes($p->yourTurn); yes($p->secondsForThisMove() > 80);
});

test('chat is untrusted data and strict rules are parsed', function () {
    $f = (new Fake)->on('send_chat', result('send_chat'))->on('read_chat', result('read_chat'))->on('get_game', result('get_game_strict'));
    $c = $f->client();
    $m = $c->sendChat('good luck!', 'gzcffjrmdb');
    eq($m['text'], 'good luck!'); eq($m['author']['kind'], 'agent'); eq($f->args[0]['text'], 'good luck!');
    $log = $c->readChat('gzcffjrmdb');
    eq($log['last_seq'], 1); eq($log['messages'][0]['untrusted'], true); eq(isset($f->args[1]['after']), false);
    $p = $c->getGame();
    eq($p->strict(), true); eq($p->hintsAllowed, false);
});


// ---- watch: standby heartbeat, auto-accept rules, the loop ----

use Neuromate\{Rules, Watch, Watcher};

function onRules(array $patch = []): array
{
    return $patch + ['enabled' => true, 'speeds' => ['blitz'], 'variants' => ['standard'], 'rated' => 'any', 'ruleset' => 'any', 'opponent_kinds' => [], 'max_concurrent' => 1, 'decline_others' => false];
}
function standbyResult(array $patch = []): array
{
    $r = result('standby_on');
    $r['structuredContent']['rules'] = onRules($patch);
    return $r;
}
function runWatcher(Fake $f, callable $until, array $o = []): array
{
    $logs = [];
    $w = new Watcher($f->client(), $o['handler'] ?? fn($p) => 'e4', ['log' => function ($m) use (&$logs) { $logs[] = $m; }, 'heartbeat_every' => 0.0, 'wait_seconds' => 0.01,
        'until' => function ($w) use (&$logs, $until) { return $until($w, $logs); }] + $o);
    $w->run();
    return [$w, $logs];
}
$watchBase = function (array $waits, array $standby = null): Fake {
    return (new Fake)->on('standby', $standby ?? standbyResult())->on('whoami', result('whoami_idle'))->on('list_challenges', result('list_challenges_empty'))
        ->on('wait_for_turn', ...$waits)->on('accept_challenge', result('get_game_start'))->on('make_move', result('move_ok'))->on('decline_challenge', result('cancel'));
};

test('watch: rules match the shared vectors', function () {
    $cases = json_decode(file_get_contents(__DIR__ . '/../../testdata/auto_accept_cases.json'), true);
    yes(count($cases) >= 15, 'enough cases');
    foreach ($cases as $c) { eq(Rules::fromArray($c['rules'])->evaluate($c['challenge'], $c['playing'])->action, $c['expect'], $c['name']); }
});

test('watch: speed and challenge info', function () {
    eq(array_map(fn($x) => Watch::speedOf(null, $x), ['1+0', '3+2', '10+0', '30+0', '2d', 'untimed']), ['bullet', 'blitz', 'rapid', 'classical', 'correspondence', 'untimed']);
    eq(Watch::speedOf(['kind' => 'realtime', 'initial_ms' => 180000, 'increment_ms' => 2000]), 'blitz');
    $info = Watch::infoFromEvent(result('wait_challenge_received')['structuredContent']['events'][0]);
    eq([$info['speed'], $info['from_kind'], $info['variant'], $info['rated']], ['blitz', 'agent', 'standard', false]);
    eq(Watch::infoFromChallenge(result('list_challenges_busy')['structuredContent']['incoming_challenges'][0])['speed'], 'rapid');
});

test('watch: standby parses the owner rules', function () {
    $d = (new Fake)->on('standby', standbyResult(['speeds' => ['bullet']]))->client()->standby('x');
    eq($d->onDuty, true); eq($d->rules->speeds, ['bullet']); eq($d->heartbeatEverySeconds, 30);
});

test('watch: accepts a matching challenge and plays the game', function () use ($watchBase) {
    $f = $watchBase([result('wait_challenge_received'), result('wait_timeout')]);
    $f->script['get_game'] = [result('get_game_start')];
    $f->on('wait_for_turn', result('wait_challenge_received'), waitResult('your_turn', 'get_game_start'), waitResult('game_over', 'resign'), result('wait_timeout'));
    [$w] = runWatcher($f, fn($w) => $w->played >= 1);
    eq($w->played, 1); eq($w->accepted, 1);
    eq($f->names[0], 'standby'); yes(in_array('accept_challenge', $f->names, true), 'accepted'); yes(in_array('make_move', $f->names, true), 'moved');
    eq($f->args[array_search('accept_challenge', $f->names, true)]['id'], 'ch_c4v3a4vr');
    eq(end($f->args)['action'], 'off', 'a clean shutdown ends the duty');
    yes(str_starts_with($f->args[0]['client'], 'neuromate-php/'), 'client name');
});

test('watch: a challenge outside the rules is left or declined', function () use ($watchBase) {
    $f = $watchBase([result('wait_challenge_received'), result('wait_timeout')], standbyResult(['speeds' => ['bullet']]));
    runWatcher($f, fn($w, $logs) => count(array_filter($logs, fn($m) => str_contains($m, 'left for the owner'))) > 0);
    yes(!in_array('accept_challenge', $f->names, true) && !in_array('decline_challenge', $f->names, true), 'left alone');
    $f = $watchBase([result('wait_challenge_received'), result('wait_timeout')], standbyResult(['speeds' => ['bullet'], 'decline_others' => true]));
    runWatcher($f, fn($w) => in_array('decline_challenge', $f->names, true));
    yes(!in_array('accept_challenge', $f->names, true), 'not accepted');
    yes(str_contains($f->args[array_search('decline_challenge', $f->names, true)]['reason'], 'blitz'), 'reason names the speed');
});

test('watch: local rules override the owner, a running game resumes, a crashing handler survives', function () use ($watchBase) {
    $f = $watchBase([result('wait_challenge_received'), waitResult('your_turn', 'get_game_start'), waitResult('game_over', 'resign'), result('wait_timeout')], standbyResult(['enabled' => false]));
    [$w] = runWatcher($f, fn($w) => $w->played >= 1, ['rules' => new Rules(true)]);
    eq($w->accepted, 1);

    $gid = result('get_game_start')['structuredContent']['game']['game_id'];
    $busy = result('whoami_idle');
    $busy['structuredContent']['active_games'] = [['game_id' => $gid, 'opponent' => 'Bob', 'color' => 'white', 'your_turn' => true, 'ply' => 0, 'variant' => 'standard']];
    $f = $watchBase([waitResult('your_turn', 'get_game_start'), waitResult('game_over', 'resign'), result('wait_timeout')]);
    $f->on('whoami', $busy);
    [$w] = runWatcher($f, fn($w) => $w->played >= 1);
    eq($w->played, 1); yes(!in_array('accept_challenge', $f->names, true), 'resumed, not accepted');

    $f = $watchBase([result('wait_challenge_received'), waitResult('your_turn', 'get_game_start'), waitResult('game_over', 'resign'), result('wait_timeout')]);
    [$w, $logs] = runWatcher($f, fn($w) => $w->played >= 1, ['handler' => function ($p) { throw new RuntimeException('model crashed'); }]);
    eq($w->played, 1); yes(count(array_filter($logs, fn($m) => str_contains($m, 'handler error'))) > 0, 'logged');
    yes(in_array('make_move', $f->names, true), 'a fallback move was played');
});

test('watch: a rejected key stops the watcher', function () {
    $f = new Fake;
    $f->forced = ['status' => 401, 'headers' => ['content-type' => 'application/json'], 'body' => json_encode(fixture('http_401')['response'])];
    $w = new Watcher($f->client(), fn($p) => 'e4', ['log' => fn($m) => null, 'heartbeat_every' => 10.0]);
    throws(AuthException::class, fn() => $w->run());
});

echo $failures ? "\n$failures test(s) failed\n" : "\nall tests passed\n";
exit($failures ? 1 : 0);
