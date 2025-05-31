package com.example.smsbackuptodrive

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.JobIntentService
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class SmsSyncService : JobIntentService() {

    companion object {
        private const val JOB_ID = 1001 // Unique job ID.
        private const val TAG = "SmsSyncService"

        const val EXTRA_SENDER = "com.example.smsbackuptodrive.EXTRA_SENDER"
        const val EXTRA_BODY = "com.example.smsbackuptodrive.EXTRA_BODY"
        const val EXTRA_TIMESTAMP = "com.example.smsbackuptodrive.EXTRA_TIMESTAMP"

        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(context, SmsSyncService::class.java, JOB_ID, work)
        }
    }

    override fun onHandleWork(intent: Intent) {
        Log.d(TAG, "onHandleWork started for intent: $intent")

        if (!NetworkUtils.isNetworkAvailable(applicationContext)) {
            Log.w(TAG, "No network connection. Service will not run at this time.")
            // TODO: Implement retry logic here if desired (e.g., using JobScheduler for a future attempt)
            return
        }

        val sender = intent.getStringExtra(EXTRA_SENDER)
        val body = intent.getStringExtra(EXTRA_BODY)
        val timestamp = intent.getLongExtra(EXTRA_TIMESTAMP, 0L)

        if (sender == null || body == null || timestamp == 0L) {
            Log.e(TAG, "Invalid SMS data received in intent. Aborting.")
            return
        }

        val googleSignInAccount = GoogleSignIn.getLastSignedInAccount(applicationContext)
        if (googleSignInAccount == null) {
            Log.e(TAG, "No signed-in Google account found. Cannot sync SMS. Aborting.")
            return
        }

        Log.d(TAG, "User signed in: ${googleSignInAccount.email}. Proceeding with SMS sync.")

        val sheetService = GoogleSheetsService(applicationContext, googleSignInAccount)

        // Using runBlocking here to ensure the suspend functions complete within onHandleWork.
        // JobIntentService manages wake locks, so this should be safe.
        // For more complex scenarios, a custom CoroutineScope with structured concurrency might be preferred.
        runBlocking(Dispatchers.IO) {
            try {
                val smsData = SmsData(id = "", sender = sender, body = body, timestamp = timestamp)

                val currentSpreadsheetId = sheetService.findOrCreateSpreadsheet()
                if (currentSpreadsheetId == null) {
                    Log.e(TAG, "Failed to find or create spreadsheet (it might be a network issue or API error). Aborting sync for this SMS.")
                    // sheetService.findOrCreateSpreadsheet() now throws IOException on network error,
                    // which would be caught by the outer catch block.
                    // If it returns null for other reasons (e.g. API error not throwing IOException), this check is valid.
                    return@runBlocking
                }
                Log.d(TAG, "Using spreadsheet ID: $currentSpreadsheetId")

                // GoogleSheetsService.generateSmsId is now public.
                val smsIdToUpload = sheetService.generateSmsId(smsData)
                Log.d(TAG, "Generated SMS ID for current message: $smsIdToUpload")

                val existingSmsIds = sheetService.getExistingSmsIds()
                // If getExistingSmsIds returns empty due to an error (like network), existingSmsIds.contains will still work.
                // The error would have been logged by GoogleSheetsService.

                if (!existingSmsIds.contains(smsIdToUpload)) {
                    Log.d(TAG, "SMS with ID $smsIdToUpload is new. Appending to sheet.")
                    val success = sheetService.appendSmsToSheet(smsData)
                    if (success) {
                        Log.i(TAG, "SMS from $sender synced successfully to Google Sheets.")
                    } else {
                        Log.e(TAG, "Failed to sync SMS from $sender to Google Sheets. Check GoogleSheetsService logs for details (e.g. network, API error).")
                    }
                } else {
                    Log.i(TAG, "SMS with ID $smsIdToUpload is a duplicate. Not adding to sheet.")
                }

            } catch (e: Exception) { // Catching general Exception from sheetService calls (e.g. IOException from network check)
                Log.e(TAG, "Error during SMS sync process in onHandleWork: ${sheetService.getErrorMessageForException(e)}", e)
            }
        }
        Log.d(TAG, "onHandleWork finished.")
    }
}

// Removed internal extension function as GoogleSheetsService.generateSmsId is now public.
