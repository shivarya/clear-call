<?php
// Load .env file
$envFile = __DIR__ . '/../.env';
if (file_exists($envFile)) {
  $lines = file($envFile, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES);
  foreach ($lines as $line) {
    if (strpos($line, '=') !== false && strpos($line, '#') !== 0) {
      list($name, $value) = explode('=', $line, 2);
      $_ENV[trim($name)] = trim($value);
      putenv(trim($name) . '=' . trim($value));
    }
  }
}

// Database configuration
define('DB_HOST', getenv('DB_HOST') ?: 'localhost');
define('DB_PORT', getenv('DB_PORT') ?: '3306');
define('DB_NAME', getenv('DB_NAME') ?: 'clear_call');
define('DB_USER', getenv('DB_USER') ?: 'root');
define('DB_PASS', getenv('DB_PASS') ?: '');

// Our own session JWTs (issued after Google Sign-In / dev login)
define('JWT_SECRET', getenv('JWT_SECRET') ?: 'dev-secret-change-in-production');
define('JWT_EXPIRES_IN', (int)(getenv('JWT_EXPIRES_IN') ?: 60 * 60 * 24 * 30)); // 30 days

// Google Sign-In (ID tokens verified against these audiences)
define('GOOGLE_CLIENT_ID', getenv('GOOGLE_CLIENT_ID') ?: '');

// Dev backdoor: POST /auth/login {as:1|2} issues tokens for seeded test users.
// Call flows need TWO users, hence the parameter. NEVER enable in production.
define('ALLOW_DEV_LOGIN', filter_var(getenv('ALLOW_DEV_LOGIN') ?: 'false', FILTER_VALIDATE_BOOLEAN));

// LiveKit (Cloud project or self-hosted server)
define('LIVEKIT_URL', getenv('LIVEKIT_URL') ?: '');            // wss://<project>.livekit.cloud
define('LIVEKIT_API_KEY', getenv('LIVEKIT_API_KEY') ?: '');
define('LIVEKIT_API_SECRET', getenv('LIVEKIT_API_SECRET') ?: '');

// FCM v1 (Android ring pushes). Service-account JSON must be chmod 600 and .htaccess-denied.
define('FCM_PROJECT_ID', getenv('FCM_PROJECT_ID') ?: '');
define('FCM_SERVICE_ACCOUNT_PATH', getenv('FCM_SERVICE_ACCOUNT_PATH') ?: '');

// APNs VoIP (iOS ring pushes). Direct to Apple with an ES256 .p8 auth key — not via FCM.
// Unset until an Apple Developer account exists; sends are skipped gracefully meanwhile.
define('APNS_KEY_PATH', getenv('APNS_KEY_PATH') ?: '');   // path to AuthKey_XXXX.p8 (outside webroot)
define('APNS_KEY_ID', getenv('APNS_KEY_ID') ?: '');       // the .p8's Key ID
define('APNS_TEAM_ID', getenv('APNS_TEAM_ID') ?: '');     // Apple Developer Team ID
define('APNS_BUNDLE_ID', getenv('APNS_BUNDLE_ID') ?: 'dev.shivarya.clearcall');
define('APNS_ENV', getenv('APNS_ENV') ?: 'sandbox');      // 'sandbox' | 'production' (TestFlight = production)

// Ring lifecycle
define('RING_TIMEOUT_SECONDS', (int)(getenv('RING_TIMEOUT_SECONDS') ?: 45));

// Timezone
date_default_timezone_set('Asia/Kolkata');

// Error reporting
error_reporting(E_ALL);
ini_set('display_errors', '0');
ini_set('log_errors', '1');
ini_set('error_log', __DIR__ . '/../php_errors.log');

// CORS settings (native mobile app only — kept for dev tooling)
define('ALLOWED_ORIGINS', [
  'http://localhost:8081',
]);
