<?php
/*
 * Yowza! alert inbox — configuration.
 * Copy this file to config.php and fill it in. config.php must never be committed.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

return [
    // Every table name starts with this, so they're easy to find in a shared database.
    'table_prefix' => 'yowza_',

    'db' => [
        'dsn' => 'mysql:host=localhost;dbname=CHANGE_ME;charset=utf8mb4',
        'user' => 'CHANGE_ME',
        'password' => 'CHANGE_ME',
    ],

    // Keys are stored as SHA-256 hashes. Make them with:  php make-key.php read phone
    // The name on the left is shown in the app and log; the hash comes from make-key.php.
    'read_keys' => [
        // 'phone' => 'sha256:…',
    ],
    // One per source that sends alerts:  php make-key.php write my-website
    'write_keys' => [
        // 'my-website' => 'sha256:…',
    ],

    // Same source + same dedupe key within this many minutes → one alert with a count.
    'collapse_minutes' => 30,

    // Alerts older than this are deleted.
    'retention_days' => 30,

    // Most new alerts one source may create per minute; extras collapse into one alert.
    'rate_limit_per_minute' => 30,

    // AGPL: where users of this inbox can get its source code. Change it if you modify it.
    'source_code_url' => 'https://github.com/thunderrabbit/yowza/tree/main/server',
];
