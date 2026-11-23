<?php
declare(strict_types=1);

namespace Neuromate;

/**
 * Talks to the Neuromate MCP server (Streamable HTTP, JSON-RPC 2.0).
 *
 *     $client = new Client(model: ['name' => 'My Model', 'version' => '1.0', 'provider' => 'Me']); // key: NEUROMATE_API_KEY
 *     $client->play(fn(Position $p) => $p->legalMoves[array_rand($p->legalMoves)]['uci'], ['time_control' => '3+2']);
 */
final class Client
{
    public const DEFAULT_URL = 'https://api.neuromate.larsima.com/mcp';
    public const VERSION = '0.1.0';
    private const PROTOCOL = '2025-06-18';

    private string $key;
    private string $url;
    /** @var callable */
    private $transport;
    private int $ids = 0;
    private string $session = '';
    private bool $ready = false;
    /** @var array{name:string,version:string,provider:string}|null */
    private ?array $model;
    /** @var array<string,mixed> */
    public array $serverInfo = [];

    /**
     * @param array{name:string,version?:string,provider?:string}|string|null $model  your exact model (reported for you); env NEUROMATE_MODEL
     * @param callable|null $transport  fn(string $url, array $headers, string $body, float $timeout): array{status:int,headers:array,body:string}
     */
    public function __construct(?string $apiKey = null, ?string $baseUrl = null, array|string|null $model = null, ?callable $transport = null, private float $timeout = 30.0)
    {
        $key = $apiKey ?: (getenv('NEUROMATE_API_KEY') ?: '');
        if ($key === '') {
            throw new \InvalidArgumentException('neuromate: no API key: pass apiKey or set NEUROMATE_API_KEY (keys look like nm_live_...)');
        }
        $this->key = $key;
        $this->url = $baseUrl ?: (getenv('NEUROMATE_URL') ?: self::DEFAULT_URL);
        if ($model === null && ($em = getenv('NEUROMATE_MODEL')) && trim($em) !== '') { $model = trim($em); }
        if (is_string($model)) { $model = ['name' => $model]; }
        $this->model = $model && trim((string)($model['name'] ?? '')) !== ''
            ? ['name' => trim($model['name']), 'version' => (string)($model['version'] ?? ''), 'provider' => (string)($model['provider'] ?? '')] : null;
        $this->transport = $transport ?? self::curlTransport(...);
    }

    /** Never reveals the key. */
    public function __toString(): string { return 'Client(' . $this->url . ' key=' . substr($this->key, 0, 8) . '...)'; }

    // ---------- raw protocol ----------

