<?php
require_once __DIR__ . '/../vendor/autoload.php';

use \Firebase\JWT\JWT;

/**
 * LiveKit access tokens are plain HS256 JWTs — no server SDK needed.
 * iss = API key, sub = participant identity, video = grant object.
 * https://docs.livekit.io/home/get-started/authentication/
 */
function mintLivekitToken(string $identity, string $displayName, string $room, int $ttlSeconds = 3600): string
{
  if (LIVEKIT_API_KEY === '' || LIVEKIT_API_SECRET === '') {
    throw new Exception('LiveKit not configured (LIVEKIT_API_KEY / LIVEKIT_API_SECRET missing)');
  }
  $now = time();
  $payload = [
    'iss' => LIVEKIT_API_KEY,
    'sub' => $identity,
    'name' => $displayName,
    'nbf' => $now - 10,
    'iat' => $now,
    'exp' => $now + $ttlSeconds,
    'video' => [
      'roomJoin' => true,
      'room' => $room,
      'canPublish' => true,
      'canSubscribe' => true,
    ],
  ];
  return JWT::encode($payload, LIVEKIT_API_SECRET, 'HS256');
}
