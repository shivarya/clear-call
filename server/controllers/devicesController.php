<?php

function handleDevicesRoutes($uri, $method)
{
  if ($uri === '/devices/register' && $method === 'POST') {
    registerDevice();
  } else {
    Response::error('Route not found', 404);
  }
}

/**
 * Upsert this user's FCM token. A token can migrate between users (sign-out /
 * sign-in as someone else on the same phone), so the upsert also re-binds user_id.
 */
function registerDevice()
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $userId = (int)$tokenData['userId'];
    $input = getJsonInput();

    $fcmToken = trim((string)($input['fcmToken'] ?? ''));
    if ($fcmToken === '') {
      Response::error('fcmToken is required', 400);
      return;
    }
    $label = substr(trim((string)($input['deviceLabel'] ?? 'Android device')), 0, 120);

    $db = getDB();
    $db->execute(
      "INSERT INTO devices (user_id, fcm_token, device_label, is_active, last_seen_at)
       VALUES (?, ?, ?, 1, NOW())
       ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), device_label = VALUES(device_label),
                               is_active = 1, last_seen_at = NOW()",
      [$userId, $fcmToken, $label]
    );

    Response::success(null, 'Device registered');
  } catch (Exception $e) {
    error_log('registerDevice error: ' . $e->getMessage());
    Response::error('Failed to register device', 500);
  }
}
