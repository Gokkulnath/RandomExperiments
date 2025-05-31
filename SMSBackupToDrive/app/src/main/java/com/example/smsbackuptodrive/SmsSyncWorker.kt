package com.example.smsbackuptodrive

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAuthIOException
import java.io.IOException

/**
 * A [CoroutineWorker] responsible for synchronizing a single SMS message to Google Sheets.
 * It handles network checks, Google Sign-In validation, and interacts with [GoogleSheetsService]
 * to perform the actual sheet operations. The worker supports retry mechanisms for transient errors.
 *
 * @param appContext The application context.
 * @param workerParams Parameters to configure the worker, including input data.
 */
class SmsSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SmsSyncWorker" // Logcat tag

        /** Key for accessing the sender's phone number from the input data. */
        const val INPUT_DATA_SENDER = "INPUT_DATA_SENDER"
        /** Key for accessing the SMS message body from the input data. */
        const val INPUT_DATA_BODY = "INPUT_DATA_BODY"
        /** Key for accessing the SMS timestamp from the input data. */
        const val INPUT_DATA_TIMESTAMP = "INPUT_DATA_TIMESTAMP"
    }

    /**
     * The main entry point for the worker's execution.
     * This function performs the following steps:
     * 1. Checks for network availability. If not available, requests a retry.
     * 2. Validates input SMS data (sender, body, timestamp). If invalid, returns failure.
     * 3. Checks if a Google user is signed in. If not, returns failure.
     * 4. Calls [attemptSmsSheetSync] to perform the core synchronization logic with Google Sheets.
     * 5. Based on the [WorkerSyncOutcome] from [attemptSmsSheetSync], returns [Result.success],
     *    [Result.retry], or [Result.failure].
     *
     * @return The result of the work, indicating success, failure, or retry.
     */
    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork started.")

        // 1. Network Check
        if (!NetworkUtils.isNetworkAvailable(applicationContext)) {
            Log.w(TAG, "No network connection. Retrying work.")
            return Result.retry() // Request a retry if network is unavailable
        }

        // 2. Input Data Validation
        val sender = inputData.getString(INPUT_DATA_SENDER)
        val body = inputData.getString(INPUT_DATA_BODY)
        val timestamp = inputData.getLong(INPUT_DATA_TIMESTAMP, 0L)

        if (sender == null || body == null || timestamp == 0L) {
            Log.e(TAG, "Invalid SMS data received (sender, body, or timestamp is null/zero). Failing work.")
            return Result.failure() // Permanent failure for invalid input
        }

        // 3. Google Sign-In Check
        val googleSignInAccount = GoogleSignIn.getLastSignedInAccount(applicationContext)
        if (googleSignInAccount == null) {
            Log.e(TAG, "No signed-in Google account found. Cannot sync SMS. Failing work.")
            // This is treated as a permanent failure for this work instance.
            // User needs to sign in for future syncs to succeed.
            return Result.failure()
        }

        Log.d(TAG, "User '${googleSignInAccount.email}' signed in. Proceeding with SMS sync for sender: $sender.")

        // Initialize GoogleSheetsService and prepare SmsData object
        val sheetService = GoogleSheetsService(applicationContext, googleSignInAccount)
        val smsData = SmsData(sender = sender, body = body, timestamp = timestamp)

        // 4. Attempt the actual SMS synchronization with Google Sheets
        val outcome = attemptSmsSheetSync(smsData, googleSignInAccount, sheetService)

        // 5. Determine the Result based on the outcome of the sync attempt
        return when {
            outcome.success -> {
                Log.i(TAG, "SMS from $sender: Sync process completed successfully (either new backup or duplicate).")
                Result.success()
            }
            outcome.shouldRetry -> {
                Log.w(TAG, "SMS from $sender: Sync process failed, will retry. Error: ${outcome.detailedError}")
                Result.retry()
            }
            else -> { // Not successful and not retryable
                Log.e(TAG, "SMS from $sender: Sync process failed permanently. Error: ${outcome.detailedError}")
                Result.failure()
            }
        }.also {
            // Log the final result of doWork for this attempt
            Log.d(TAG, "doWork finished for SMS from $sender with result: $it")
        }
    }

    /**
     * Data class to represent the outcome of the core SMS synchronization attempt with Google Sheets.
     * @property success True if the SMS was successfully processed (either backed up or confirmed as a duplicate).
     *                   False if an error occurred during the sheet operation.
     * @property shouldRetry True if a retryable error (like a network IOException) occurred, suggesting
     *                       the operation might succeed on a subsequent attempt.
     * @property detailedError A string containing details about the error, primarily for logging.
     */
    private data class WorkerSyncOutcome(
        val success: Boolean,
        val shouldRetry: Boolean = false,
        val detailedError: String? = null
    )

    /**
     * Attempts to synchronize a single SMS message with Google Sheets.
     * This function handles the logic for finding/creating the spreadsheet, generating an SMS ID,
     * checking for duplicates, and appending the new SMS if it's not a duplicate.
     * It catches exceptions related to sheet operations and translates them into a [WorkerSyncOutcome].
     *
     * @param smsData The [SmsData] object representing the SMS to be synced.
     * @param googleSignInAccount The [GoogleSignInAccount] of the current user. (Currently unused here but passed for context).
     * @param sheetService An instance of [GoogleSheetsService] to interact with Google Sheets.
     * @return A [WorkerSyncOutcome] indicating the result of the synchronization attempt.
     */
    private suspend fun attemptSmsSheetSync(
        smsData: SmsData,
        googleSignInAccount: GoogleSignInAccount,
        sheetService: GoogleSheetsService
    ): WorkerSyncOutcome {
        try {
            // Step 1: Find or create the spreadsheet.
            // This can throw IOException if network issues occur during API calls.
            val currentSpreadsheetId = sheetService.findOrCreateSpreadsheet()
            if (currentSpreadsheetId == null) {
                // This indicates an unexpected issue if findOrCreateSpreadsheet returns null without an exception.
                return WorkerSyncOutcome(success = false, detailedError = "Failed to find or create spreadsheet (unexpected null response).")
            }
            Log.d(TAG, "attemptSmsSheetSync: Using spreadsheet ID '$currentSpreadsheetId' for SMS from ${smsData.sender}")

            // Step 2: Generate a unique ID for the SMS.
            val smsIdToUpload = sheetService.generateSmsId(smsData)
            Log.d(TAG, "attemptSmsSheetSync: Generated SMS ID '$smsIdToUpload' for current message.")

            // Step 3: Get existing SMS IDs from the sheet to check for duplicates.
            // This can also throw exceptions (e.g., IOException).
            val existingSmsIds = sheetService.getExistingSmsIds()
            Log.d(TAG, "attemptSmsSheetSync: Found ${existingSmsIds.size} existing SMS IDs in sheet.")

            // Step 4: Check if the current SMS is a duplicate.
            if (!existingSmsIds.contains(smsIdToUpload)) {
                // SMS is new, attempt to append it to the sheet.
                Log.d(TAG, "attemptSmsSheetSync: SMS with ID '$smsIdToUpload' is new. Appending to sheet.")
                val appendSuccess = sheetService.appendSmsToSheet(smsData)
                return if (appendSuccess) {
                    // Successfully appended new SMS.
                    WorkerSyncOutcome(success = true)
                } else {
                    // appendSmsToSheet returned false, indicating an issue not thrown as an exception
                    // (e.g., API call succeeded but indicated no rows appended).
                    // This is treated as a non-retryable failure for this specific SMS.
                    WorkerSyncOutcome(success = false, detailedError = "GoogleSheetsService.appendSmsToSheet returned false, indicating append failure.")
                }
            } else {
                // SMS is a duplicate. This is considered a successful processing of the SMS.
                Log.i(TAG, "attemptSmsSheetSync: SMS with ID '$smsIdToUpload' is a duplicate. Not adding to sheet.")
                return WorkerSyncOutcome(success = true) // Duplicate found, considered a success for this sync operation.
            }
        } catch (e: GoogleAuthIOException) {
            // Handle specific authentication errors (e.g., token expired, revoked).
            // These are typically not retryable without user interaction.
            val errorMsg = sheetService.getErrorMessageForException(e)
            Log.e(TAG, "attemptSmsSheetSync: Authentication error during SMS sync: $errorMsg", e)
            return WorkerSyncOutcome(success = false, shouldRetry = false, detailedError = "GoogleAuthIOException: $errorMsg")
        } catch (e: IOException) {
            // Handle network errors or other I/O problems.
            // These are generally considered retryable.
            val errorMsg = sheetService.getErrorMessageForException(e)
            Log.w(TAG, "attemptSmsSheetSync: Network or I/O error during SMS sync, will request retry: $errorMsg", e)
            return WorkerSyncOutcome(success = false, shouldRetry = true, detailedError = "IOException: $errorMsg")
        } catch (e: Exception) {
            // Handle any other unexpected errors.
            // These are treated as non-retryable by default unless specific exceptions are known to be retryable.
            val errorMsg = sheetService.getErrorMessageForException(e)
            Log.e(TAG, "attemptSmsSheetSync: Unexpected error during SMS sync: $errorMsg", e)
            return WorkerSyncOutcome(success = false, shouldRetry = false, detailedError = "Unexpected Exception: $errorMsg")
        }
    }
}
