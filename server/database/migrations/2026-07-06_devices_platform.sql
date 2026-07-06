-- P4.5: iOS-readiness. Add a push platform to devices so the ring step can branch
-- FCM (Android) vs APNs VoIP (iOS). Existing rows default to 'android'.
-- Apply on the live DB:  mysql -u clearcall clear_call < this_file.sql
ALTER TABLE devices
  ADD COLUMN platform ENUM('android','ios') NOT NULL DEFAULT 'android' AFTER fcm_token;
