<?php
/*
 * Yowza! alert inbox — shared code: config, database, keys, collapsing, pruning.
 * Copyright (C) 2026 Rob Nugen
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU Affero General Public License as published by the Free Software Foundation,
 * version 3. It is distributed WITHOUT ANY WARRANTY; see the LICENSE file in this folder.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

declare(strict_types=1);

const YZ_MAX_TITLE = 255;
const YZ_MAX_BODY = 1000;
const YZ_LIST_LIMIT = 100;

/** Settings from config.php next to this file. Nothing depends on the folder's name. */
function yz_config(): array
{
    static $cfg = null;
    if ($cfg === null) {
        $file = __DIR__ . '/config.php';
        if (!is_file($file)) {
            throw new RuntimeException('Not configured: copy config.example.php to config.php');
        }
        $cfg = require $file;
        $prefix = (string)($cfg['table_prefix'] ?? 'yowza_');
        if (!preg_match('/^[A-Za-z0-9_]*$/', $prefix)) {
            throw new RuntimeException('table_prefix may only contain letters, digits and _');
        }
        $cfg['table_prefix'] = $prefix;
    }
    return $cfg;
}

/** Full table name, e.g. yz_table('alerts') → `yowza_alerts`. */
function yz_table(string $name): string
{
    return '`' . yz_config()['table_prefix'] . $name . '`';
}

function yz_db(): PDO
{
    static $db = null;
    if ($db === null) {
        $c = yz_config()['db'];
        $db = new PDO($c['dsn'], $c['user'], $c['password'], [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]);
        $db->exec("SET time_zone = '+00:00'");
    }
    return $db;
}

/** The SQL that creates the tables, using the configured prefix. Used by install.php. */
function yz_schema_sql(): array
{
    $alerts = yz_table('alerts');
    $seq = yz_table('seq');
    return [
        "CREATE TABLE IF NOT EXISTS $alerts (
            id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
            seq BIGINT UNSIGNED NOT NULL,
            source VARCHAR(64) NOT NULL,
            title VARCHAR(255) NOT NULL,
            body VARCHAR(1000) NOT NULL DEFAULT '',
            priority VARCHAR(10) NOT NULL DEFAULT 'normal',
            dedupe_hash CHAR(64) NOT NULL,
            repeat_count INT UNSIGNED NOT NULL DEFAULT 1,
            first_seen_at DATETIME NOT NULL,
            last_seen_at DATETIME NOT NULL,
            UNIQUE KEY seq (seq),
            KEY collapse (source, dedupe_hash, last_seen_at),
            KEY source_first (source, first_seen_at),
            KEY last_seen (last_seen_at)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci",
        // One-row counter. Every insert or collapse takes the next number, so a phone that
        // remembers the last number it saw can ask for "everything changed since".
        "CREATE TABLE IF NOT EXISTS $seq (
            id TINYINT UNSIGNED NOT NULL PRIMARY KEY,
            value BIGINT UNSIGNED NOT NULL
        ) ENGINE=InnoDB",
        "INSERT IGNORE INTO $seq (id, value) VALUES (1, 0)",
    ];
}

// --- Keys -----------------------------------------------------------------------------

/** "sha256:<hex>" — the form stored in config.php. */
function yz_hash_key(string $key): string
{
    return 'sha256:' . hash('sha256', $key);
}

/** The key sent with the request: Authorization: Bearer …, or X-Yowza-Key: … */
function yz_request_key(): ?string
{
    $auth = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if ($auth === '' && function_exists('getallheaders')) {
        foreach (getallheaders() as $name => $value) {
            if (strcasecmp($name, 'Authorization') === 0) {
                $auth = $value;
            }
        }
    }
    if (preg_match('/^\s*Bearer\s+(\S+)\s*$/i', $auth, $m)) {
        return $m[1];
    }
    $alt = trim((string)($_SERVER['HTTP_X_YOWZA_KEY'] ?? ''));
    return $alt === '' ? null : $alt;
}

/**
 * Name of the key that made this request, for $kind 'read' or 'write', or null.
 * For writes, that name is the alert's source, so a source can't claim to be another.
 */
function yz_authenticate(string $kind): ?string
{
    $key = yz_request_key();
    if ($key === null) {
        return null;
    }
    $hash = yz_hash_key($key);
    foreach ((yz_config()[$kind . '_keys'] ?? []) as $name => $stored) {
        if (is_string($stored) && hash_equals($stored, $hash)) {
            return (string)$name;
        }
    }
    return null;
}

// --- Alerts ---------------------------------------------------------------------------

function yz_cut(string $s, int $max): string
{
    $s = trim($s);
    return function_exists('mb_substr') ? mb_substr($s, 0, $max) : substr($s, 0, $max);
}

/** Take the next sequence number. Must run inside the caller's transaction. */
function yz_next_seq(PDO $db): int
{
    $db->exec('UPDATE ' . yz_table('seq') . ' SET value = LAST_INSERT_ID(value + 1) WHERE id = 1');
    return (int)$db->query('SELECT LAST_INSERT_ID()')->fetchColumn();
}

function yz_current_seq(PDO $db): int
{
    return (int)$db->query('SELECT value FROM ' . yz_table('seq') . ' WHERE id = 1')->fetchColumn();
}

/**
 * Add an alert from $source, or collapse it into a recent identical one.
 * Returns ['id' => …, 'count' => …, 'collapsed' => bool].
 */
