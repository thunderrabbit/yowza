<?php
/*
 * Yowza! alert inbox — make a new key (command line only).
 *
 *   php make-key.php read  phone        a key the app uses to fetch alerts
 *   php make-key.php write my-website   a key a source uses to send alerts
 *
 * Prints the key (give it to the app or the sending script) and a line to paste into
 * config.php under read_keys or write_keys. config.php only ever holds the hash.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

require __DIR__ . '/lib.php';

$kind = $argv[1] ?? '';
$name = $argv[2] ?? '';
if (!in_array($kind, ['read', 'write'], true) || !preg_match('/^[A-Za-z0-9._-]{1,64}$/', $name)) {
    fwrite(STDERR, "Usage: php make-key.php read|write <name>\n");
    fwrite(STDERR, "  name: letters, digits, . _ - (up to 64). For write keys it's the alert's source.\n");
    exit(1);
}

$key = 'yz_' . rtrim(strtr(base64_encode(random_bytes(32)), '+/', '-_'), '=');

echo "Key (save it now; it can't be shown again):\n\n  $key\n\n";
echo "Paste into config.php under '{$kind}_keys':\n\n";
echo "  '$name' => '" . yz_hash_key($key) . "',\n";
