<?php

require_once __DIR__ . '/../vendor/autoload.php'; // Google API client for ID token verification

function handleAuthRoutes($uri, $method)
{
  if ($uri === '/auth/login' && $method === 'POST') {
    devLogin();
  } elseif ($uri === '/auth/google' && $method === 'POST') {
    googleLogin();
  } elseif ($uri === '/auth/me' && $method === 'GET') {
    getMe();
  } elseif ($uri === '/auth/account' && $method === 'DELETE') {
    deleteAccount();
  } else {
    Response::error('Route not found', 404);
  }
}

/** Public shape of a user row (never leak google_sub). */
function userSummary(array $user): array
{
  return [
    'id' => (int)$user['id'],
    'email' => $user['email'],
    'name' => $user['name'],
    'userCode' => $user['user_code'],
    'avatarUrl' => $user['avatar_url'],
  ];
}

/** 8-char code from an unambiguous alphabet (no 0/O/1/I); retried on collision. */
function generateUserCode($db): string
{
  $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  for ($attempt = 0; $attempt < 10; $attempt++) {
    $code = '';
    for ($i = 0; $i < 8; $i++) {
      $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
    }
    $exists = $db->fetchOne("SELECT id FROM users WHERE user_code = ?", [$code]);
    if (!$exists) {
      return $code;
    }
  }
  throw new Exception('Could not generate a unique user code');
}

/**
 * Dev-only login for local testing WITHOUT Google Sign-In. Call flows need two
 * distinct users, so this takes {as: 1|2} and seeds both test users on demand.
 * Gated by ALLOW_DEV_LOGIN — must NEVER be true in production.
 */
function devLogin()
{
  if (!ALLOW_DEV_LOGIN) {
    Response::error('This endpoint is disabled. Use POST /auth/google.', 410);
    return;
  }

  try {
    $input = getJsonInput();
    $as = (int)($input['as'] ?? 1);
    if ($as !== 1 && $as !== 2) {
      Response::error('as must be 1 or 2', 400);
      return;
    }

    $db = getDB();
    $email = "dev$as@localhost";
    $user = $db->fetchOne("SELECT * FROM users WHERE email = ? AND google_sub IS NULL", [$email]);
    if (!$user) {
      $code = generateUserCode($db);
      $userId = $db->insert(
        "INSERT INTO users (email, name, user_code) VALUES (?, ?, ?)",
        [$email, "Dev User $as", $code]
      );
      $user = $db->fetchOne("SELECT * FROM users WHERE id = ?", [$userId]);
    }

    $token = JWTHandler::generate($user['id'], $user['email'], $user['name']);
    Response::success(['token' => $token, 'user' => userSummary($user)], 'Login successful (dev)');
  } catch (Exception $e) {
    error_log('Dev login failed: ' . $e->getMessage());
    Response::error('Login failed', 500);
  }
}

/** Audiences (OAuth client IDs) accepted on Google ID tokens. */
function getAllowedGoogleAudiences(): array
{
  $ids = [];
  if (defined('GOOGLE_CLIENT_ID') && trim((string)GOOGLE_CLIENT_ID) !== '') {
    $ids[] = trim((string)GOOGLE_CLIENT_ID);
  }
  $extra = getenv('GOOGLE_ALLOWED_AUDIENCES') ?: ($_ENV['GOOGLE_ALLOWED_AUDIENCES'] ?? '');
  foreach (explode(',', (string)$extra) as $aud) {
    $aud = trim($aud);
    if ($aud !== '') {
      $ids[] = $aud;
    }
  }
  return array_values(array_unique($ids));
}

/**
 * Verify a Google ID token: signature, expiry, issuer, audience, verified-email.
 * Returns the payload or null. (Same flow as diet-plan's authController.)
 */
function verifyGoogleIdToken(string $idToken): ?array
{
  $allowed = getAllowedGoogleAudiences();
  if (empty($allowed)) {
    error_log('Google auth not configured: GOOGLE_CLIENT_ID is empty');
    return null;
  }

  try {
    $client = new Google\Client();
    $payload = $client->verifyIdToken($idToken); // validates signature, exp, iss

    if (!is_array($payload) || empty($payload['aud'])) {
      return null;
    }
    if (!in_array($payload['aud'], $allowed, true)) {
      error_log('Google ID token audience mismatch: ' . $payload['aud']);
      return null;
    }
    $emailVerified = $payload['email_verified'] ?? false;
    if ($emailVerified === false || $emailVerified === 'false' || $emailVerified === 0) {
      error_log('Google ID token email not verified');
      return null;
    }
    return $payload;
  } catch (Throwable $e) {
    error_log('Google ID token verification failed: ' . $e->getMessage());
    return null;
  }
}

function googleLogin()
{
  try {
    $input = getJsonInput();
    $idToken = $input['idToken'] ?? $input['id_token'] ?? null;
    if (!$idToken) {
      Response::error('ID token is required', 400);
      return;
    }

    $payload = verifyGoogleIdToken($idToken);
    if (!$payload) {
      Response::error('Invalid or unverified Google token', 401);
      return;
    }

    $sub = $payload['sub'] ?? null;
    $email = $payload['email'] ?? null;
    if (!$sub || !$email) {
      Response::error('Google token missing sub/email', 400);
      return;
    }
    $name = $payload['name'] ?? $email;
    $picture = $payload['picture'] ?? null;

    // Keyed by immutable Google sub, not email (emails can change).
    $db = getDB();
    $user = $db->fetchOne("SELECT * FROM users WHERE google_sub = ?", [$sub]);

    if (!$user) {
      $code = generateUserCode($db);
      $userId = $db->insert(
        "INSERT INTO users (google_sub, email, name, avatar_url, user_code) VALUES (?, ?, ?, ?, ?)",
        [$sub, $email, $name, $picture, $code]
      );
      $user = $db->fetchOne("SELECT * FROM users WHERE id = ?", [$userId]);
    } else {
      $db->execute(
        "UPDATE users SET email = ?, name = ?, avatar_url = ?, updated_at = NOW() WHERE id = ?",
        [$email, $name, $picture, $user['id']]
      );
      $user = $db->fetchOne("SELECT * FROM users WHERE id = ?", [$user['id']]);
    }

    // Our own JWT (not Google's token)
    $token = JWTHandler::generate($user['id'], $user['email'], $user['name']);
    Response::success(['token' => $token, 'user' => userSummary($user)], 'Google login successful');
  } catch (Exception $e) {
    error_log("Google login error: " . $e->getMessage());
    Response::error('Google login failed', 500);
  }
}

function getMe()
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $db = getDB();
    $user = $db->fetchOne("SELECT * FROM users WHERE id = ?", [$tokenData['userId']]);
    if (!$user) {
      Response::error('User not found', 404);
    }
    Response::success(userSummary($user), 'User data retrieved');
  } catch (Exception $e) {
    Response::error('Failed to get user: ' . $e->getMessage(), 500);
  }
}

function deleteAccount()
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $db = getDB();
    // ON DELETE CASCADE clears devices, contacts (both directions), calls.
    $db->execute("DELETE FROM users WHERE id = ?", [$tokenData['userId']]);
    Response::success(null, 'Account and all associated data deleted');
  } catch (Exception $e) {
    error_log("Delete account error: " . $e->getMessage());
    Response::error('Failed to delete account', 500);
  }
}
