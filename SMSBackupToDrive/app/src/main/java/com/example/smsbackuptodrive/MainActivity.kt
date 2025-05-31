package com.example.smsbackuptodrive

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task

/**
 * Main activity for the SMS Backup to Drive application.
 * This activity handles user authentication with Google Sign-In, manages UI elements,
 * requests necessary permissions, and initiates the manual SMS backup process.
 */
class MainActivity : AppCompatActivity() {

    // Google Sign-In client used to initiate the sign-in flow and manage the current user.
    private lateinit var googleSignInClient: GoogleSignInClient
    // UI Elements
    private lateinit var signInButton: SignInButton
    private lateinit var signOutButton: Button
    private lateinit var statusTextView: TextView // Displays sign-in status
    private lateinit var userInfoTextView: TextView // Displays signed-in user's information
    private lateinit var manualSyncButton: Button // Button to trigger manual SMS sync
    private lateinit var syncProgressBar: ProgressBar // Progress bar shown during sync
    private lateinit var syncStatusTextView: TextView // Displays status of the sync operation
    private lateinit var lastSyncStatusTextView: TextView // Displays timestamp of the last successful sync

    // ActivityResultLauncher for handling the result of the Google Sign-In intent.
    private lateinit var signInLauncher: ActivityResultLauncher<Intent>

    companion object {
        private const val TAG = "MainActivity" // Logcat tag
        private const val REQUEST_CODE_READ_SMS = 101 // Request code for READ_SMS permission
    }

