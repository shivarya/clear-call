<?php
// Set error reporting
error_reporting(E_ALL);
ini_set('display_errors', '0');
date_default_timezone_set('Asia/Kolkata');

// Load configuration + shared utils
require_once __DIR__ . '/config/config.php';
require_once __DIR__ . '/config/database.php';
require_once __DIR__ . '/utils/response.php';
require_once __DIR__ . '/utils/jwt.php';

// Handle CORS (native app doesn't need it; kept for dev tooling)
$origin = isset($_SERVER['HTTP_ORIGIN']) ? $_SERVER['HTTP_ORIGIN'] : '';
$isDev = (DB_HOST === 'localhost' || DB_HOST === '127.0.0.1');
if ($isDev) {
  header("Access-Control-Allow-Origin: *");
} elseif (in_array($origin, ALLOWED_ORIGINS)) {
  header("Access-Control-Allow-Origin: $origin");
}
header('Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With');
header('Content-Type: application/json; charset=utf-8');

// Handle preflight requests
if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
  http_response_code(200);
  exit();
}

// Get request URI and method
$requestUri = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$requestMethod = $_SERVER['REQUEST_METHOD'];

// Remove base path if API is in a subdirectory (e.g., /clear_call/)
$scriptDir = dirname($_SERVER['SCRIPT_NAME']);
if ($scriptDir !== '/' && strpos($requestUri, $scriptDir) === 0) {
  $requestUri = substr($requestUri, strlen($scriptDir));
}

// Remove trailing slash
$requestUri = rtrim($requestUri, '/');

// Error handler
set_exception_handler(function ($e) {
  error_log("Exception: " . $e->getMessage() . " in " . $e->getFile() . " on line " . $e->getLine());
  Response::error('Internal server error', 500);
});

// Routing
try {
  // Health check
  if ($requestUri === '/health' || $requestUri === '') {
    Response::success([
      'status' => 'healthy',
      'timestamp' => date('Y-m-d H:i:s'),
    ], 'ClearCall API is running');
  }

  if (strpos($requestUri, '/auth') === 0) {
    require_once __DIR__ . '/controllers/authController.php';
    handleAuthRoutes($requestUri, $requestMethod);
    exit;
  }

  if (strpos($requestUri, '/devices') === 0) {
    require_once __DIR__ . '/controllers/devicesController.php';
    handleDevicesRoutes($requestUri, $requestMethod);
    exit;
  }

  if (strpos($requestUri, '/contacts') === 0) {
    require_once __DIR__ . '/controllers/contactsController.php';
    handleContactsRoutes($requestUri, $requestMethod);
    exit;
  }

  if (strpos($requestUri, '/calls') === 0) {
    require_once __DIR__ . '/controllers/callsController.php';
    handleCallsRoutes($requestUri, $requestMethod);
    exit;
  }

  // If no route matched
  Response::error('Route not found: ' . $requestUri, 404);
} catch (Exception $e) {
  error_log("Error: " . $e->getMessage());
  Response::error('Internal server error', 500);
}
