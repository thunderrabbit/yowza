<?php
/*
 * Yowza! alert inbox — create the tables (command line only).
 *
 *   php install.php           create the tables, named with table_prefix from config.php
 *   php install.php --print   print the SQL instead (e.g. to paste into phpMyAdmin)
 *
 * Safe to run again: existing tables and alerts are left alone.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    http_response_code(404);
    exit;
}

require __DIR__ . '/lib.php';

try {
    $statements = yz_schema_sql();
    if (in_array('--print', $argv, true)) {
        foreach ($statements as $sql) {
            echo preg_replace('/^ {8}/m', '', $sql), ";\n\n";
        }
        exit(0);
    }
    $db = yz_db();
    foreach ($statements as $sql) {
        $db->exec($sql);
    }
    $prefix = yz_config()['table_prefix'];
    echo "Done. Tables: {$prefix}alerts, {$prefix}seq\n";
    echo "Next: php make-key.php read phone   and   php make-key.php write <source-name>\n";
} catch (Throwable $e) {
    fwrite(STDERR, 'Error: ' . $e->getMessage() . "\n");
    exit(1);
}
