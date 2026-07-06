<?php

function handleContactsRoutes($uri, $method)
{
  if ($uri === '/contacts' && $method === 'POST') {
    addContact();
  } elseif ($uri === '/contacts' && $method === 'GET') {
    listContacts();
  } elseif (preg_match('#^/contacts/(\d+)$#', $uri, $m) && $method === 'DELETE') {
    removeContact((int)$m[1]);
  } else {
    Response::error('Route not found', 404);
  }
}

/** Add by the other person's share code — inserts BOTH direction rows atomically. */
function addContact()
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $userId = (int)$tokenData['userId'];
    $input = getJsonInput();

    $code = strtoupper(trim((string)($input['userCode'] ?? '')));
    if ($code === '') {
      Response::error('userCode is required', 400);
      return;
    }

    $db = getDB();
    $peer = $db->fetchOne("SELECT * FROM users WHERE user_code = ?", [$code]);
    if (!$peer) {
      Response::error('No user with that code', 404);
      return;
    }
    if ((int)$peer['id'] === $userId) {
      Response::error('You cannot add yourself', 400);
      return;
    }
    $existing = $db->fetchOne(
      "SELECT id FROM contacts WHERE user_id = ? AND contact_user_id = ?",
      [$userId, $peer['id']]
    );
    if ($existing) {
      Response::error('Already in your contacts', 409);
      return;
    }

    $db->beginTransaction();
    try {
      $db->execute("INSERT INTO contacts (user_id, contact_user_id) VALUES (?, ?)", [$userId, $peer['id']]);
      $db->execute(
        "INSERT IGNORE INTO contacts (user_id, contact_user_id) VALUES (?, ?)",
        [$peer['id'], $userId]
      );
      $db->commit();
    } catch (Exception $e) {
      $db->rollback();
      throw $e;
    }

    Response::success(userSummaryRow($peer), 'Contact added');
  } catch (Exception $e) {
    error_log('addContact error: ' . $e->getMessage());
    Response::error('Failed to add contact', 500);
  }
}

function listContacts()
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $db = getDB();
    $rows = $db->fetchAll(
      "SELECT u.id, u.name, u.email, u.avatar_url, u.user_code
       FROM contacts c JOIN users u ON u.id = c.contact_user_id
       WHERE c.user_id = ? ORDER BY u.name",
      [(int)$tokenData['userId']]
    );
    Response::success(array_map('userSummaryRow', $rows), 'Contacts retrieved');
  } catch (Exception $e) {
    error_log('listContacts error: ' . $e->getMessage());
    Response::error('Failed to list contacts', 500);
  }
}

function removeContact(int $peerId)
{
  try {
    $tokenData = JWTHandler::requireAuth();
    $userId = (int)$tokenData['userId'];
    $db = getDB();
    $db->execute(
      "DELETE FROM contacts WHERE (user_id = ? AND contact_user_id = ?) OR (user_id = ? AND contact_user_id = ?)",
      [$userId, $peerId, $peerId, $userId]
    );
    Response::success(null, 'Contact removed');
  } catch (Exception $e) {
    error_log('removeContact error: ' . $e->getMessage());
    Response::error('Failed to remove contact', 500);
  }
}

/** Same public shape as authController's userSummary, for joined rows. */
function userSummaryRow(array $u): array
{
  return [
    'id' => (int)$u['id'],
    'name' => $u['name'],
    'email' => $u['email'],
    'userCode' => $u['user_code'],
    'avatarUrl' => $u['avatar_url'] ?? null,
  ];
}
