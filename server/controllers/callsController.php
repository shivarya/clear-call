<?php

require_once __DIR__ . '/../utils/livekit.php';
require_once __DIR__ . '/../utils/fcm.php';

function handleCallsRoutes($uri, $method)
{
  reapStaleRinging();

  if ($uri === '/calls' && $method === 'POST') {
    createCall();
  } elseif ($uri === '/calls' && $method === 'GET') {
    listCalls();
  } elseif (preg_match('#^/calls/(\d+)/(answer|decline|cancel|end)$#', $uri, $m) && $method === 'POST') {
    $callId = (int)$m[1];
    switch ($m[2]) {
      case 'answer':
        answerCall($callId);
        break;
      case 'decline':
        declineCall($callId);
        break;
      case 'cancel':
        cancelCall($callId);
        break;
      case 'end':
        endCall($callId);
        break;
    }
  } else {
    Response::error('Route not found', 404);
  }
}

/** Lazy cleanup: any ring older than timeout+grace that nobody resolved becomes missed. */
function reapStaleRinging(): void
{
  try {
    $grace = RING_TIMEOUT_SECONDS + 45;
    getDB()->execute(
      "UPDATE calls SET status = 'missed', end_reason = 'reaped', ended_at = NOW()
       WHERE status = 'ringing' AND created_at < DATE_SUB(NOW(), INTERVAL ? SECOND)",
      [$grace]
    );
  } catch (Exception $e) {
    error_log('reapStaleRinging: ' . $e->getMessage());
  }
}

/** Is this user currently in an open call (fresh ring or answered-not-ended)? */
function findOpenCallForUser($db, int $userId)
{
  return $db->fetchOne(
    "SELECT id FROM calls
     WHERE (caller_id = ? OR callee_id = ?)
       AND (
         (status = 'ringing' AND created_at > DATE_SUB(NOW(), INTERVAL ? SECOND))
         OR (status = 'answered' AND ended_at IS NULL)
       )
     LIMIT 1",
    [$userId, $userId, RING_TIMEOUT_SECONDS + 15]
  );
}

/**
 * Start a call: busy checks -> call row -> mint caller's LiveKit token -> FCM ring.
 * The callee's token is minted at /answer, never shipped through FCM.
 */
