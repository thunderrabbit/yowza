<?php
/*
 * Yowza! alert inbox — the only web-facing file.
 *
 *   POST  add an alert          (write key; JSON or form fields: title, body, priority, dedupe_key)
 *   GET   ?after=<cursor>       alerts changed since the cursor (read key)
 *   GET   (no after)            just the current cursor, so a new phone starts fresh
 *
 * Keys: "Authorization: Bearer <key>" or "X-Yowza-Key: <key>".
 *
 * Copyright (C) 2026 Rob Nugen
 * SPDX-License-Identifier: AGPL-3.0-only
 */

declare(strict_types=1);

require __DIR__ . '/lib.php';

try {
    $method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

    if ($method === 'POST') {
        $source = yz_authenticate('write');
        if ($source === null) {
            yz_respond(401, ['error' => 'A valid write key is required.']);
        }
        $input = $_POST;
        $type = $_SERVER['CONTENT_TYPE'] ?? '';
        if (stripos($type, 'application/json') !== false) {
            $decoded = json_decode((string)file_get_contents('php://input'), true);
            if (!is_array($decoded)) {
                yz_respond(400, ['error' => 'Body is not valid JSON.']);
            }
            $input = $decoded;
        }
        $title = trim((string)($input['title'] ?? ''));
        if ($title === '') {
            yz_respond(400, ['error' => 'title is required.']);
        }
        $result = yz_add_alert(
            yz_db(),
            $source,
            $title,
            (string)($input['body'] ?? ''),
            (string)($input['priority'] ?? 'normal'),
            isset($input['dedupe_key']) ? (string)$input['dedupe_key'] : null
        );
        yz_respond($result['collapsed'] ? 200 : 201, ['ok' => true, 'source' => $source] + $result);
    }

    if ($method === 'GET') {
        if (yz_authenticate('read') === null) {
            yz_respond(401, ['error' => 'A valid read key is required.']);
        }
        $after = $_GET['after'] ?? null;
        if ($after !== null && !preg_match('/^\d{1,19}$/', (string)$after)) {
            yz_respond(400, ['error' => 'after must be a whole number.']);
        }
        yz_respond(200, yz_list(yz_db(), $after === null ? null : (int)$after));
    }

    header('Allow: GET, POST');
    yz_respond(405, ['error' => 'Use GET or POST.']);
} catch (Throwable $e) {
    // Don't leak details (database names, paths) to the caller; log them for the owner.
    error_log('Yowza inbox: ' . $e->getMessage());
    yz_respond(500, ['error' => 'Server error. Details are in the web server error log.']);
}
