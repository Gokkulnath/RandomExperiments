# SMS Backup To Drive - Design Document

## 1. Introduction

SMS Backup To Drive is an Android application designed to allow users to back up their SMS messages to a Google Sheet stored in their Google Drive. This provides a simple way for users to keep a persistent, cloud-based archive of their text messages. The app features both manual backup of all messages and automatic backup of new incoming messages.

## 2. Core Components

The application is built around several key components:

### 2.1. `MainActivity.kt`
- **Purpose**: Serves as the primary user interface (UI) for the application.
- **Responsibilities**:
    - Handles Google Sign-In and Sign-Out functionality using the Google Sign-In API.
    - Displays the current sign-in status and user information.
    - Allows users to initiate a manual synchronization of all SMS messages.
    - Manages runtime permission requests (specifically for `READ_SMS`).
    - Shows the status of ongoing sync operations and the timestamp of the last successful sync.
    - Orchestrates the manual backup flow, including pre-checks (permissions, network, sign-in) and UI updates based on the sync outcome.

### 2.2. `SmsReceiver.kt`
- **Purpose**: Listens for new incoming SMS messages.
- **Responsibilities**:
    - Implemented as a `BroadcastReceiver` that triggers when an SMS is received (`android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION`).
    - Parses the incoming SMS data (sender, body, timestamp).
    - Enqueues an `SmsSyncWorker` using Android's `WorkManager` to process and back up the new SMS in the background.

### 2.3. `SmsSyncWorker.kt`
- **Purpose**: Performs the actual backup of individual SMS messages in a background thread.
- **Responsibilities**:
    - Implemented as a `CoroutineWorker` managed by `WorkManager`, ensuring reliable background execution.
    - Receives SMS data (sender, body, timestamp) as input from `SmsReceiver` (or potentially other sources in the future).
    - Performs pre-checks:
        - Network availability (retries if network is unavailable).
        - User Google Sign-In status (fails if not signed in).
    - Interacts with `GoogleSheetsService` to:
        - Find or create the designated Google Sheet.
        - Check for duplicate messages by comparing against existing SMS IDs in the sheet.
        - Append the new SMS message to the sheet if it's not a duplicate.
    - Returns a result (`Result.success()`, `Result.failure()`, `Result.retry()`) to `WorkManager` based on the outcome.

### 2.4. `GoogleSheetsService.kt`
- **Purpose**: Encapsulates all interactions with the Google Drive and Google Sheets APIs.
- **Responsibilities**:
    - Initializes Google API clients (Drive and Sheets) using user credentials obtained via Google Sign-In.
    - **Spreadsheet Management**:
        - `findOrCreateSpreadsheet()`: Searches for the "SMS Backups" spreadsheet in the user's Google Drive. If not found, it creates a new spreadsheet with the specified name and a header row in the "Messages" tab. Caches the spreadsheet ID for subsequent operations.
    - **Data Handling**:
        - `generateSmsId(smsData: SmsData)`: Generates a unique SHA-256 hash for an SMS message based on its sender, timestamp, and body. This ID is used for deduplication.
        - `getExistingSmsIds()`: Retrieves all existing SMS IDs (hashes) from the "SMS ID" column in the "Messages" tab of the spreadsheet.
        - `appendSmsToSheet(smsData: SmsData)`: Appends a new row to the "Messages" tab with the SMS details (generated ID, timestamp, sender, body).
    - **Error Handling**: Provides a utility `getErrorMessageForException(e: Exception)` to translate API exceptions into user-friendly error messages.
    - All API calls are executed as suspend functions on an I/O dispatcher (`Dispatchers.IO`).

### 2.5. `NetworkUtils.kt`
- **Purpose**: A utility object to check the device's current network connectivity status.
- **Responsibilities**:
    - `isNetworkAvailable(context: Context)`: Returns `true` if the device has an active internet connection (Wi-Fi, Cellular, Ethernet, Bluetooth PAN), `false` otherwise. Handles different Android API levels for querying network state.

### 2.6. `SmsData.kt`
- **Purpose**: A simple Kotlin data class representing an SMS message.
- **Fields**:
    - `sender: String`: The originating address of the SMS.
    - `body: String`: The content of the SMS message.
    - `timestamp: Long`: The timestamp (in milliseconds) when the SMS was sent/received.

## 3. Data Flow

### 3.1. Manual Sync
1.  User clicks the "Manual Sync" button in `MainActivity`.
2.  `MainActivity.handleManualSync()`:
    a.  Checks for `READ_SMS` permission. If not granted, requests it.
    b.  Checks for network availability using `NetworkUtils`. If unavailable, shows an error.
    c.  Checks if the user is signed in via Google. If not, shows an error.
    d.  If all checks pass, shows a progress bar and launches a coroutine.