function createCall()
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $callerId = (int)$tokenData['userId'];
    $input = getJsonInput();

    $calleeId = (int)($input['calleeUserId'] ?? 0);
    if ($calleeId <= 0) {
      Response::error('calleeUserId is required', 400);
      return;
    }
    if ($calleeId === $callerId) {
      Response::error('You cannot call yourself', 400);
      return;
    }

    $db = getDB();

    // Only mutual contacts can call each other.
    $isContact = $db->fetchOne(
      "SELECT id FROM contacts WHERE user_id = ? AND contact_user_id = ?",
      [$callerId, $calleeId]
    );
    if (!$isContact) {
      Response::error('Not in your contacts', 403);
      return;
    }

    $caller = $db->fetchOne("SELECT id, name FROM users WHERE id = ?", [$callerId]);
    $callee = $db->fetchOne("SELECT id, name FROM users WHERE id = ?", [$calleeId]);
    if (!$callee) {
      Response::error('Callee not found', 404);
      return;
    }

    if (findOpenCallForUser($db, $callerId)) {
      Response::error('You are already in a call', 409);
      return;
    }
    if (findOpenCallForUser($db, $calleeId)) {
      // Record the attempt for history, then report busy.
      $db->insert(
        "INSERT INTO calls (room_name, caller_id, callee_id, status, end_reason, ended_at)
         VALUES (?, ?, ?, 'busy', 'callee_busy', NOW())",
        ['busy-' . bin2hex(random_bytes(8)), $callerId, $calleeId]
      );
      Response::error('busy', 409);
      return;
    }

    // Callee must be reachable (at least one active device).
    $deviceCount = $db->fetchOne(
      "SELECT COUNT(*) AS n FROM devices WHERE user_id = ? AND is_active = 1",
      [$calleeId]
    );
    if ((int)($deviceCount['n'] ?? 0) === 0) {
      $db->insert(
        "INSERT INTO calls (room_name, caller_id, callee_id, status, end_reason, ended_at)
         VALUES (?, ?, ?, 'failed', 'no_device', NOW())",
        ['fail-' . bin2hex(random_bytes(8)), $callerId, $calleeId]
      );
      Response::error('Callee has no registered device', 409);
      return;
    }

    $roomName = 'call-' . bin2hex(random_bytes(8));
    $callId = $db->insert(
      "INSERT INTO calls (room_name, caller_id, callee_id, status) VALUES (?, ?, ?, 'ringing')",
      [$roomName, $callerId, $calleeId]
    );

    $callerToken = mintLivekitToken('u' . $callerId, $caller['name'], $roomName);

    $push = Fcm::sendToUser($calleeId, [
      'type' => 'ring',
      'callId' => $callId,
      'roomName' => $roomName,
      'callerId' => $callerId,
      'callerName' => $caller['name'],
    ], RING_TIMEOUT_SECONDS . 's');

    if (!$push['skipped'] && $push['sent'] === 0) {
      // Every push failed — the callee will never ring.
      $db->execute(
        "UPDATE calls SET status = 'failed', end_reason = 'push_failed', ended_at = NOW() WHERE id = ?",
        [$callId]
      );
      Response::error('Could not reach callee (push failed)', 502);
      return;
    }

    Response::success([
      'callId' => (int)$callId,
      'roomName' => $roomName,
      'livekitUrl' => LIVEKIT_URL,
      'token' => $callerToken,
      'ringTimeoutSeconds' => RING_TIMEOUT_SECONDS,
      'pushSkipped' => $push['skipped'],
    ], 'Ringing');
  } catch (Exception $e) {
    error_log('createCall error: ' . $e->getMessage());
    Response::error('Failed to create call', 500);
  }
}

function loadCallOr404($db, int $callId): array
{
  $call = $db->fetchOne("SELECT * FROM calls WHERE id = ?", [$callId]);
  if (!$call) {
    Response::error('Call not found', 404);
  }
  return $call;
}

function answerCall(int $callId)
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $userId = (int)$tokenData['userId'];
    $db = getDB();
    $call = loadCallOr404($db, $callId);

    if ((int)$call['callee_id'] !== $userId) {
      Response::error('Only the callee can answer', 403);
      return;
    }
    if ($call['status'] !== 'ringing') {
      Response::error('Call is no longer ringing (' . $call['status'] . ')', 410);
      return;
    }

    $db->execute("UPDATE calls SET status = 'answered', answered_at = NOW() WHERE id = ?", [$callId]);

    $callee = $db->fetchOne("SELECT id, name FROM users WHERE id = ?", [$userId]);
    $token = mintLivekitToken('u' . $userId, $callee['name'], $call['room_name']);

    Response::success([
      'callId' => $callId,
      'roomName' => $call['room_name'],
      'livekitUrl' => LIVEKIT_URL,
      'token' => $token,
    ], 'Answered');
  } catch (Exception $e) {
    error_log('answerCall error: ' . $e->getMessage());
    Response::error('Failed to answer call', 500);
  }
}

function declineCall(int $callId)
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $userId = (int)$tokenData['userId'];
    $db = getDB();
    $call = loadCallOr404($db, $callId);

    if ((int)$call['callee_id'] !== $userId) {
      Response::error('Only the callee can decline', 403);
      return;
    }
    if ($call['status'] !== 'ringing') {
      Response::success(null, 'Call already resolved (' . $call['status'] . ')');
      return;
    }

    $db->execute(
      "UPDATE calls SET status = 'declined', end_reason = 'declined', ended_at = NOW() WHERE id = ?",
      [$callId]
    );
    Fcm::sendToUser((int)$call['caller_id'], ['type' => 'declined', 'callId' => $callId], '30s');
    Response::success(null, 'Declined');
  } catch (Exception $e) {
    error_log('declineCall error: ' . $e->getMessage());
    Response::error('Failed to decline call', 500);
  }
}