    /**
     * Called when the activity is first created.
     * Initializes UI components, Google Sign-In, and sets up event listeners.
     * @param savedInstanceState If the activity is being re-initialized after previously being shut down,
     * this Bundle contains the data it most recently supplied in onSaveInstanceState(Bundle). Otherwise, it is null.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements by finding them in the layout
        signInButton = findViewById(R.id.sign_in_button)
        signOutButton = findViewById(R.id.sign_out_button)
        statusTextView = findViewById(R.id.status_textview)
        userInfoTextView = findViewById(R.id.user_info_textview)
        manualSyncButton = findViewById(R.id.manual_sync_button)
        syncProgressBar = findViewById(R.id.sync_progress_bar)
        syncStatusTextView = findViewById(R.id.sync_status_textview)
        lastSyncStatusTextView = findViewById(R.id.last_sync_status_textview)

        // Load and display the timestamp of the last successful sync from SharedPreferences
        loadAndDisplayLastSyncTimestamp()

        // Configure Google Sign-In options
        // Requests user's email, ID token, and scopes for Google Drive (file access) and Sheets API.
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.server_client_id)) // OAuth 2.0 client ID for backend authentication
            .requestEmail() // Request user's email address
            .requestScopes(
                com.google.android.gms.common.api.Scope(com.google.api.services.drive.DriveScopes.DRIVE_FILE), // Scope for Google Drive file access
                com.google.android.gms.common.api.Scope(com.google.api.services.sheets.v4.SheetsScopes.SPREADSHEETS) // Scope for Google Sheets access
            )
            .build()

        // Initialize the GoogleSignInClient with the configured options
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Register an ActivityResultLauncher to handle the outcome of the Google Sign-In intent
        signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // Sign-in was successful, attempt to get the account from the intent
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                handleSignInResult(task)
            } else {
                // Sign-in failed or was cancelled by the user
                Log.w(TAG, "Sign-in failed. Result code: ${result.resultCode}")
                updateUI(null) // Update UI to reflect signed-out state
            }
        }

        // Set click listener for the Google Sign-In button
        signInButton.setOnClickListener {
            signIn()
        }

        // Set click listener for the Sign-Out button
        signOutButton.setOnClickListener {
            signOut()
        }

        // Set click listener for the Manual Sync button
        manualSyncButton.setOnClickListener {
            handleManualSync()
        }
    }

    /**
     * Called when the activity is becoming visible to the user.
     * Checks the current sign-in state and updates the UI accordingly.
     * Also refreshes the last sync timestamp display.
     */
    override fun onStart() {
        super.onStart()
        // Check if a user is already signed in (e.g., from a previous session)
        val account = GoogleSignIn.getLastSignedInAccount(this)
        updateUI(account) // Update UI based on current sign-in state
        loadAndDisplayLastSyncTimestamp() // Refresh last sync time display

        // Check for RECEIVE_SMS permission and log a warning if not granted,
        // as it's crucial for automatic backups.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECEIVE_SMS permission not granted. Automatic SMS backup will not function.")
            // User is informed about this via the lastSyncStatusTextView in loadAndDisplayLastSyncTimestamp()
        }
    }

    /**
     * Initiates the Google Sign-In flow by launching the sign-in intent.
     * The result of this flow is handled by the [signInLauncher].
     */
    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent) // Start the sign-in activity
    }

    /**
     * Handles the result of the Google Sign-In attempt.
     * If successful, updates the UI with the signed-in account information.
     * If failed, logs the error and updates the UI to a signed-out state.
     * @param completedTask The Task containing the result of the sign-in attempt.
     */
    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java) // Throws ApiException on failure
            // Sign-in was successful
            Log.d(TAG, "signInResult:success, user: ${account.email}")
            updateUI(account) // Update UI to reflect signed-in state
        } catch (e: ApiException) {
            // Sign-in failed
            Log.w(TAG, "signInResult:failed code=" + e.statusCode + ", message=" + e.message)
            updateUI(null) // Update UI to reflect signed-out state
        }
    }

    /**
     * Signs out the current user and updates the UI.
     */
    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener(this) {
            // Callback after sign-out attempt completes
            updateUI(null) // Update UI to reflect signed-out state
            Log.d(TAG, "Signed out successfully.")
        }
    }

    /**
     * Updates the user interface based on the provided Google Sign-In account.
     * Shows/hides buttons and updates text views to reflect the current authentication state.
     * @param account The currently signed-in GoogleSignInAccount, or null if no user is signed in.
     */
    private fun updateUI(account: GoogleSignInAccount?) {
        if (account != null) {
            // User is signed in
            statusTextView.text = getString(R.string.signed_in_as, account.email)
            userInfoTextView.text = "User: ${account.displayName ?: "N/A"}\nEmail: ${account.email}"
            signInButton.visibility = View.GONE // Hide sign-in button
            signOutButton.visibility = View.VISIBLE // Show sign-out button
            manualSyncButton.isEnabled = true // Enable manual sync
        } else {
            // User is signed out
            statusTextView.text = getString(R.string.not_signed_in)
            userInfoTextView.text = "" // Clear user info
            signInButton.visibility = View.VISIBLE // Show sign-in button
            signOutButton.visibility = View.GONE // Hide sign-out button
            manualSyncButton.isEnabled = false // Disable manual sync
        }
    }

    /**
     * Reads all SMS messages from the device's content provider.
     * Requires READ_SMS permission, which should be granted before calling this.
     * @return A list of [SmsData] objects representing the SMS messages found.
     * Returns an empty list if no messages are found or if the cursor is null.
     */
    private fun readAllSms(): List<SmsData> {
        Log.d(TAG, "Attempting to read all SMS messages from device.")
        val smsList = mutableListOf<SmsData>()
        val contentResolver: android.content.ContentResolver = contentResolver
        // Define the columns to retrieve from the SMS content provider
        val projection = arrayOf(
            Telephony.Sms.ADDRESS, // Sender's address (phone number)
            Telephony.Sms.BODY,    // Message body
            Telephony.Sms.DATE     // Date the message was sent or received (timestamp)
        )

        // Query the SMS content provider
        // Telephony.Sms.CONTENT_URI: URI for all SMS messages
        // projection: The columns to return
        // null selection: Return all rows (no filtering)
        // null selectionArgs: No arguments for selection
        // Telephony.Sms.DEFAULT_SORT_ORDER: Sort results by date in descending order (newest first)
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            Telephony.Sms.DEFAULT_SORT_ORDER
        )

        // Process the cursor if it's not null
        cursor?.use { // 'use' ensures the cursor is closed automatically
            // Get column indices for faster access within the loop
            val addressColumn = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = it.getColumnIndexOrThrow(Telephony.Sms.DATE)

            // Iterate over each row in the cursor
            while (it.moveToNext()) {
                val sender = it.getString(addressColumn)
                val body = it.getString(bodyColumn)
                val timestamp = it.getLong(dateColumn)
                // Create SmsData object and add to list. Use "Unknown" if sender or body is null.
                smsList.add(SmsData(sender ?: "Unknown", body ?: "", timestamp))
            }
            Log.d(TAG, "Finished reading SMS. Found ${smsList.size} messages.")
        } ?: Log.w(TAG, "Cursor was null, could not read SMS messages.") // Log if cursor is null

        return smsList
    }

    /**
     * Callback for the result from requesting permissions.
     * This method is invoked for every call on [requestPermissions].
     * @param requestCode The request code passed in [requestPermissions].
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions which is either [PackageManager.PERMISSION_GRANTED] or [PackageManager.PERMISSION_DENIED]. Never null.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_READ_SMS -> { // Handle result for READ_SMS permission request
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    // Permission was granted
                    Log.d(TAG, "READ_SMS permission granted by user after request.")
                    // Inform user they can now try syncing again
                    syncStatusTextView.text = getString(R.string.permission_read_sms_granted_manual_sync_instruction)
                } else {
                    // Permission was denied
                    Log.w(TAG, "READ_SMS permission denied by user after request.")
                    // Explain that the feature is unavailable without the permission
                    syncStatusTextView.text = getString(R.string.permission_read_sms_denied_message)
                }
                return // Exit after handling this specific request code
            }
            // TODO: Handle other permission request codes here if any are added in the future.
        }
    }

    /**
     * Handles the manual SMS sync process when the "Manual Sync" button is clicked.
     * This function orchestrates the necessary pre-checks (READ_SMS permission, network availability,
     * Google Sign-In status) and then initiates the SMS synchronization if all checks pass.
     * The actual synchronization logic is performed by [performSmsSync], and UI updates
     * reflecting the sync progress and result are handled here.
     */
    private fun handleManualSync() {
        // Step 1: Check for READ_SMS Permission.
        // If not granted, request it. The actual sync will not proceed until permission is granted.
        // The result of the permission request is handled by onRequestPermissionsResult.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_SMS)) {
                // Show rationale if user previously denied without "Don't ask again"
                Log.i(TAG, "Showing READ_SMS permission rationale to the user.")
                syncStatusTextView.text = getString(R.string.permission_read_sms_rationale)
                // Request permission again after showing rationale (or instruct user to click button again)
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), REQUEST_CODE_READ_SMS)
            } else {
                // Request permission for the first time or if "Don't ask again" was selected
                Log.i(TAG, "Requesting READ_SMS permission.")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), REQUEST_CODE_READ_SMS)
            }
            return // Exit and wait for user's response to permission request
        }

        // Step 2: Check for Network Connectivity.
        // If no network, inform the user and do not proceed with sync.
        if (!NetworkUtils.isNetworkAvailable(this)) {
            syncStatusTextView.text = getString(R.string.error_no_network)
            Log.w(TAG, "Manual Sync: No network connection.")
            syncProgressBar.visibility = View.GONE // Ensure progress bar is hidden
            return
        }

        // Step 3: Check for Google Sign-In.
        // If no user is signed in, inform the user and do not proceed.
        val googleSignInAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (googleSignInAccount == null) {
            syncStatusTextView.text = getString(R.string.please_sign_in_sync)
            Log.w(TAG, "Manual Sync: User not signed in.")
            syncProgressBar.visibility = View.GONE // Ensure progress bar is hidden
            return
        }

        // Step 4: All pre-checks passed. Proceed with the synchronization logic.
        Log.d(TAG, "Manual Sync: Pre-checks passed (Permissions, Network, Sign-In). User: ${googleSignInAccount.email}")
        syncProgressBar.visibility = View.VISIBLE // Show progress bar
        syncStatusTextView.text = getString(R.string.manual_sync_starting) // Initial status update

        // Launch a coroutine in the lifecycle scope to perform the sync operation off the main thread.
        lifecycleScope.launch {
            // Call the suspend function that encapsulates the core sync logic.
            val syncResult = performSmsSync(googleSignInAccount)

            // After performSmsSync completes, update the UI on the main thread.
            withContext(Dispatchers.Main) {
                syncProgressBar.visibility = View.GONE // Hide progress bar

                // Update syncStatusTextView based on the outcome of performSmsSync
                if (syncResult.error != null) {
                    // An error occurred during sync
                    syncStatusTextView.text = syncResult.error
                } else if (syncResult.smsListEmpty) {
                    // No SMS messages were found on the device to back up
                    syncStatusTextView.text = getString(R.string.error_no_sms_on_device)
                } else if (syncResult.newMessagesBackedUp == 0) {
                    // All messages were already backed up (duplicates) or no new messages processed
                    syncStatusTextView.text = getString(R.string.manual_sync_complete_no_new_messages, syncResult.messagesProcessed)
                } else {
                    // Sync was successful and new messages were backed up
                    syncStatusTextView.text = getString(R.string.manual_sync_complete, syncResult.newMessagesBackedUp, syncResult.messagesProcessed)
                    // Save timestamp only if messages were processed or backed up.
                    if (syncResult.messagesProcessed > 0 || syncResult.newMessagesBackedUp > 0) {
                        saveLastSyncTimestamp() // Persist the current time as the last sync time
                        updateLastSyncStatus(getString(R.string.last_sync_status, formatTimestamp(System.currentTimeMillis())))
                    }
                }
                Log.i(TAG, "Manual Sync: Process finished. UI updated with result: ${syncStatusTextView.text}")
            }
        }
    }

    /**
     * Data class to hold the results of the SMS synchronization process.
     * @property newMessagesBackedUp Count of new messages successfully backed up.
     * @property messagesProcessed Total number of messages processed from the device.
     * @property error A user-friendly error message if the sync failed, null otherwise.
     * @property smsListEmpty True if no SMS messages were found on the device, false otherwise.
     */
    private data class SyncResult(
        val newMessagesBackedUp: Int,
        val messagesProcessed: Int,
        val error: String?,
        val smsListEmpty: Boolean
    )

    /**
     * Performs the core SMS synchronization logic.
     * This function reads SMS messages, interacts with [GoogleSheetsService] to find/create
     * a spreadsheet, checks for duplicate messages, and appends new messages.
     * It should be called from a coroutine.
     *
     * @param googleSignInAccount The currently signed-in Google account, used to initialize [GoogleSheetsService].
     * @return A [SyncResult] object containing the outcome of the synchronization.
     */
    private suspend fun performSmsSync(googleSignInAccount: GoogleSignInAccount): SyncResult {
        var newMessagesBackedUpCount = 0
        var messagesProcessedCount = 0
        var errorMessage: String? = null
        var smsListWasEmpty = false // Flag to indicate if the device has any SMS messages
        // Initialize GoogleSheetsService for interacting with Google Sheets API
        val sheetService = GoogleSheetsService(applicationContext, googleSignInAccount)

        try {
            // Read all SMS messages from the device (this happens on an IO dispatcher)
            val smsList = withContext(Dispatchers.IO) { readAllSms() }
            messagesProcessedCount = smsList.size
            smsListWasEmpty = smsList.isEmpty()

            if (smsListWasEmpty) {
                Log.d(TAG, "performSmsSync: No SMS messages found on device.")
                // If no SMS messages, no further sheet operations are needed.
                // The SyncResult will indicate that smsListWasEmpty is true.
            } else {
                // SMS messages found, proceed with Google Sheets operations.
                Log.d(TAG, "performSmsSync: Found ${smsList.size} SMS. Proceeding with sheet operations.")

                // Find or create the target spreadsheet. This can throw IOException on network issues.
                val currentSpreadsheetId = withContext(Dispatchers.IO) { sheetService.findOrCreateSpreadsheet() }
                if (currentSpreadsheetId == null) {
                    // This case implies a non-IOException error from findOrCreateSpreadsheet,
                    // or an unexpected null return without an exception.
                    throw IOException(getString(R.string.error_sheet_permission)) // Treat as a general sheet error
                }
                Log.d(TAG, "performSmsSync: Using spreadsheet ID: $currentSpreadsheetId")

                // Get IDs of SMS messages already present in the Google Sheet to avoid duplicates.
                val existingSmsIds = withContext(Dispatchers.IO) { sheetService.getExistingSmsIds() }
                Log.d(TAG, "performSmsSync: Found ${existingSmsIds.size} existing SMS IDs in sheet.")

                var currentIterationProcessed = 0 // Counter for UI progress updates
                for (sms in smsList) {
                    currentIterationProcessed++
                    // Update UI progress intermittently on the Main thread to avoid overwhelming it.
                    if (currentIterationProcessed % 5 == 0 || currentIterationProcessed == smsList.size) {
                        withContext(Dispatchers.Main) {
                            syncStatusTextView.text = getString(R.string.processed_messages_status, currentIterationProcessed, smsList.size)
                        }
                    }

                    // Generate a unique ID for the current SMS to check for duplicates and for storage.
                    val currentSmsId = withContext(Dispatchers.IO) { sheetService.generateSmsId(sms) }

                    // If the SMS is not already in the sheet, append it.
                    if (!existingSmsIds.contains(currentSmsId)) {
                        val success = withContext(Dispatchers.IO) { sheetService.appendSmsToSheet(sms) }
                        if (success) {
                            newMessagesBackedUpCount++
                            Log.d(TAG, "performSmsSync: Backed up new SMS - ID: $currentSmsId")
                        } else {
                            // Log if a specific SMS fails to append, but continue with others.
                            // A more robust error handling might collect these individual failures.
                            Log.e(TAG, "performSmsSync: Failed to backup SMS - ID: $currentSmsId, Sender: ${sms.sender}")
                        }
                    } else {
                        // This SMS is a duplicate, already exists in the sheet.
                        Log.d(TAG, "performSmsSync: SMS already exists (duplicate) - ID: $currentSmsId")
                    }
                }
            }
        } catch (e: Exception) {
            // Catch any exception during the sync process (e.g., network, API errors).
            Log.e(TAG, "performSmsSync: Error during sync process", e)
            // Convert the exception to a user-friendly error message.
            errorMessage = sheetService.getErrorMessageForException(e)
        }
        // Return the populated SyncResult.
        return SyncResult(newMessagesBackedUpCount, messagesProcessedCount, errorMessage, smsListWasEmpty)
    }

    /**
     * Saves the current timestamp to SharedPreferences, marking the time of the last successful sync.
     */
    private fun saveLastSyncTimestamp() {
        val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply()
        Log.d(TAG, "Saved last sync timestamp to SharedPreferences.")
    }

    /**
     * Loads the last sync timestamp from SharedPreferences and updates the UI to display it.
     * Also appends a warning if the RECEIVE_SMS permission is missing.
     */
    private fun loadAndDisplayLastSyncTimestamp() {
        val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lastSyncTs = sharedPrefs.getLong("last_sync_timestamp", 0L) // 0L is default if not found
        var statusText: String
        if (lastSyncTs > 0) {
            // If a timestamp exists, format it and display.
            statusText = getString(R.string.last_sync_status, formatTimestamp(lastSyncTs))
        } else {
            // If no timestamp (e.g., first run), display "Never".
            statusText = getString(R.string.last_sync_status, getString(R.string.last_sync_never))
        }

        // Append a warning if RECEIVE_SMS permission is not granted, as it affects automatic backups.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            statusText += "\n${getString(R.string.warning_receive_sms_permission_missing)}"
        }
        updateLastSyncStatus(statusText)
    }

    /**
     * Formats a given timestamp (Long) into a human-readable date and time string.
     * @param timestamp The timestamp in milliseconds since the epoch.
     * @return A formatted date-time string (e.g., "yyyy-MM-dd HH:mm:ss").
     */
    private fun formatTimestamp(timestamp: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }

    /**
     * Updates the [lastSyncStatusTextView] with the provided message.
     * @param message The status message to display.
     */
    private fun updateLastSyncStatus(message: String) {
        lastSyncStatusTextView.text = message
    }

}
// Android Manifest and other permission related imports are grouped here for better readability
import android.Manifest // Required for Manifest.permission.READ_SMS
import android.content.Context // For SharedPreferences
import android.content.pm.PackageManager // Required for PackageManager.PERMISSION_GRANTED
import androidx.core.app.ActivityCompat // For permission requests
import androidx.core.content.ContextCompat // Required for ContextCompat.checkSelfPermission
// import androidx.core.app.ActivityCompat // Required for ActivityCompat.requestPermissions (if implementing request directly here)

import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.ProgressBar
import java.io.IOException
