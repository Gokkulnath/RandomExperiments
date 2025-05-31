## SMS Backup To Drive Android App

This Android application allows users to back up their SMS messages to a Google Sheet in their Google Drive.

### Features
- Sign in with your Google account.
- Manually trigger a backup of all SMS messages to a Google Sheet.
- Automatically back up new incoming SMS messages.
- View last sync status.

### Permissions Required
- **READ_SMS**: To read SMS messages from your device for backup.
- **RECEIVE_SMS**: To detect incoming SMS messages for automatic backup.
- **INTERNET**: To communicate with Google Drive and Google Sheets APIs.
- **ACCESS_NETWORK_STATE**: To check for network connectivity before attempting backups.
- **GET_ACCOUNTS**: (Implicitly used by Google Sign-In) To allow Google account selection.

## Building the APK

You can build the release APK for the application using Gradle.

### Using Gradle Directly
1.  Navigate to the `SMSBackupToDrive` directory:
    ```bash
    cd SMSBackupToDrive
    ```
2.  Run the `packageReleaseApk` task:
    ```bash
    ./gradlew :app:packageReleaseApk
    ```
3.  This will assemble the release APK and copy it to the `apks/` directory in the project root (e.g., `apks/sms_backup_to_drive_v1.0.apk`).

### Using the Automated Script
A helper script is provided to automate the building of the APK and committing it to the repository.

1.  Ensure the script is executable:
    ```bash
    chmod +x scripts/commit_apk.sh
    ```
2.  Run the script from the project root directory:
    ```bash
    ./scripts/commit_apk.sh
    ```
3.  This script will:
    - Build the release APK using the `packageReleaseApk` Gradle task.
    - Stage the generated APK file.
    - Commit the APK with a message like "feat: Build and add release APK vX.Y (YYYY-MM-DD HH:MM UTC)".
    - It will remind you to `git push` the changes if desired.

## Development Notes
- The application uses Google Sign-In for authentication and requires appropriate OAuth 2.0 credentials (client ID) to be configured in `strings.xml` (`server_client_id`) for the Google Sign-In and Google Sheets/Drive API access to work.
- SMS messages are stored in a Google Sheet named "SMS Backups" in the user's Google Drive.