package com.example.smsbackuptodrive

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import java.io.IOException

class SmsSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SmsSyncWorker"
        // Input data keys
        const val INPUT_DATA_SENDER = "INPUT_DATA_SENDER"
        const val INPUT_DATA_BODY = "INPUT_DATA_BODY"
        const val INPUT_DATA_TIMESTAMP = "INPUT_DATA_TIMESTAMP"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork started.")

        if (!NetworkUtils.isNetworkAvailable(applicationContext)) {
            Log.w(TAG, "No network connection. Retrying work.")
            return Result.retry()
        }

        val sender = inputData.getString(INPUT_DATA_SENDER)
        val body = inputData.getString(INPUT_DATA_BODY)
        val timestamp = inputData.getLong(INPUT_DATA_TIMESTAMP, 0L)

        if (sender == null || body == null || timestamp == 0L) {
            Log.e(TAG, "Invalid SMS data received. Failing work.")
            // This is considered a permanent failure as the input data is bad.
            return Result.failure()
        }

        val googleSignInAccount = GoogleSignIn.getLastSignedInAccount(applicationContext)
        if (googleSignInAccount == null) {
            Log.e(TAG, "No signed-in Google account found. Cannot sync SMS. Failing work.")
            // This could be a temporary issue if the user signs in later, but
            // WorkManager's default retry might not be appropriate.
            // For simplicity, treating as failure. Could be configured with backoff policy.
            return Result.failure()
        }

        Log.d(TAG, "User signed in: ${googleSignInAccount.email}. Proceeding with SMS sync.")
        val sheetService = GoogleSheetsService(applicationContext, googleSignInAccount)

        try {
            val smsData = SmsData(id = "", sender = sender, body = body, timestamp = timestamp)

            val currentSpreadsheetId = sheetService.findOrCreateSpreadsheet()
            // findOrCreateSpreadsheet now throws exceptions for errors.
            // If it somehow returns null without an exception (should not happen with current impl),
            // it's an unexpected state.
            if (currentSpreadsheetId == null) {
                 Log.e(TAG, "Failed to find or create spreadsheet (unexpected null). Failing work.")
                 return Result.failure()
            }
            Log.d(TAG, "Using spreadsheet ID: $currentSpreadsheetId")

            val smsIdToUpload = sheetService.generateSmsId(smsData)
            Log.d(TAG, "Generated SMS ID for current message: $smsIdToUpload")

            val existingSmsIds = sheetService.getExistingSmsIds()
            // Errors in getExistingSmsIds (like network) will throw an exception caught below.

            if (!existingSmsIds.contains(smsIdToUpload)) {
                Log.d(TAG, "SMS with ID $smsIdToUpload is new. Appending to sheet.")
                val success = sheetService.appendSmsToSheet(smsData)
                if (success) {
                    Log.i(TAG, "SMS from $sender synced successfully to Google Sheets.")
                    return Result.success()
                } else {
                    // appendSmsToSheet returning false implies an issue not caught as an exception,
                    // e.g., API returned success=false but no HTTP error.
                    Log.e(TAG, "Failed to sync SMS from $sender to Google Sheets (appendSmsToSheet returned false). Failing work.")
                    return Result.failure()
                }
            } else {
                Log.i(TAG, "SMS with ID $smsIdToUpload is a duplicate. Not adding to sheet. Work successful.")
                return Result.success()
            }

        } catch (e: GoogleAuthIOException) {
            // Specific handling for auth token issues - likely requires user interaction.
            Log.e(TAG, "Authentication error during SMS sync: ${sheetService.getErrorMessageForException(e)}", e)
            return Result.failure() // Or consider a specific output for UI feedback
        } catch (e: IOException) {
            // Network errors or other I/O problems.
            Log.e(TAG, "Network or I/O error during SMS sync: ${sheetService.getErrorMessageForException(e)}", e)
            return Result.retry()
        } catch (e: Exception) {
            // Other unexpected errors (e.g., API errors not fitting IOException, programming errors).
            Log.e(TAG, "Unexpected error during SMS sync: ${sheetService.getErrorMessageForException(e)}", e)
            // Depending on the nature of 'e', this might be a candidate for retry or failure.
            // For safety, failing here. If specific exceptions are known to be retryable, handle them explicitly.
            return Result.failure()
        } finally {
            Log.d(TAG, "doWork finished.")
        }
    }
}
