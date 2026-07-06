<?php
require_once __DIR__ . '/fcm.php';
require_once __DIR__ . '/apns.php';

/**
 * Platform-aware push dispatcher. A user may have Android and/or iOS devices; this routes each
 * device's signaling push to the right transport — FCM data push for Android, APNs VoIP push
 * for iOS — and deactivates any tokens the transports report dead. Call sites (ring / cancel /
 * declined in callsController) don't need to know which platform the callee is on.
 */
class Push
{
  /**
   * @param array  $data flat signaling payload (type, callId, …)
   * @return array{sent:int, failed:int, skipped:bool, deviceCount:int}
   */
  public static function sendToUser(int $userId, array $data, string $fcmTtl = '45s', int $apnsTtlSeconds = 45): array
  {
    $db = getDB();
    $rows = $db->fetchAll(
      "SELECT fcm_token, platform FROM devices WHERE user_id = ? AND is_active = 1",
      [$userId]
    );

    $androidTokens = [];
    $iosTokens = [];
    foreach ($rows as $r) {
      if ($r['platform'] === 'ios') {
        $iosTokens[] = $r['fcm_token'];
      } else {
        $androidTokens[] = $r['fcm_token'];
      }
    }

    $sent = 0;
    $failed = 0;
    $skipped = true;
    $stale = [];

    if (!empty($androidTokens)) {
      $r = Fcm::sendData($androidTokens, $data, $fcmTtl);
      $sent += $r['sent'];
      $failed += $r['failed'];
      $skipped = $skipped && $r['skipped'];
      $stale = array_merge($stale, $r['staleTokens']);
    }
    if (!empty($iosTokens)) {
      $r = Apns::sendVoip($iosTokens, $data, $apnsTtlSeconds);
      $sent += $r['sent'];
      $failed += $r['failed'];
      $skipped = $skipped && $r['skipped'];
      $stale = array_merge($stale, $r['staleTokens']);
    }

    foreach ($stale as $token) {
      $db->execute("UPDATE devices SET is_active = 0 WHERE fcm_token = ?", [$token]);
    }

    return [
      'sent' => $sent,
      'failed' => $failed,
      // "skipped" only if there was nothing to send OR every applicable transport was unconfigured.
      'skipped' => (empty($androidTokens) && empty($iosTokens)) ? false : $skipped,
      'deviceCount' => count($androidTokens) + count($iosTokens),
    ];
  }
}
