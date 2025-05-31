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

class MainActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var signInButton: SignInButton
    private lateinit var signOutButton: Button
    private lateinit var statusTextView: TextView
    private lateinit var userInfoTextView: TextView
    private lateinit var readSmsButton: Button
    private lateinit var manualSyncButton: Button // For Manual Sync
    private lateinit var syncProgressBar: ProgressBar
    private lateinit var syncStatusTextView: TextView
    private lateinit var lastSyncStatusTextView: TextView // New TextView

    private lateinit var signInLauncher: ActivityResultLauncher<Intent>

    companion object {
        private const val RC_SIGN_IN = 9001
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_READ_SMS = 101 // For runtime permission
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        signInButton = findViewById(R.id.sign_in_button)
        signOutButton = findViewById(R.id.sign_out_button)
        statusTextView = findViewById(R.id.status_textview)
        userInfoTextView = findViewById(R.id.user_info_textview)
        readSmsButton = findViewById(R.id.read_sms_button)
        manualSyncButton = findViewById(R.id.manual_sync_button)
        syncProgressBar = findViewById(R.id.sync_progress_bar)
        syncStatusTextView = findViewById(R.id.sync_status_textview)
        lastSyncStatusTextView = findViewById(R.id.last_sync_status_textview) // Initialize new

        loadAndDisplayLastSyncTimestamp() // Load and display persisted sync status

        // Configure Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.server_client_id))
            .requestEmail()
            .requestScopes(com.google.android.gms.common.api.Scope(com.google.api.services.drive.DriveScopes.DRIVE_FILE),
                           com.google.android.gms.common.api.Scope(com.google.api.services.sheets.v4.SheetsScopes.SPREADSHEETS))
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, gso)

        signInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                handleSignInResult(task)
            } else {
                Log.w(TAG, "Sign-in failed. Result code: ${result.resultCode}")
                updateUI(null)
            }
        }

        signInButton.setOnClickListener {
            signIn()
        }

        signOutButton.setOnClickListener {
            signOut()
        }

        readSmsButton.setOnClickListener {
            // TODO: Implement runtime permission check for READ_SMS before calling readAllSms
            // For now, directly calling, assuming permission is granted or will be handled.
            Log.d(TAG, "Read SMS button clicked. Requesting READ_SMS permission conceptually.")
            // Ideally, you'd check for permission here:
            // if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
            //     val smsList = readAllSms()
            //     Log.d(TAG, "SMS Read Count: ${smsList.size}")
            //     smsList.take(5).forEach { // Log first 5 messages for brevity
            //         Log.d(TAG, "SMS: Sender: ${it.sender}, Body: ${it.body.take(50)}, Date: ${it.timestamp}")
            //     }
            // } else {
            //     Log.w(TAG, "READ_SMS permission not granted. Cannot read SMS.")
            //     // ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), YOUR_REQUEST_CODE_READ_SMS)
            // }
            // For this subtask, we'll just log the attempt and the fact that permission handling is needed.
            // Calling the function directly to ensure it compiles, but it won't work without permissions on a real device.
            // This button's primary role is now superseded by manual sync, consider removing or repurposing.
            // For now, keeping its logic but commenting out direct call to avoid confusion with manual sync.
            // val smsList = readAllSms()
            // Log.d(TAG, "SMS Read attempt. Count (if permission granted): ${smsList.size}")
            // if (smsList.isNotEmpty()) {
            //     smsList.take(5).forEach { // Log first 5 messages for brevity
            //          Log.d(TAG, "SMS: Sender: ${it.sender}, Body: ${it.body.take(50)}, Date: ${it.timestamp}")
            //     }
            // } else {
            //     Log.d(TAG, "No SMS messages found or permission READ_SMS not granted.")
            // }
            Log.d(TAG, "'Read All SMS (Dev)' button clicked. Functionality is developer-oriented or being phased out by Manual Sync.")
            // Optionally, trigger a permission check here too if this button is kept for testing readAllSms independently
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) {
                val smsListDev = readAllSms()
                Log.d(TAG, "DEV READ: SMS Read Count: ${smsListDev.size}")
            } else {
                 ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), REQUEST_CODE_READ_SMS)
            }
        }

        manualSyncButton.setOnClickListener {
            handleManualSync()
        }
    }

    override fun onStart() {
        super.onStart()
        val account = GoogleSignIn.getLastSignedInAccount(this)
        updateUI(account)
        loadAndDisplayLastSyncTimestamp() // Refresh in case it changed

        // Conceptual check for RECEIVE_SMS permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECEIVE_SMS permission not granted. Automatic SMS backup will not function.")
            // Consider showing a persistent message to the user here or in a dedicated settings/status screen.
            // For this subtask, just logging. Example:
            // lastSyncStatusTextView.text = getString(R.string.permissions_needed_critical) // Update appropriate TextView
        }
    }

    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        signInLauncher.launch(signInIntent)
    }

    private fun handleSignInResult(completedTask: Task<GoogleSignInAccount>) {
        try {
            val account = completedTask.getResult(ApiException::class.java)
            // Signed in successfully, show authenticated UI.
            Log.d(TAG, "signInResult:success")
            updateUI(account)
        } catch (e: ApiException) {
            // The ApiException status code indicates the detailed failure reason.
            // Please refer to the GoogleSignInStatusCodes class reference for more information.
            Log.w(TAG, "signInResult:failed code=" + e.statusCode)
            updateUI(null)
        }
    }

    private fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener(this) {
            updateUI(null)
            Log.d(TAG, "Signed out successfully")
        }
    }

    private fun updateUI(account: GoogleSignInAccount?) {
        if (account != null) {
            statusTextView.text = getString(R.string.signed_in_as, account.email) // Using email for main status
            userInfoTextView.text = "User: ${account.displayName ?: "N/A"}\nEmail: ${account.email}" // More detailed user info
            signInButton.visibility = View.GONE
            signOutButton.visibility = View.VISIBLE
            manualSyncButton.isEnabled = true
            readSmsButton.isEnabled = true // Dev button
        } else {
            statusTextView.text = getString(R.string.not_signed_in)
            userInfoTextView.text = "" // Clear user details
            signInButton.visibility = View.VISIBLE
            signOutButton.visibility = View.GONE
            manualSyncButton.isEnabled = false
            readSmsButton.isEnabled = false // Dev button
        }
    }

    private fun readAllSms(): List<SmsData> {
        // TODO: Implement proper runtime permission request for READ_SMS.
        // This function assumes permission is already granted for direct testing.
        // On a real device, if permission is not granted, this will likely throw a SecurityException
        // or return an empty cursor.

        Log.d(TAG, "Attempting to read all SMS messages.")
        // Add a check here: if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) { Log.w(TAG, "READ_SMS permission not granted."); return emptyList() }


        val smsList = mutableListOf<SmsData>()
        val contentResolver: android.content.ContentResolver = contentResolver
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null, // Read all SMS; no selection criteria
            null,
            Telephony.Sms.DEFAULT_SORT_ORDER // Sort by date descending
        )

        cursor?.use {
            val idColumn = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressColumn = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyColumn = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateColumn = it.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (it.moveToNext()) {
                val id = it.getString(idColumn)
                val sender = it.getString(addressColumn)
                val body = it.getString(bodyColumn)
                val timestamp = it.getLong(dateColumn)
                smsList.add(SmsData(id, sender ?: "Unknown", body ?: "", timestamp))
            }
            Log.d(TAG, "Finished reading SMS. Found ${smsList.size} messages.")
        } ?: Log.w(TAG, "Cursor was null, could not read SMS.")

        return smsList
    }

    // Handle results from permission requests
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_READ_SMS -> {
                if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    Log.d(TAG, "READ_SMS permission granted by user after request.")
                    syncStatusTextView.text = "READ_SMS permission granted. Click 'Manual Sync' again."
                    // Optionally, trigger handleManualSync() here directly, but be cautious of loops if rationale is shown.
                    // handleManualSync() // Re-check permission if calling directly
                } else {
                    Log.w(TAG, "READ_SMS permission denied by user after request.")
                    syncStatusTextView.text = getString(R.string.permission_read_sms_denied)
                    // Explain that the feature is unavailable.
                }
                return
            }
            // Handle other permission request codes here if any
        }
    }

    private fun handleManualSync() {
        // 1. Check for READ_SMS Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_SMS)) {
                Log.i(TAG, "Showing READ_SMS permission rationale to the user.")
                syncStatusTextView.text = getString(R.string.permission_read_sms_rationale)
                // Here you would typically show a dialog. For this subtask, updating TextView.
                // After rationale, user needs to click button again, or you can directly call requestPermissions.
                // To request immediately after rationale for simplicity in this non-dialog version:
                 ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), REQUEST_CODE_READ_SMS)
            } else {
                Log.i(TAG, "Requesting READ_SMS permission (first time or 'Don't ask again' was selected).")
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_SMS), REQUEST_CODE_READ_SMS)
            }
            return // Exit and wait for onRequestPermissionsResult
        }

        // 2. Check for Network
        if (!NetworkUtils.isNetworkAvailable(this)) {
            syncStatusTextView.text = getString(R.string.error_no_network)
            Log.w(TAG, "Manual Sync: No network connection.")
            syncProgressBar.visibility = View.GONE // Ensure progress bar is hidden
            return
        }

        // 3. Check for Google Sign-In
        val googleSignInAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (googleSignInAccount == null) {
            syncStatusTextView.text = getString(R.string.please_sign_in_sync)
            Log.w(TAG, "Manual Sync: User not signed in.")
            return
        }

        // 4. Proceed with Sync Logic (if permissions, network, and sign-in are OK)
        Log.d(TAG, "Manual Sync: Permissions, Network & Sign-In OK. User: ${googleSignInAccount.email}")
        syncProgressBar.visibility = View.VISIBLE
        syncStatusTextView.text = getString(R.string.manual_sync_starting)

        // Initialize sheetService here to use its error message helper in catch block
        val sheetService = GoogleSheetsService(applicationContext, googleSignInAccount)

        lifecycleScope.launch {
            var newMessagesBackedUpCount = 0
            var messagesProcessedCount = 0
            var errorMessage: String? = null

            try {
                val smsList = withContext(Dispatchers.IO) { readAllSms() }
                messagesProcessedCount = smsList.size

                if (smsList.isEmpty()) {
                    Log.d(TAG, "Manual Sync: No SMS messages found on device.")
                    withContext(Dispatchers.Main) {
                        syncStatusTextView.text = getString(R.string.error_no_sms_on_device) // Use specific string
                    }
                } else {
                    Log.d(TAG, "Manual Sync: Found ${smsList.size} SMS. Proceeding with sheet operations.")
                    // sheetService is already initialized before lifecycleScope.launch

                    val currentSpreadsheetId = withContext(Dispatchers.IO) { sheetService.findOrCreateSpreadsheet() }
                    // findOrCreateSpreadsheet now throws IOException on network error, caught by outer try-catch
                    if (currentSpreadsheetId == null) {
                        // This case implies a non-IOException error from findOrCreateSpreadsheet (e.g. API error not re-thrown as IOException)
                        throw IOException(getString(R.string.error_sheet_permission)) // Or a more specific error from sheetService
                    }
                    Log.d(TAG, "Manual Sync: Using spreadsheet ID: $currentSpreadsheetId")

                    val existingSmsIds = withContext(Dispatchers.IO) { sheetService.getExistingSmsIds() }
                    // getExistingSmsIds also logs errors internally and returns emptyList on failure.
                    Log.d(TAG, "Manual Sync: Found ${existingSmsIds.size} existing SMS IDs in sheet.")

                    var currentIterationProcessed = 0
                    for (sms in smsList) {
                        currentIterationProcessed++
                        val currentSmsId = withContext(Dispatchers.IO) { sheetService.generateSmsId(sms) }

                        if (!existingSmsIds.contains(currentSmsId)) {
                            val success = withContext(Dispatchers.IO) { sheetService.appendSmsToSheet(sms) }
                            if (success) {
                                newMessagesBackedUpCount++
                                Log.d(TAG, "Manual Sync: Backed up new SMS - ID: $currentSmsId")
                            } else {
                                Log.e(TAG, "Manual Sync: Failed to backup SMS - ID: $currentSmsId, Sender: ${sms.sender}")
                            }
                        } else {
                            Log.d(TAG, "Manual Sync: SMS already exists (duplicate) - ID: $currentSmsId")
                        }

                        if (currentIterationProcessed % 5 == 0 || currentIterationProcessed == smsList.size) {
                            withContext(Dispatchers.Main) {
                                syncStatusTextView.text = getString(R.string.processed_messages_status, currentIterationProcessed, smsList.size)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Manual Sync: Error during sync process", e)
                errorMessage = sheetService.getErrorMessageForException(e) // Use helper from sheetService
            }

            withContext(Dispatchers.Main) {
                syncProgressBar.visibility = View.GONE
                if (errorMessage != null) {
                    syncStatusTextView.text = errorMessage // Display the user-friendly error
                } else {
                    syncStatusTextView.text = getString(R.string.manual_sync_complete, newMessagesBackedUpCount, messagesProcessedCount)
                    if (messagesProcessedCount > 0 || newMessagesBackedUpCount > 0) {
                        saveLastSyncTimestamp()
                        updateLastSyncStatus(getString(R.string.last_sync_status, formatTimestamp(System.currentTimeMillis())))
                    }
                }
                Log.i(TAG, "Manual Sync: Process finished. Result: ${syncStatusTextView.text}")
            }
        }
    }

    private fun saveLastSyncTimestamp() {
        val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        sharedPrefs.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply()
        Log.d(TAG, "Saved last sync timestamp.")
    }

    private fun loadAndDisplayLastSyncTimestamp() {
        val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lastSyncTs = sharedPrefs.getLong("last_sync_timestamp", 0L)
        if (lastSyncTs > 0) {
            updateLastSyncStatus(getString(R.string.last_sync_status, formatTimestamp(lastSyncTs)))
        } else {
            updateLastSyncStatus(getString(R.string.last_sync_status, getString(R.string.last_sync_never)))
        }
        // Update warning for RECEIVE_SMS permission if needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            val currentStatus = lastSyncStatusTextView.text.toString()
            lastSyncStatusTextView.text = "$currentStatus\n${getString(R.string.warning_receive_sms_permission_missing)}"
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        // Simple date formatting
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))
    }

    private fun updateLastSyncStatus(message: String) {
        lastSyncStatusTextView.text = message
    }

}
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
