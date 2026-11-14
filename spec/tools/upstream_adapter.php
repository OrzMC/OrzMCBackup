<?php
/**
 * Conformance adapter for the upstream PHP reference implementation
 * (`upstream/thanos`, aternosorg/thanos).
 *
 * The upstream CLI (`thanos.php`) hardcodes a threshold of 0 and offers no options, so the
 * harness drives the *library* directly instead. This is the only implementation-specific
 * code in the conformance pipeline: the manifest is still derived by the neutral
 * `spec/tools/manifest.py` from the produced world.
 *
 * Usage:
 *   php spec/tools/upstream_adapter.php --world DIR --out DIR --params params.json
 *
 * Exit codes: 0 = run completed, 1 = usage error, 2 = upstream threw.
 */

declare(strict_types=1);

require __DIR__ . '/../../upstream/thanos/vendor/autoload.php';

use Aternos\IO\System\Directory\Directory;
use Aternos\Thanos\Pattern\Factory\ForceLoadedChunkPatternFactory;
use Aternos\Thanos\Pattern\InhabitedTimePattern;
use Aternos\Thanos\Thanos;
use Aternos\Thanos\World\World;

function usage(string $message): void
{
    fwrite(STDERR, $message . PHP_EOL);
    fwrite(STDERR, "usage: upstream_adapter.php --world DIR --out DIR --params params.json" . PHP_EOL);
    exit(1);
}

$options = getopt('', ['world:', 'out:', 'params:']);
foreach (['world', 'out', 'params'] as $required) {
    if (!isset($options[$required])) {
        usage("missing --$required");
    }
}

$worldPath = rtrim((string)$options['world'], '/');
$outPath = rtrim((string)$options['out'], '/');
$paramsPath = (string)$options['params'];

if (!is_dir($worldPath)) {
    usage("world directory not found: $worldPath");
}
$params = json_decode((string)file_get_contents($paramsPath), true);
if (!is_array($params)) {
    usage("cannot read params: $paramsPath");
}

// Vector params -> upstream library configuration.
$args = $params['args'] ?? [];
$thresholdSeconds = 0;
$removeUnknown = false;
foreach ($args as $i => $arg) {
    if ($arg === '--inhabited-time-seconds') {
        $thresholdSeconds = (int)($args[$i + 1] ?? 0);
    } elseif (str_starts_with((string)$arg, '--inhabited-time-seconds=')) {
        $thresholdSeconds = (int)substr((string)$arg, strlen('--inhabited-time-seconds='));
    } elseif ($arg === '--remove-unknown') {
        $removeUnknown = true;
    }
}
$ticks = $thresholdSeconds * 20;

if (!is_dir($outPath) && !mkdir($outPath, 0777, true) && !is_dir($outPath)) {
    usage("cannot create output directory: $outPath");
}

$patterns = [
    new ForceLoadedChunkPatternFactory(),
    new InhabitedTimePattern($ticks, $removeUnknown),
];

try {
    $thanos = new Thanos($patterns);
    $removed = $thanos->snap(World::open($worldPath), new Directory($outPath));
    fwrite(STDERR, sprintf("upstream: removed %d chunks (threshold=%ds)\n", $removed, $thresholdSeconds));
} catch (Throwable $e) {
    fwrite(STDERR, 'upstream threw: ' . $e::class . ': ' . $e->getMessage() . PHP_EOL);
    exit(2);
}

exit(0);
