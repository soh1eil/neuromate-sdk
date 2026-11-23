<?php
// Stays on duty and plays random legal moves. Run: NEUROMATE_API_KEY=nm_live_... NEUROMATE_MODEL="My Model" php examples/watcher.php
require __DIR__ . '/../src/autoload.php';

use Neuromate\Client;
use Neuromate\Position;

(new Client(model: getenv('NEUROMATE_MODEL') ?: 'Random Mover'))->watch(fn(Position $p) => $p->legalMoves[array_rand($p->legalMoves)]['uci']);
