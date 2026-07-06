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
 * Upsert this user's push token. On Android that's the FCM token; on iOS it's the PushKit
 * VoIP token (both stored in `fcm_token`, disambiguated by `platform`). A token can migrate
 * between users (sign-out / sign-in as someone else on the same phone), so the upsert also
 * re-binds user_id and platform.
 */
function registerDevice()
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $userId = (int)$tokenData['userId'];
    $input = getJsonInput();

    // `pushToken` is the platform-neutral name; `fcmToken` kept as a back-compat alias.
    $pushToken = trim((string)($input['pushToken'] ?? $input['fcmToken'] ?? ''));
    if ($pushToken === '') {
      Response::error('pushToken is required', 400);
      return;
    }
    $platform = ($input['platform'] ?? 'android') === 'ios' ? 'ios' : 'android';
    $defaultLabel = $platform === 'ios' ? 'iPhone' : 'Android device';
    $label = substr(trim((string)($input['deviceLabel'] ?? $defaultLabel)), 0, 120);

    $db = getDB();
    $db->execute(
      "INSERT INTO devices (user_id, fcm_token, platform, device_label, is_active, last_seen_at)
       VALUES (?, ?, ?, ?, 1, NOW())
       ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), platform = VALUES(platform),
                               device_label = VALUES(device_label), is_active = 1, last_seen_at = NOW()",
      [$userId, $pushToken, $platform, $label]
    );

    Response::success(null, 'Device registered');
  } catch (Exception $e) {
    error_log('registerDevice error: ' . $e->getMessage());
    Response::error('Failed to register device', 500);
  }
}