function yz_add_alert(PDO $db, string $source, string $title, string $body, string $priority, ?string $dedupeKey): array
{
    $cfg = yz_config();
    $title = yz_cut($title, YZ_MAX_TITLE);
    $body = yz_cut($body, YZ_MAX_BODY);
    $priority = $priority === 'high' ? 'high' : 'normal';
    $collapseMinutes = max(1, (int)($cfg['collapse_minutes'] ?? 30));
    $limit = max(1, (int)($cfg['rate_limit_per_minute'] ?? 30));
    $alerts = yz_table('alerts');

    $db->beginTransaction();
    try {
        $result = yz_collapse_or_insert(
            $db, $alerts, $source, $title, $body, $priority,
            hash('sha256', $dedupeKey !== null && $dedupeKey !== '' ? $dedupeKey : $title),
            $collapseMinutes, $limit, false
        );
        $db->commit();
    } catch (Throwable $e) {
        $db->rollBack();
        throw $e;
    }
    yz_prune($db);
    return $result;
}

function yz_collapse_or_insert(
    PDO $db, string $alerts, string $source, string $title, string $body, string $priority,
    string $dedupeHash, int $collapseMinutes, int $limit, bool $isOverflow
): array {
    // Same source + same dedupe key recently? Bump that alert instead of adding one.
    $find = $db->prepare(
        "SELECT id, repeat_count FROM $alerts
         WHERE source = ? AND dedupe_hash = ? AND last_seen_at >= UTC_TIMESTAMP() - INTERVAL ? MINUTE
         ORDER BY id DESC LIMIT 1 FOR UPDATE"
    );
    $find->execute([$source, $dedupeHash, $collapseMinutes]);
    $existing = $find->fetch();
    if ($existing) {
        $seq = yz_next_seq($db);
        $db->prepare(
            "UPDATE $alerts SET repeat_count = repeat_count + 1, last_seen_at = UTC_TIMESTAMP(),
                seq = ?, body = ?, priority = IF(? = 'high', 'high', priority)
             WHERE id = ?"
        )->execute([$seq, $body, $priority, $existing['id']]);
        return ['id' => (int)$existing['id'], 'count' => (int)$existing['repeat_count'] + 1, 'collapsed' => true];
    }

    // A runaway source: fold anything past the per-minute limit into one overflow alert.
    if (!$isOverflow) {
        $recent = $db->prepare(
            "SELECT COUNT(*) FROM $alerts WHERE source = ? AND first_seen_at >= UTC_TIMESTAMP() - INTERVAL 1 MINUTE"
        );
        $recent->execute([$source]);
        if ((int)$recent->fetchColumn() >= $limit) {
            return yz_collapse_or_insert(
                $db, $alerts, $source,
                yz_cut("Too many alerts from $source", YZ_MAX_TITLE),
                yz_cut("Latest: $title", YZ_MAX_BODY),
                'high', hash('sha256', '__yowza_rate_limited__'), $collapseMinutes, $limit, true
            );
        }
    }

    $seq = yz_next_seq($db);
    $db->prepare(
        "INSERT INTO $alerts (seq, source, title, body, priority, dedupe_hash, repeat_count, first_seen_at, last_seen_at)
         VALUES (?, ?, ?, ?, ?, ?, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP())"
    )->execute([$seq, $source, $title, $body, $priority, $dedupeHash]);
    return ['id' => (int)$db->lastInsertId(), 'count' => 1, 'collapsed' => false];
}

/**
 * Alerts changed after cursor $after (oldest change first), plus the cursor to use next.
 * Without $after, returns no alerts and the current cursor, so a new phone starts fresh.
 */
function yz_list(PDO $db, ?int $after): array
{
    if ($after === null) {
        return ['cursor' => yz_current_seq($db), 'alerts' => [], 'more' => false];
    }
    $stmt = $db->prepare(
        "SELECT id, seq, source, title, body, priority, repeat_count,
                DATE_FORMAT(first_seen_at, '%Y-%m-%dT%H:%i:%sZ') AS first_seen_at,
                DATE_FORMAT(last_seen_at, '%Y-%m-%dT%H:%i:%sZ') AS last_seen_at
         FROM " . yz_table('alerts') . " WHERE seq > ? ORDER BY seq LIMIT " . (YZ_LIST_LIMIT + 1)
    );
    $stmt->execute([$after]);
    $rows = $stmt->fetchAll();
    $more = count($rows) > YZ_LIST_LIMIT;
    $rows = array_slice($rows, 0, YZ_LIST_LIMIT);

    $alerts = array_map(static fn(array $r): array => [
        'id' => (int)$r['id'],
        'seq' => (int)$r['seq'],
        'source' => $r['source'],
        'title' => $r['title'],
        'body' => $r['body'],
        'priority' => $r['priority'],
        'count' => (int)$r['repeat_count'],
        'first_seen_at' => $r['first_seen_at'],
        'last_seen_at' => $r['last_seen_at'],
    ], $rows);
    $cursor = $alerts ? end($alerts)['seq'] : $after;
    return ['cursor' => $cursor, 'alerts' => $alerts, 'more' => $more];
}

/** Delete alerts older than retention_days. */
function yz_prune(PDO $db): void
{
    $days = max(1, (int)(yz_config()['retention_days'] ?? 30));
    $db->prepare('DELETE FROM ' . yz_table('alerts') . ' WHERE last_seen_at < UTC_TIMESTAMP() - INTERVAL ? DAY')
        ->execute([$days]);
}

// --- HTTP -----------------------------------------------------------------------------

/** Send JSON and stop. Every response links to the source code (AGPL §13). */
function yz_respond(int $status, array $data): never
{
    $source = 'https://github.com/thunderrabbit/yowza/tree/main/server';
    try {
        $source = (string)(yz_config()['source_code_url'] ?? $source);
    } catch (Throwable) {
        // Not configured yet: keep the default link.
    }
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Source-Code: ' . $source);
    $data['source_code'] = $source;
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}
