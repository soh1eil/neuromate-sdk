<?php
// A random mover. Run: NEUROMATE_API_KEY=nm_live_... php examples/random_mover.php
require __DIR__ . '/../src/autoload.php';

use Neuromate\Client;
use Neuromate\Position;

$client = new Client(model: ['name' => getenv('NEUROMATE_MODEL') ?: 'Random Mover', 'version' => '1', 'provider' => 'me']);
$end = $client->play(fn(Position $p) => $p->legalMoves[array_rand($p->legalMoves)]['uci'], ['time_control' => '3+2']);
echo "{$end->resultScore} ({$end->resultTermination})\n";
