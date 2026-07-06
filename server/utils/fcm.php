<?php
require_once __DIR__ . '/../vendor/autoload.php';

/**
 * FCM v1 HTTP sender for call-signaling data pushes (ring / cancel / declined).
 * Data-only + Android priority HIGH so the message is Doze-exempt and wakes the
 * app's FirebaseMessagingService; short TTL so a stale ring dies in transit
 * instead of ringing a phone minutes later.
 *
 * When FCM isn't configured yet (local dev before Firebase setup), sends are
 * skipped gracefully — call flows stay curl-testable end to end.
 */
class Fcm
{
  /**
   * @param string[] $tokens  active device FCM tokens
   * @param array $data       flat payload; every value is cast to string (FCM v1 requirement)
   * @return array{sent:int, failed:int, skipped:bool, staleTokens:string[]}
   */
  public static function sendData(array $tokens, array $data, string $ttl = '45s'): array
  {
    $result = ['sent' => 0, 'failed' => 0, 'skipped' => false, 'staleTokens' => []];
    if (empty($tokens)) {
      return $result;
    }
    if (FCM_PROJECT_ID === '' || FCM_SERVICE_ACCOUNT_PATH === '' || !file_exists(FCM_SERVICE_ACCOUNT_PATH)) {
      error_log('[FCM] Not configured — skipping send of type=' . ($data['type'] ?? '?'));
      $result['skipped'] = true;
      return $result;
    }

    $stringData = [];
    foreach ($data as $k => $v) {
      $stringData[(string)$k] = (string)$v;
    }

    try {
      $client = new Google\Client();
      $client->setAuthConfig(FCM_SERVICE_ACCOUNT_PATH);
      $client->addScope('https://www.googleapis.com/auth/firebase.messaging');
      $tokenInfo = $client->fetchAccessTokenWithAssertion();
      $accessToken = $tokenInfo['access_token'] ?? null;
      if (!$accessToken) {
        throw new Exception('No access_token in service-account assertion response');
      }
    } catch (Throwable $e) {
      error_log('[FCM] Service-account auth failed: ' . $e->getMessage());
      $result['failed'] = count($tokens);
      return $result;
    }

    $url = 'https://fcm.googleapis.com/v1/projects/' . FCM_PROJECT_ID . '/messages:send';
    foreach ($tokens as $token) {
      $body = [
        'message' => [
          'token' => $token,
          'data' => $stringData,
          'android' => [
            'priority' => 'HIGH',
            'ttl' => $ttl,
          ],
        ],
      ];
      $ch = curl_init();
      curl_setopt_array($ch, [
        CURLOPT_URL => $url,
        CURLOPT_POST => true,
        CURLOPT_RETURNTRANSFER => true,
        CURLOPT_TIMEOUT => 10,
        CURLOPT_HTTPHEADER => [
          'Authorization: Bearer ' . $accessToken,
          'Content-Type: application/json',
        ],
        CURLOPT_POSTFIELDS => json_encode($body),
      ]);
      $response = curl_exec($ch);
      $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
      curl_close($ch);

      if ($httpCode >= 200 && $httpCode < 300) {
        $result['sent']++;
      } else {
        $result['failed']++;
        error_log("[FCM] send failed http=$httpCode resp=" . substr((string)$response, 0, 300));
        // UNREGISTERED / NOT_FOUND => token is dead; caller should deactivate it.
        if ($httpCode === 404 || strpos((string)$response, 'UNREGISTERED') !== false) {
          $result['staleTokens'][] = $token;
        }
      }
    }
    return $result;
  }

  /** Send to all of a user's active devices and deactivate any stale tokens. */
  public static function sendToUser(int $userId, array $data, string $ttl = '45s'): array
  {
    $db = getDB();
    $rows = $db->fetchAll(
      "SELECT fcm_token FROM devices WHERE user_id = ? AND is_active = 1",
      [$userId]
    );
    $tokens = array_column($rows, 'fcm_token');
    $result = self::sendData($tokens, $data, $ttl);
    foreach ($result['staleTokens'] as $stale) {
      $db->execute("UPDATE devices SET is_active = 0 WHERE fcm_token = ?", [$stale]);
    }
    $result['deviceCount'] = count($tokens);
    return $result;
  }
}
