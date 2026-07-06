<?php
require_once __DIR__ . '/../vendor/autoload.php';

use \Firebase\JWT\JWT;

/**
 * APNs VoIP push sender for iOS ring signaling.
 *
 * Apple's PushKit VoIP pushes **cannot** go through FCM — they must hit APNs directly on the
 * `<bundleid>.voip` topic with `apns-push-type: voip`, and the app must immediately
 * `reportNewIncomingCall` to CallKit or iOS penalizes it. Auth is an ES256 JWT signed with an
 * APNs `.p8` auth key (same `firebase/php-jwt` lib as everywhere else, `ES256` alg). Requires
 * HTTP/2 (Apple rejects HTTP/1.1).
 *
 * When APNs isn't configured (no `.p8` yet — the common case until an Apple Developer account
 * exists), sends are skipped gracefully so Android-only call flows stay fully working/testable.
 */
class Apns
{
  /** APNs provider JWTs are reusable for ~1h; Apple rate-limits regeneration. Cache per request. */
  private static ?string $cachedJwt = null;
  private static int $cachedAt = 0;

  /**
   * @param string[] $tokens PushKit VoIP device tokens (hex)
   * @param array    $data   flat payload delivered to PushKit (values stringified)
   * @return array{sent:int, failed:int, skipped:bool, staleTokens:string[]}
   */
  public static function sendVoip(array $tokens, array $data, int $ttlSeconds = 45): array
  {
    $result = ['sent' => 0, 'failed' => 0, 'skipped' => false, 'staleTokens' => []];
    if (empty($tokens)) {
      return $result;
    }
    if (
      APNS_KEY_PATH === '' || APNS_KEY_ID === '' || APNS_TEAM_ID === '' ||
      APNS_BUNDLE_ID === '' || !file_exists(APNS_KEY_PATH)
    ) {
      error_log('[APNs] Not configured — skipping VoIP send of type=' . ($data['type'] ?? '?'));
      $result['skipped'] = true;
      return $result;
    }

    try {
      $jwt = self::providerToken();
    } catch (Throwable $e) {
      error_log('[APNs] Provider token build failed: ' . $e->getMessage());
      $result['failed'] = count($tokens);
      return $result;
    }

    $host = APNS_ENV === 'production' ? 'https://api.push.apple.com' : 'https://api.sandbox.push.apple.com';
    $stringData = [];
    foreach ($data as $k => $v) {
      $stringData[(string)$k] = (string)$v;
    }
    // A VoIP payload has no user-facing alert; PushKit hands the whole dict to the app.
    $payload = json_encode(array_merge(['aps' => new stdClass()], $stringData));

    foreach ($tokens as $token) {
      $ch = curl_init();
      curl_setopt_array($ch, [
        CURLOPT_URL => "$host/3/device/$token",
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 10,
        CURLOPT_HTTP_VERSION => CURL_HTTP_VERSION_2TLS, // APNs requires HTTP/2
        CURLOPT_POSTFIELDS => $payload,
        CURLOPT_HTTPHEADER => [
          'authorization: bearer ' . $jwt,
          'apns-topic: ' . APNS_BUNDLE_ID . '.voip',
          'apns-push-type: voip',
          'apns-priority: 10',
          'apns-expiration: ' . (time() + $ttlSeconds),
          'content-type: application/json',
        ],
      ]);
      $response = curl_exec($ch);
      $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
      $curlErr = curl_error($ch);
      curl_close($ch);

      if ($httpCode >= 200 && $httpCode < 300) {
        $result['sent']++;
      } else {
        $result['failed']++;
        error_log("[APNs] send failed http=$httpCode err=$curlErr resp=" . substr((string)$response, 0, 300));
        // 410 Gone => the device token is no longer valid; caller should deactivate it.
        if ($httpCode === 410 || strpos((string)$response, 'BadDeviceToken') !== false || strpos((string)$response, 'Unregistered') !== false) {
          $result['staleTokens'][] = $token;
        }
      }
    }
    return $result;
  }

  private static function providerToken(): string
  {
    $now = time();
    if (self::$cachedJwt !== null && ($now - self::$cachedAt) < 1500) {
      return self::$cachedJwt;
    }
    $key = file_get_contents(APNS_KEY_PATH);
    if ($key === false) {
      throw new Exception('Could not read APNS_KEY_PATH');
    }
    $jwt = JWT::encode(
      ['iss' => APNS_TEAM_ID, 'iat' => $now],
      $key,
      'ES256',
      APNS_KEY_ID // becomes the `kid` header
    );
    self::$cachedJwt = $jwt;
    self::$cachedAt = $now;
    return $jwt;
  }
}