/** Caller withdraws a ring: reason 'canceled' (hung up) or 'timeout' (nobody answered). */
function cancelCall(int $callId)
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $userId = (int)$tokenData['userId'];
    $input = getJsonInput();
    $reason = ($input['reason'] ?? 'canceled') === 'timeout' ? 'timeout' : 'canceled';

    $db = getDB();
    $call = loadCallOr404($db, $callId);

    if ((int)$call['caller_id'] !== $userId) {
      Response::error('Only the caller can cancel', 403);
      return;
    }
    if ($call['status'] !== 'ringing') {
      Response::success(null, 'Call already resolved (' . $call['status'] . ')');
      return;
    }

    $newStatus = $reason === 'timeout' ? 'missed' : 'canceled';
    $db->execute(
      "UPDATE calls SET status = ?, end_reason = ?, ended_at = NOW() WHERE id = ?",
      [$newStatus, $reason, $callId]
    );
    Fcm::sendToUser((int)$call['callee_id'], [
      'type' => 'cancel',
      'callId' => $callId,
      'missed' => $reason === 'timeout' ? '1' : '0',
      'callerName' => $db->fetchOne("SELECT name FROM users WHERE id = ?", [$call['caller_id']])['name'] ?? '',
    ], '60s');
    Response::success(null, ucfirst($newStatus));
  } catch (Exception $e) {
    error_log('cancelCall error: ' . $e->getMessage());
    Response::error('Failed to cancel call', 500);
  }
}

/** Either party hangs up an answered call. Idempotent. */
function endCall(int $callId)
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $userId = (int)$tokenData['userId'];
    $db = getDB();
    $call = loadCallOr404($db, $callId);

    if ((int)$call['caller_id'] !== $userId && (int)$call['callee_id'] !== $userId) {
      Response::error('Not your call', 403);
      return;
    }
    if ($call['status'] === 'ended') {
      Response::success(null, 'Already ended');
      return;
    }
    if ($call['status'] !== 'answered') {
      Response::success(null, 'Call already resolved (' . $call['status'] . ')');
      return;
    }

    $db->execute(
      "UPDATE calls SET status = 'ended', end_reason = 'hangup', ended_at = NOW() WHERE id = ?",
      [$callId]
    );
    Response::success(null, 'Ended');
  } catch (Exception $e) {
    error_log('endCall error: ' . $e->getMessage());
    Response::error('Failed to end call', 500);
  }
}

function listCalls()
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $userId = (int)$tokenData['userId'];
    $limit = min(200, max(1, (int)($_GET['limit'] ?? 50)));

    $db = getDB();
    $rows = $db->fetchAll(
      "SELECT c.id, c.status, c.end_reason, c.created_at, c.answered_at, c.ended_at,
              c.caller_id, c.callee_id,
              cu.name AS caller_name, ce.name AS callee_name
       FROM calls c
       JOIN users cu ON cu.id = c.caller_id
       JOIN users ce ON ce.id = c.callee_id
       WHERE c.caller_id = ? OR c.callee_id = ?
       ORDER BY c.created_at DESC
       LIMIT $limit",
      [$userId, $userId]
    );

    $calls = array_map(function ($r) use ($userId) {
      $outgoing = (int)$r['caller_id'] === $userId;
      return [
        'id' => (int)$r['id'],
        'direction' => $outgoing ? 'outgoing' : 'incoming',
        'peerId' => $outgoing ? (int)$r['callee_id'] : (int)$r['caller_id'],
        'peerName' => $outgoing ? $r['callee_name'] : $r['caller_name'],
        'status' => $r['status'],
        'endReason' => $r['end_reason'],
        'createdAt' => $r['created_at'],
        'answeredAt' => $r['answered_at'],
        'endedAt' => $r['ended_at'],
      ];
    }, $rows);

    Response::success($calls, 'Call history retrieved');
  } catch (Exception $e) {
    error_log('listCalls error: ' . $e->getMessage());
    Response::error('Failed to list calls', 500);
  }
}