3.  `MainActivity.performSmsSync()` (within the coroutine):
    a.  Initializes `GoogleSheetsService`.
    b.  Calls `readAllSms()` to get all SMS messages from the device.
    c.  If no SMS messages, reports this.
    d.  Otherwise, calls `sheetService.findOrCreateSpreadsheet()`.
    e.  Calls `sheetService.getExistingSmsIds()`.
    f.  Iterates through each SMS from `readAllSms()`:
        i.  Generates an SMS ID (hash) using `sheetService.generateSmsId()`.
        ii. If the ID is not in the existing IDs list, calls `sheetService.appendSmsToSheet()`.
        iii.Updates UI with progress (e.g., "Processed X of Y messages").
    g.  Catches any exceptions and uses `sheetService.getErrorMessageForException()` for error reporting.
4.  `MainActivity.handleManualSync()` (coroutine's `Main` context block):
    a.  Hides the progress bar.
    b.  Updates the UI (`syncStatusTextView`, `lastSyncStatusTextView`) with the final result (success count, error message, etc.).
    c.  Saves the current timestamp as the last sync time if successful.

### 3.2. Automatic Sync (New Incoming SMS)
1.  A new SMS message is received by the Android system.
2.  `SmsReceiver.onReceive()` is triggered.
3.  `SmsReceiver` extracts the sender, body, and timestamp from the incoming SMS.
4.  It creates a `Data` object containing this information.
5.  It enqueues a `OneTimeWorkRequest` for `SmsSyncWorker` with `WorkManager`, passing the `Data` object.
6.  `SmsSyncWorker.doWork()`:
    a.  Checks for network availability. If unavailable, returns `Result.retry()`.
    b.  Retrieves SMS data from input. If invalid, returns `Result.failure()`.
    c.  Checks if the user is signed in. If not, returns `Result.failure()`.
    d.  Calls `attemptSmsSheetSync()`:
        i.  Initializes `GoogleSheetsService`.
        ii. Calls `sheetService.findOrCreateSpreadsheet()`.
        iii.Generates SMS ID (hash) using `sheetService.generateSmsId()`.
        iv. Calls `sheetService.getExistingSmsIds()`.
        v.  If the ID is not in the existing list, calls `sheetService.appendSmsToSheet()`.
        vi. Translates exceptions/outcomes into `WorkerSyncOutcome`.
    e.  Based on `WorkerSyncOutcome`, returns `Result.success()`, `Result.failure()`, or `Result.retry()`.

## 4. Error Handling
- **`MainActivity`**: Displays user-friendly error messages in `syncStatusTextView` for issues during manual sync (no network, not signed in, permission denied, API errors from `GoogleSheetsService`).
- **`SmsSyncWorker`**:
    - Uses `Result.retry()` for transient issues like network unavailability.
    - Uses `Result.failure()` for persistent issues like invalid data, user not signed in, or non-retryable API errors.
    - Logs detailed errors.
- **`GoogleSheetsService`**: Catches API exceptions (`GoogleJsonResponseException`, `IOException`) from Google API calls and can translate them into user-friendly messages via `getErrorMessageForException()`. It generally throws `IOException` for network issues, which callers can then decide to retry.

## 5. Permissions
The application requires the following Android permissions (declared in `AndroidManifest.xml`):
-   `android.permission.RECEIVE_SMS`: To allow the app to be notified of incoming SMS messages for automatic backup.
-   `android.permission.READ_SMS`: To allow the app to read existing SMS messages from the device during a manual backup.
-   `android.permission.INTERNET`: Essential for communicating with Google Drive and Google Sheets APIs.
-   `android.permission.ACCESS_NETWORK_STATE`: To check for network connectivity before attempting any network operations, preventing unnecessary errors.
-   `android.permission.GET_ACCOUNTS`: While often implicitly handled by modern Google Sign-In, it's traditionally associated with allowing the Google Sign-In SDK to access and list Google accounts on the device for the user to choose from.

## 6. Google Sheet Structure
-   **Spreadsheet Name**: "SMS Backups" (created in the user's root Google Drive folder if it doesn't exist).
-   **Sheet (Tab) Name**: "Messages"
-   **Columns**:
    1.  `SMS ID`: A unique SHA-256 hash generated from the sender, timestamp, and body of the SMS. Used for deduplication.
    2.  `Timestamp`: The original timestamp of the SMS message (e.g., "YYYY-MM-DD HH:MM:SS" format, though stored as a string representation of the long timestamp in the service).
    3.  `Sender`: The phone number or contact name of the SMS sender.
    4.  `Message Body`: The textual content of the SMS message.

```
