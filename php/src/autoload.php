<?php
declare(strict_types=1);

// Without Composer: require 'path/to/sdk/php/src/autoload.php';
spl_autoload_register(function (string $class): void {
    if (!str_starts_with($class, 'Neuromate\\')) { return; }
    $rel = substr($class, 10);
    foreach (['Errors', 'Position', 'Move', 'Client'] as $f) { require_once __DIR__ . "/$f.php"; }
});
