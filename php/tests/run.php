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

echo $failures ? "\n$failures test(s) failed\n" : "\nall tests passed\n";
exit($failures ? 1 : 0);
