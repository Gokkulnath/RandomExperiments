package com.example.smsbackuptodrive

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.sheets.v4.Sheets
import com.google.api.services.sheets.v4.SheetsScopes
import com.google.api.services.sheets.v4.model.CellData
import com.google.api.services.sheets.v4.model.ExtendedValue
import com.google.api.services.sheets.v4.model.GridData
import com.google.api.services.sheets.v4.model.RowData
import com.google.api.services.sheets.v4.model.SheetProperties
import com.google.api.services.sheets.v4.model.Spreadsheet
import com.google.api.services.sheets.v4.model.SpreadsheetProperties
import com.google.api.services.sheets.v4.model.ValueRange
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

class GoogleSheetsService(
    private val context: Context,
    private val account: GoogleSignInAccount
) {
    private var driveService: Drive? = null
    private var sheetsService: Sheets? = null
    var spreadsheetId: String? = null // Made public for potential external access if needed

    companion object {
        private const val TAG = "GoogleSheetsService"
        const val SHEET_NAME = "SMS Backups" // Public for potential use in UI/logging
        const val MESSAGES_SHEET_TAB_NAME = "Messages" // Name of the tab within the spreadsheet
        const val SMS_ID_COLUMN_INDEX = 0 // Column A for our generated SMS ID
        val DEFAULT_COLUMNS = listOf("SMS ID", "Timestamp", "Sender", "Message Body")
    }

    init {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE, SheetsScopes.SPREADSHEETS)
        )
        credential.selectedAccount = account.account

        val transport = AndroidHttp.newCompatibleTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        driveService = Drive.Builder(transport, jsonFactory, credential)
            .setApplicationName(context.getString(R.string.app_name))
            .build()

        sheetsService = Sheets.Builder(transport, jsonFactory, credential)
            .setApplicationName(context.getString(R.string.app_name))
            .build()
    }

    // Made public to be callable from outside if needed for ID generation before appending,
    // e.g. for deduplication logic in the caller.
    fun generateSmsId(smsData: SmsData): String {
        val input = "${smsData.sender}-${smsData.timestamp}-${smsData.body}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    suspend fun findOrCreateSpreadsheet(): String? = withContext(Dispatchers.IO) {
        if (spreadsheetId != null) return@withContext spreadsheetId

        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.e(TAG, "findOrCreateSpreadsheet: No network connection.")
            throw IOException(context.getString(R.string.error_no_network))
        }

        try {
            Log.d(TAG, "Searching for spreadsheet: $SHEET_NAME")
            val query = "name = '$SHEET_NAME' and mimeType = 'application/vnd.google-apps.spreadsheet' and 'me' in owners and trashed = false"
            val files = driveService?.files()?.list()?.setQ(query)?.setSpaces("drive")?.execute()

            if (files != null && files.files.isNotEmpty()) {
                spreadsheetId = files.files[0].id
                Log.d(TAG, "Found existing spreadsheet. ID: $spreadsheetId")
                return@withContext spreadsheetId
            }

            Log.d(TAG, "Spreadsheet not found. Creating new one.")
            // Create header row
            val headerCells = DEFAULT_COLUMNS.map { CellData().setUserEnteredValue(ExtendedValue().setStringValue(it)) }
            val headerRow = RowData().setValues(headerCells)

            val sheetTab = com.google.api.services.sheets.v4.model.Sheet()
                .setProperties(SheetProperties().setTitle(MESSAGES_SHEET_TAB_NAME))
                .setData(listOf(GridData().setRowData(listOf(headerRow))))


            val spreadsheetToCreate = Spreadsheet()
                .setProperties(SpreadsheetProperties().setTitle(SHEET_NAME))
                .setSheets(listOf(sheetTab))

            val createdSpreadsheet = sheetsService?.spreadsheets()?.create(spreadsheetToCreate)?.execute()
            spreadsheetId = createdSpreadsheet?.spreadsheetId
            Log.d(TAG, "Created new spreadsheet. ID: $spreadsheetId")
            return@withContext spreadsheetId

        } catch (e: GoogleJsonResponseException) {
            Log.e(TAG, "Google API error in findOrCreateSpreadsheet: ${e.statusCode} - ${e.details?.message}", e)
            // Depending on the status code, you might throw a more specific custom exception or return null
            // For now, logging and returning null for simplicity for findOrCreate, as it's a setup step.
        } catch (e: IOException) {
            Log.e(TAG, "Network or IO error in findOrCreateSpreadsheet: ${e.message}", e)
            throw e // Re-throw to be caught by caller if they need to handle network specifically
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in findOrCreateSpreadsheet: ${e.message}", e)
        }
        return@withContext null
    }

    suspend fun appendSmsToSheet(smsData: SmsData): Boolean = withContext(Dispatchers.IO) {
        if (spreadsheetId == null) {
            // Attempt to initialize spreadsheetId. If this fails (e.g. network error), it will throw.
            try {
                findOrCreateSpreadsheet()
            } catch (e: Exception) {
                Log.e(TAG, "appendSmsToSheet: Failed to find/create spreadsheet during pre-check: ${e.message}")
                return@withContext false // Or rethrow if MainActivity should handle this specific error
            }
            if (spreadsheetId == null) {
                Log.e(TAG, "Spreadsheet ID is null even after attempting to find/create. Cannot append.")
                return@withContext false
            }
        }

        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.e(TAG, "appendSmsToSheet: No network connection.")
            // Consider throwing an IOException if the caller should handle it specifically
            return@withContext false
        }

        try {
            val generatedId = generateSmsId(smsData) // generateSmsId is public for this class
            val values = listOf(
                listOf(
                    generatedId,
                    smsData.timestamp.toString(),
                    smsData.sender,
                    smsData.body
                )
            )
            val valueRange = ValueRange().setValues(values)
            val range = "$MESSAGES_SHEET_TAB_NAME!A1" // It will append after the last row with data

            sheetsService?.spreadsheets()?.values()
                ?.append(spreadsheetId, range, valueRange)
                ?.setValueInputOption("USER_ENTERED")
                ?.execute()
            // The ID used for appending is the one generated by this service's generateSmsId,
            // which is called internally if appendSmsToSheet is designed that way, or externally if caller generates.
            // Current appendSmsToSheet implicitly calls its own generateSmsId.
            Log.d(TAG, "SMS appended to sheet successfully. Sheet's internal ID for SMS from ${smsData.sender} on ${smsData.timestamp}")
            return@withContext true
        } catch (e: GoogleJsonResponseException) {
            Log.e(TAG, "Google API error in appendSmsToSheet: ${e.statusCode} - ${e.details?.message}", e)
            // Handle specific status codes if needed, e.g., re-auth for 401
        } catch (e: IOException) {
            Log.e(TAG, "Network or IO error in appendSmsToSheet: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in appendSmsToSheet: ${e.message}", e)
        }
        return@withContext false
    }

    suspend fun getExistingSmsIds(): List<String> = withContext(Dispatchers.IO) {
        if (spreadsheetId == null) {
            try {
                findOrCreateSpreadsheet()
            } catch (e: Exception) {
                Log.e(TAG, "getExistingSmsIds: Failed to find/create spreadsheet during pre-check: ${e.message}")
                return@withContext emptyList()
            }
            if (spreadsheetId == null) {
                Log.e(TAG, "Spreadsheet ID is null even after attempting to find/create. Cannot get existing IDs.")
                return@withContext emptyList()
            }
        }

        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.e(TAG, "getExistingSmsIds: No network connection.")
            return@withContext emptyList() // Or throw IOException
        }

        val ids = mutableListOf<String>()
        try {
            // Range A2:A means all cells in column A starting from row 2
            val range = "$MESSAGES_SHEET_TAB_NAME!A2:A"
            val response = sheetsService?.spreadsheets()?.values()?.get(spreadsheetId, range)?.execute()
            val values = response?.getValues()

            if (values != null) {
                for (row in values) {
                    if (row.isNotEmpty() && row[0] != null) {
                        ids.add(row[0].toString())
                    }
                }
            }
            Log.d(TAG, "Fetched ${ids.size} existing SMS IDs from the sheet.")
        } catch (e: GoogleJsonResponseException) {
            Log.e(TAG, "Google API error in getExistingSmsIds: ${e.statusCode} - ${e.details?.message}", e)
            // Specific error handling can be added here. For now, returns empty list on error.
        } catch (e: IOException) {
            Log.e(TAG, "Network or IO error in getExistingSmsIds: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in getExistingSmsIds: ${e.message}", e)
        }
        return@withContext ids
    }

    // Helper to map GoogleJsonResponseException to a user-friendly string resource ID or message
    fun getErrorMessageForException(e: Exception): String {
        return when (e) {
            is GoogleJsonResponseException -> {
                when (e.statusCode) {
                    401 -> context.getString(R.string.error_authentication_failed)
                    403 -> context.getString(R.string.error_sheet_permission) // Could be file not found if ID is wrong too
                    429 -> context.getString(R.string.error_api_quota_exceeded)
                    else -> e.details?.message ?: context.getString(R.string.error_sync_failed_general)
                }
            }
            is IOException -> context.getString(R.string.error_no_network) // Or a more general IO error string
            else -> context.getString(R.string.error_sync_failed_general)
        }
    }
}

// Removed the internal extension function as generateSmsId is now a public member