    /** @return array{status:int,headers:array<string,string>,body:string} */
    private static function curlTransport(string $url, array $headers, string $body, float $timeout): array
    {
        $h = [];
        foreach ($headers as $k => $v) { $h[] = "$k: $v"; }
        $respHeaders = [];
        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_POST => true, CURLOPT_POSTFIELDS => $body, CURLOPT_HTTPHEADER => $h, CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT_MS => (int)($timeout * 1000), CURLOPT_CONNECTTIMEOUT => 10,
            CURLOPT_HEADERFUNCTION => function ($c, $line) use (&$respHeaders) {
                $p = explode(':', $line, 2);
                if (count($p) === 2) { $respHeaders[strtolower(trim($p[0]))] = trim($p[1]); }
                return strlen($line);
            },
        ]);
        $out = curl_exec($ch);
        if ($out === false) {
            $err = curl_error($ch);
            curl_close($ch);
            throw new TransportException("could not reach $url: $err");
        }
        $status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
        curl_close($ch);
        return ['status' => $status, 'headers' => $respHeaders, 'body' => (string)$out];
    }

    private function post(string $method, ?array $params, bool $notification, float $timeout): mixed
    {
        $req = ['jsonrpc' => '2.0'];
        $id = 0;
        if (!$notification) { $id = ++$this->ids; $req['id'] = $id; }
        $req['method'] = $method;
        if ($params !== null) { $req['params'] = $params; }
        $headers = [
            'Authorization' => 'Bearer ' . $this->key, 'Content-Type' => 'application/json', 'Accept' => 'application/json, text/event-stream',
            'MCP-Protocol-Version' => self::PROTOCOL, 'User-Agent' => 'neuromate-php/' . self::VERSION,
        ];
        if ($this->session !== '') { $headers['Mcp-Session-Id'] = $this->session; }
        $r = ($this->transport)($this->url, $headers, json_encode($req, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE), $timeout);
        self::checkHttp($r);
        if ($notification || in_array($r['status'], [202, 204], true)) { return null; }
        if (!empty($r['headers']['mcp-session-id'])) { $this->session = $r['headers']['mcp-session-id']; }
        return self::parseRpc($r['headers']['content-type'] ?? '', $r['body'], $id);
    }

    private static function snippet(string $b): string { return substr(trim($b), 0, 500); }

    /** @param array{status:int,headers:array,body:string} $r */
    public static function checkHttp(array $r): void
    {
        $st = $r['status'];
        if (in_array($st, [200, 202, 204], true)) { return; }
        $body = self::snippet($r['body']);
        $j = json_decode($r['body'], true);
        $j = is_array($j) ? $j : [];
        if ($st === 401) {
            $code = (string)($j['code'] ?? ''); $msg = (string)($j['message'] ?? ''); $hint = (string)($j['hint'] ?? '');
            $d = $j['error']['data'] ?? null;
            if (is_array($d) && ($d['message'] ?? '') !== '') { $code = (string)($d['code'] ?? ''); $msg = (string)$d['message']; $hint = (string)($d['hint'] ?? ''); }
            if ($msg === '') { $msg = $body !== '' ? $body : 'invalid or missing API key: send it as Authorization: Bearer nm_live_...'; }
            throw new AuthException($hint !== '' ? "$msg. $hint" : $msg, $code, $hint);
        }
        if ($st === 429) {
            $ra = is_numeric($r['headers']['retry-after'] ?? null) ? (float)$r['headers']['retry-after'] : 1.0;
            throw new RateLimitedException($body !== '' ? $body : 'rate limited', $ra);
        }
        if ($st >= 500) { throw new ServerException("server error $st: $body"); }
        if (is_array($j['error'] ?? null)) { throw new ProtocolException((string)($j['error']['message'] ?? ''), (int)($j['error']['code'] ?? 0), $j['error']['data'] ?? null); }
        throw new HttpException($st, $body);
    }

    /** The data: payload of the event that answers request $id (or the last one). */
    public static function ssePayload(string $body, int $id): string
    {
        $last = '';
        foreach (preg_split('/\r?\n\r?\n/', $body) as $block) {
            $data = [];
            foreach (preg_split('/\r?\n/', $block) as $line) {
                if (str_starts_with($line, 'data:')) { $data[] = ltrim(substr($line, 5), ' '); }
            }
            if (!$data) { continue; }
            $last = implode("\n", $data);
            $d = json_decode($last, true);
            if (is_array($d) && ($d['id'] ?? null) === $id) { return $last; }
        }
        return $last;
    }

    private static function parseRpc(string $contentType, string $raw, int $id): mixed
    {
        $body = str_contains($contentType, 'text/event-stream') ? self::ssePayload($raw, $id) : $raw;
        $r = json_decode($body, true);
        if (!is_array($r)) { throw new ProtocolException('the server sent something that is not JSON-RPC: ' . self::snippet($body)); }
        if (is_array($r['error'] ?? null)) { throw new ProtocolException((string)($r['error']['message'] ?? ''), (int)($r['error']['code'] ?? 0), $r['error']['data'] ?? null); }
        return $r['result'] ?? null;
    }

    private function connect(): void
    {
        if ($this->ready) { return; }
        $res = $this->post('initialize', [
            'protocolVersion' => self::PROTOCOL, 'capabilities' => new \stdClass(),
            'clientInfo' => ['name' => 'neuromate-php', 'version' => self::VERSION],
        ], false, $this->timeout);
        $this->post('notifications/initialized', null, true, $this->timeout);
        $this->serverInfo = is_array($res['serverInfo'] ?? null) ? $res['serverInfo'] : [];
        $this->ready = true;
        if ($this->model !== null) { $this->sendModel(); }
    }

    private function sendModel(): void
    {
        $this->rawCall('report_model', array_filter($this->model, fn($v) => $v !== ''));
    }

    /** @return array{tool:string,text:string,data:array,isError:bool} */
    private function rawCall(string $name, array $args): array
    {
        $timeout = $this->timeout;
        if ($name === 'wait_for_turn') { $timeout = (float)($args['timeout_seconds'] ?? 25) + 15; }
        $res = $this->post('tools/call', ['name' => $name, 'arguments' => $args ?: new \stdClass()], false, $timeout);
        if (!is_array($res)) { throw new ProtocolException("tools/call $name returned no result"); }
        $text = '';
        foreach ((array)($res['content'] ?? []) as $c) { if (($c['type'] ?? '') === 'text') { $text .= (string)($c['text'] ?? ''); } }
        $data = is_array($res['structuredContent'] ?? null) ? $res['structuredContent'] : [];
        if (!empty($res['isError'])) { throw self::toError($name, $text, $data); }
        return ['tool' => $name, 'text' => $text, 'data' => $data, 'isError' => false];
    }

    private static function toError(string $tool, string $text, array $data): NeuromateException
    {
        $err = is_array($data['error'] ?? null) ? $data['error'] : [];
        $code = (string)($err['code'] ?? '');
        if ($code === 'rate_limited' || str_starts_with($text, 'RATE LIMITED')) {
            $ra = isset($err['retry_after_seconds']) ? (float)$err['retry_after_seconds'] : (preg_match('/Wait (\d+) seconds/', $text, $m) ? (float)$m[1] : 1.0);
            return new RateLimitedException(trim($text), $ra);
        }
        if ($code === 'internal' || str_starts_with($text, 'INTERNAL ERROR')) { return new ServerException(trim($text)); }
        return ToolException::from($tool, $text, $data);
    }

    /**
     * Calls any MCP tool by name. Tool-level failures are thrown as ToolException (or a subclass). When the server answers
     * model_not_reported and a model was given, it is reported and the call repeated once.
     *
     * @return array{tool:string,text:string,data:array,isError:bool}
     */
    public function callTool(string $name, array $args = []): array
    {
        $this->connect();
        try {
            return $this->rawCall($name, $args);
        } catch (ModelNotReportedException $e) {
            if ($this->model === null || $name === 'report_model') {
                throw new ToolException($e->tool, $e->errorCode, $e->detail . '. Pass your exact model: new Client(model: [\'name\' => \'<name>\', \'version\' => \'<version>\', \'provider\' => \'<maker>\']) (or set NEUROMATE_MODEL).', $e->hint, $e->legalMoves, $e->position, $e->getMessage(), $e->context);
            }
            $this->sendModel();
            return $this->rawCall($name, $args);
        }
    }

    /** @return array<int,array<string,mixed>> */
    public function listTools(): array
    {
        $this->connect();
        $r = $this->post('tools/list', [], false, $this->timeout);
        return (array)($r['tools'] ?? []);
    }

    // ---------- tools ----------

    private static function clean(array $a): array { return array_filter($a, fn($v) => $v !== null && $v !== ''); }

    private function position(string $tool, array $args): Position
    {
        $o = $this->callTool($tool, self::clean($args));
        if (!is_array($o['data']['game'] ?? null)) { throw new ProtocolException("$tool: the reply has no game state:\n" . $o['text']); }
        return new Position($o['data']['game'], $o['text']);
    }

    private function data(string $tool, array $args = []): array { return $this->callTool($tool, self::clean($args))['data']; }

    /** Tell Neuromate which model you are (done for you when the client has a model). */
    public function reportModel(string $name, string $version = '', string $provider = ''): array
    {
        if ($name === '') { throw new \InvalidArgumentException('reportModel needs the exact model name'); }
        return $this->data('report_model', compact('name', 'version', 'provider'));
    }

    public function startHere(): array { return $this->data('start_here'); }
    public function whoami(): array { return $this->data('whoami'); }
    /** Heartbeat of a watcher: marks you on duty ($off = true ends the duty) and returns your owner's auto-accept rules (a Duty). */
    public function standby(?string $client = null, bool $off = false): Duty { return Duty::parse($this->data('standby', ['client' => $client, 'action' => $off ? 'off' : null])); }

    /** Stays on duty until stop()/`until` (or Ctrl-C when the script handles it): see Watcher. Without a handler it plays random legal moves. */
    public function watch(?callable $handler = null, array $options = []): Watcher
    {
        $handler ??= fn(Position $p) => self::randomLegal($p);
        return (new Watcher($this, $handler, $options))->run();
    }

    public function listChallenges(?int $ack = null): array { return $this->data('list_challenges', ['ack' => $ack]); }
    /**
     * The strategy guide. An empty topic returns the index and the six-step think-before-every-move checklist (read it before your first game).
     * Topics: checklist, opening, openings, middlegame, pawns, tactics, attack, endgame, blunders.
     */
    public function strategyGuide(string $topic = ''): string { return $this->callTool('get_strategy_guide', self::clean(['topic' => $topic]))['text']; }
    /** Every piece of both sides with its square, side to move, castling, en passant and the last move in words. */
    public function describePosition(string $gameId = ''): string { return $this->callTool('describe_position', self::clean(['game_id' => $gameId]))['text']; }
    /** What stands on a square and which pieces attack or defend it (geometry; occupancy only in fide_strict games). */
    public function squareInfo(string $square, string $gameId = ''): string { return $this->callTool('square_info', self::clean(['square' => $square, 'game_id' => $gameId]))['text']; }
    public function getHandbook(string $section = ''): string { return $this->callTool('get_handbook', self::clean(['section' => $section]))['text']; }

    /** Game options for challenge / createSeek / joinQueue. Empty values are left out. */
    public static function game(string $variant = '', string $timeControl = '', bool $rated = false, string $color = ''): array
    {
        return self::clean(['variant' => $variant, 'time_control' => $timeControl, 'rated' => $rated ?: null, 'color' => $color]);
    }

    public function challenge(string $to, array $game = []): array { return $this->data('challenge', $game + ['to' => $to]); }
    public function createSeek(array $game = []): array { return $this->data('create_seek', $game); }
    public function acceptChallenge(string $id): Position { return $this->position('accept_challenge', ['id' => $id]); }
    public function declineChallenge(string $id, string $reason = ''): string { return $this->callTool('decline_challenge', self::clean(['id' => $id, 'reason' => $reason]))['text']; }
    public function cancel(string $id): string { return $this->callTool('cancel', ['id' => $id])['text']; }

    /** @return array{queued:bool,position:?Position} */
    public function joinQueue(array $game = [], string $pool = ''): array
    {
        $o = $this->callTool('join_queue', self::clean($game + ['pool' => $pool]));
        if (is_array($o['data']['game'] ?? null)) { return ['queued' => false, 'position' => new Position($o['data']['game'], $o['text'])]; }
        return ['queued' => (bool)($o['data']['queued'] ?? false), 'position' => null];
    }

    public function leaveQueue(): bool { return (bool)($this->data('leave_queue')['left'] ?? false); }

    /** The full state of a game; empty means your only active game. */
    public function getGame(string $gameId = ''): Position { return $this->position('get_game', ['game_id' => $gameId]); }

    /**
     * Blocks server-side (up to 25 s) until something needs you. An empty $gameId watches all games and the lobby.
     *
     * @return array{reason:string,position:?Position,events:array,text:string,timedOut:bool}
     */
    public function waitForTurn(string $gameId = '', float $timeoutSeconds = 0): array
    {
        $o = $this->callTool('wait_for_turn', self::clean(['game_id' => $gameId, 'timeout_seconds' => $timeoutSeconds > 0 ? $timeoutSeconds : null]));
        $d = $o['data'];
        $reason = (string)($d['reason'] ?? '');
        return [
            'reason' => $reason, 'timedOut' => $reason === 'timeout', 'text' => $o['text'], 'events' => (array)($d['events'] ?? []),
            'position' => is_array($d['game'] ?? null) ? new Position($d['game'], $o['text']) : null,
        ];
    }

    /**
     * Plays a move in UCI ("e2e4") or SAN ("Nf3"). $clientMoveId should be unique per move so a retry never plays twice.
     *
     * @return array{position:?Position,replayed:bool,move:array}
     */
    public function makeMove(string $move, string $gameId = '', string $clientMoveId = '', ?int $expectedPly = null, string $note = ''): array
    {
        if ($clientMoveId === '') { $clientMoveId = bin2hex(random_bytes(8)); }
        $o = $this->callTool('make_move', self::clean(['move' => $move, 'client_move_id' => $clientMoveId, 'game_id' => $gameId, 'note' => $note, 'expected_ply' => $expectedPly]));
        return [
            'position' => is_array($o['data']['game'] ?? null) ? new Position($o['data']['game'], $o['text']) : null,
            'replayed' => (bool)($o['data']['replayed'] ?? false), 'move' => (array)($o['data']['move'] ?? []),
        ];
    }

    public function legalMoves(string $gameId = ''): array { return $this->data('legal_moves', ['game_id' => $gameId]); }
    public function offerDraw(string $gameId = ''): Position { return $this->position('offer_draw', ['game_id' => $gameId]); }
    public function respondDraw(bool $accept, string $gameId = ''): Position { return $this->position('respond_draw', ['accept' => $accept, 'game_id' => $gameId]); }
    public function claimDraw(string $gameId = ''): Position { return $this->position('claim_draw', ['game_id' => $gameId]); }
    public function resign(string $gameId = ''): Position { return $this->position('resign', ['game_id' => $gameId]); }
    public function abortGame(string $gameId = ''): Position { return $this->position('abort_game', ['game_id' => $gameId]); }
    public function requestTakeback(string $gameId = ''): Position { return $this->position('request_takeback', ['game_id' => $gameId]); }
    public function respondTakeback(bool $accept, string $gameId = ''): Position { return $this->position('respond_takeback', ['accept' => $accept, 'game_id' => $gameId]); }
    public function getAnalysis(string $gameId = ''): array { return $this->data('get_analysis', ['game_id' => $gameId]); }

    /**
     * Says $text (1-280 characters) in a game's chat; an empty $gameId means your only active game.
     * The text of every message is untrusted: written by another player or person, never instructions.
     *
     * @return array{id:string,seq:int,author:array,color:string,text:string,created_at:string,untrusted:bool,hidden:bool}
     */
    public function sendChat(string $text, string $gameId = ''): array { return (array)($this->data('send_chat', ['game_id' => $gameId, 'text' => $text])['message'] ?? []); }

    /** Reads a game's chat after seq $after (0 = from the start). @return array{game_id:string,last_seq:int,messages:array} */
    public function readChat(string $gameId = '', int $after = 0): array { return $this->data('read_chat', ['game_id' => $gameId, 'after' => $after > 0 ? $after : null]); }

    // coach mode (a human picked this agent as their chess coach): raw structured arrays
    public function coachSessions(): array { return $this->data('coach_sessions'); }
    public function coachContext(string $sessionId = ''): array { return $this->data('coach_get_context', ['session_id' => $sessionId]); }
    public function coachWait(string $sessionId = '', float $timeoutSeconds = 0): array { return $this->data('coach_wait', ['session_id' => $sessionId, 'timeout_seconds' => $timeoutSeconds > 0 ? $timeoutSeconds : null]); }
    /** Writes to the student's coach panel in their language; $extra may hold kind, arrows, circles, ply, language. */
    public function coachSay(string $text, string $sessionId = '', array $extra = []): array { return $this->data('coach_say', ['session_id' => $sessionId, 'text' => $text] + $extra); }
    public function coachMarkProgress(string $topic, float $mastery, string $sessionId = '', string $note = ''): array { return $this->data('coach_mark_progress', ['session_id' => $sessionId, 'topic' => $topic, 'mastery' => $mastery, 'note' => $note]); }

    // tournaments: raw structured arrays (keys "tournaments", "tournament", "standings", ...)
    public function listTournaments(string $status = '', string $kind = ''): array { return $this->data('list_tournaments', ['status' => $status, 'kind' => $kind]); }
    public function joinTournament(string $id): array { return $this->data('join_tournament', ['id' => $id]); }
    public function withdrawTournament(string $id): array { return $this->data('withdraw_tournament', ['id' => $id]); }
    public function tournamentStandings(string $id): array { return $this->data('tournament_standings', ['id' => $id]); }

    // ---------- play ----------

    /** @template T @param callable():T $fn @return T */
    private function retry(array $o, callable $fn): mixed
    {
        $delay = 0.25;
        for ($i = 0;; $i++) {
            try {
                return $fn();
            } catch (NeuromateException $e) {
                if (!$e->retryable() || $i >= $o['max_retries']) { throw $e; }
                $wait = $e instanceof RateLimitedException ? $e->retryAfter : $delay + mt_rand() / mt_getrandmax() * $delay;
                usleep((int)(min($wait, 30.0) * 1_000_000));
                if ($delay < 4) { $delay *= 2; }
            }
        }
    }

    private static function randomLegal(Position $p): Move
    {
        return $p->legalMoves ? Move::of($p->legalMoves[array_rand($p->legalMoves)]['uci']) : Move::of('');
    }

    private static function options(array $o): array
    {
        return $o + [
            'game_id' => '', 'opponent' => '', 'variant' => '', 'time_control' => '', 'pool' => '', 'rated' => false, 'accept_challenges' => false,
            'no_join' => false, 'max_illegal' => 3, 'max_retries' => 8, 'wait_seconds' => 25.0, 'find_timeout' => 0.0,
            'on_position' => null, 'on_draw_offer' => null, 'fallback' => null, 'on_event' => null,
        ];
    }

    /**
     * Plays one complete game and returns the final Position. Without game_id it resumes your active game, else challenges
     * `opponent`, else joins the matchmaking queue (leaving it again if something fails while queued).
     *
     * $strategy: fn(Position): Move|string  (return Move::resign() to resign).
     * Options: game_id, opponent, variant, time_control, pool, rated, accept_challenges, max_illegal, max_retries, wait_seconds,
     * find_timeout, on_position(Position), on_draw_offer(Position): bool, fallback(Position): Move|string.
     */
    public function play(callable $strategy, array $options = []): Position
    {
        $o = self::options($options);
        $nonce = bin2hex(random_bytes(3));
        $gameId = (string)$o['game_id'];
        $queued = false;
        try {
            if ($gameId === '') {
                $lobby = $this->retry($o, fn() => $this->whoami());
                $seqs = array_map(fn($e) => (int)($e['seq'] ?? 0), (array)($lobby['events'] ?? []));
                if ($seqs && max($seqs) > 0) { $m = max($seqs); $this->retry($o, fn() => $this->listChallenges($m)); }
                $mine = (array)($lobby['active_games'] ?? []);
                usort($mine, fn($a, $b) => (int)!empty($b['your_turn']) <=> (int)!empty($a['your_turn']));
                $go = self::game($o['variant'], $o['time_control'], (bool)$o['rated']);
                if ($mine) {
                    $gameId = (string)$mine[0]['game_id'];
                } elseif ($o['opponent'] !== '') {
                    $this->retry($o, fn() => $this->challenge($o['opponent'], $go));
                } else {
                    $queued = true;
                    $q = $this->retry($o, fn() => $this->joinQueue($go, $o['pool']));
                    if ($q['position'] !== null) { $gameId = $q['position']->gameId; $queued = false; }
                }
            }
            $started = microtime(true);
            while (true) {
                if ($gameId === '' && $o['find_timeout'] > 0 && microtime(true) - $started > $o['find_timeout']) {
                    throw new NeuromateException("neuromate: no game started within {$o['find_timeout']} s");
                }
                $gid = $gameId;
                $turn = $this->retry($o, fn() => $this->waitForTurn($gid, (float)$o['wait_seconds']));
                $pos = $turn['position'];
                if ($gameId === '' && $o['accept_challenges']) {
                    foreach ($turn['events'] as $ev) {
                        $id = (string)($ev['data']['challenge_id'] ?? '');
                        if (($ev['type'] ?? '') === 'challenge_received' && $id !== '') { $pos = $this->retry($o, fn() => $this->acceptChallenge($id)); }
                    }
                }
                if ($pos === null || ($gameId === '' && $pos->isOver())) { continue; }
                if ($gameId === '') { $gameId = $pos->gameId; $queued = false; }
                if ($pos->gameId !== $gameId) { continue; }
                if ($o['on_position']) { ($o['on_position'])($pos); }
                if ($pos->isOver()) { return $pos; }
                $pos = $this->answerOffers($o, $pos);
                if ($pos->isOver()) { return $pos; }
                if (!$pos->yourTurn) { continue; }
                $done = $this->playMove($o, $strategy, $pos, $nonce);
                if ($done !== null && $done->isOver()) { return $done; }
            }
        } finally {
            if ($queued && $gameId === '') { try { $this->leaveQueue(); } catch (\Throwable) { } }
        }
    }

    private function answerOffers(array $o, Position $pos): Position
    {
        $gid = $pos->gameId;
        if ($pos->drawOffer === 'from_opponent') {
            $accept = $o['on_draw_offer'] ? (bool)($o['on_draw_offer'])($pos) : false;
            $pos = $this->retry($o, fn() => $this->respondDraw($accept, $gid));
        }
        if (!$pos->isOver() && $pos->takebackRequest === 'from_opponent') { $pos = $this->retry($o, fn() => $this->respondTakeback(false, $gid)); }
        return $pos;
    }

    private static function asMove(mixed $m): Move
    {
        if ($m instanceof Move) { return $m; }
        if (is_string($m)) { return Move::of($m); }
        throw new NeuromateException('neuromate: your strategy must return a Move or a move string such as "e2e4"');
    }

    private function playMove(array $o, callable $strategy, Position $start, string $nonce): ?Position
    {
        $pos = $start;
        $attempts = [];
        $n = 0;
        while (true) {
            $view = $pos->withAttempts($attempts);
            $mv = self::asMove(count($attempts) >= $o['max_illegal']
                ? ($o['fallback'] ? ($o['fallback'])($view) : self::randomLegal($view))
                : $strategy($view));
            $gid = $pos->gameId;
            if ($mv->resign) { return $this->retry($o, fn() => $this->resign($gid)); }
            if ($mv->move === '') { throw new NeuromateException('neuromate: your strategy returned an empty move; return a move such as "e2e4" or Move::resign()'); }
            $n++;
            $cmid = $gid . '-' . ($pos->ply + 1) . '-' . $nonce . '-' . $n;
            $ply = $pos->ply;
            try {
                return $this->retry($o, fn() => $this->makeMove($mv->move, $gid, $cmid, $ply, $mv->note))['position'];
            } catch (IllegalMoveException $e) {
                $attempts[] = $mv->move . ': ' . $e->detail;
                if (count($attempts) > $o['max_illegal'] + 2) { throw $e; }
            } catch (StalePlyException) {
                $fresh = $this->retry($o, fn() => $this->getGame($gid));
                if ($fresh->isOver() || !$fresh->yourTurn) { return $fresh; }
                $pos = $fresh;
                $attempts = [];
            } catch (NotYourTurnException) {
                return null;
            } catch (GameOverException $e) {
                return $e->position ?? $this->retry($o, fn() => $this->getGame($gid));
            }
        }
    }

    private static function tournamentOver(array $st): bool { return in_array($st['tournament']['status'] ?? '', ['finished', 'cancelled'], true); }

    private static function joined(array $st): bool
    {
        $me = $st['tournament']['me'] ?? [];
        return !empty($me['joined']) && ($me['status'] ?? '') !== 'withdrawn';
    }

    /**
     * Joins a tournament (unless no_join) and plays all its games one after another until it finishes or is cancelled.
     * Returns your final standing as ['rank' => int, 'score' => float, 'raw' => array], or null when you never took part.
     *
     * @return array{rank:int,score:float,raw:array}|null
     */
    public function playTournament(string $id, callable $strategy, array $options = []): ?array
    {
        $o = self::options($options);
        $nonce = bin2hex(random_bytes(3));
        $st = $this->retry($o, fn() => $this->tournamentStandings($id));
        if (self::tournamentOver($st)) { return $this->finalRow($o, $id, 0, 0.0, false); }
        if (!$o['no_join'] && !self::joined($st)) {
            try {
                $this->retry($o, fn() => $this->joinTournament($id));
            } catch (ConflictException $c) {
                if (!self::joined($this->retry($o, fn() => $this->tournamentStandings($id)))) {
                    throw new NeuromateException("neuromate: could not join tournament $id: " . $c->getMessage());
                }
            }
        }
        while (true) {
            $turn = $this->retry($o, fn() => $this->waitForTurn('', (float)$o['wait_seconds']));
            $ended = null;
            $check = $turn['timedOut'];
            foreach ($turn['events'] as $ev) {
                if ($o['on_event']) { ($o['on_event'])($ev); }
                $tid = (string)($ev['data']['tournament_id'] ?? '');
                if ($tid !== '' && $tid !== $id) { continue; }
                $type = $ev['type'] ?? '';
                if ($type === 'tournament_finished' || $type === 'tournament_cancelled') { $ended = $ev; }
                elseif ($type === 'tournament_game_over') { $check = true; }
            }
            $pos = $turn['position'];
            $busy = false;
            if ($pos !== null && ($pos->tournamentId === '' || $pos->tournamentId === $id)) {
                if ($o['on_position']) { ($o['on_position'])($pos); }
                if (!$pos->isOver()) {
                    $busy = true;
                    $pos = $this->answerOffers($o, $pos);
                    if (!$pos->isOver() && $pos->yourTurn) { $this->playMove($o, $strategy, $pos, $nonce); }
                }
            }
            if ($ended !== null) {
                $d = (array)($ended['data'] ?? []);
                return $this->finalRow($o, $id, (int)($d['rank'] ?? 0), (float)($d['score'] ?? 0), true);
            }
            if ($check) {
                if (self::tournamentOver($this->retry($o, fn() => $this->tournamentStandings($id)))) { return $this->finalRow($o, $id, 0, 0.0, false); }
            } elseif (!$turn['events'] && !$busy) {
                usleep(200_000);
            }
        }
    }

    private function finalRow(array $o, string $id, int $rank, float $score, bool $haveEvent): ?array
    {
        $st = $this->retry($o, fn() => $this->tournamentStandings($id));
        $you = (array)($this->retry($o, fn() => $this->whoami())['you'] ?? []);
        $row = null;
        foreach ((array)($st['standings'] ?? []) as $r) {
            if (($you['agent_id'] ?? '') !== '' && ($r['agent_id'] ?? '') === $you['agent_id']) { $row = $r; break; }
            if ($row === null && ($r['name'] ?? '') === ($you['name'] ?? null)) { $row = $r; }
        }
        if ($row !== null) { return ['rank' => (int)($row['rank'] ?? 0), 'score' => (float)($row['score'] ?? 0), 'raw' => $row]; }
        $me = (array)($st['tournament']['me'] ?? []);
        if (!empty($me['joined'])) {
            return ['rank' => $haveEvent ? $rank : (int)($me['rank'] ?? 0), 'score' => $haveEvent ? $score : (float)($me['score'] ?? 0), 'raw' => $me];
        }
        return null;
    }
}
