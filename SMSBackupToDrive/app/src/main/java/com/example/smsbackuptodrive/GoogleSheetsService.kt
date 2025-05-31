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

/**
 * Service class for interacting with Google Drive and Google Sheets APIs.
 * This class handles operations such as finding or creating a spreadsheet,
 * appending SMS data to it, and retrieving existing SMS IDs for deduplication.
 * All network operations are performed asynchronously using Kotlin coroutines.
 *
 * @property context The application context, used for accessing resources and initializing Google services.
 * @property account The [GoogleSignInAccount] of the authenticated user, used for authorizing API calls.
 */
class GoogleSheetsService(
    private val context: Context,
    private val account: GoogleSignInAccount
) {
    private var driveService: Drive? = null // Service for Google Drive API interactions
    private var sheetsService: Sheets? = null // Service for Google Sheets API interactions
    private var spreadsheetId: String? = null // Cached ID of the target spreadsheet

    companion object {
        private const val TAG = "GoogleSheetsService" // Logcat tag
        /** The default name of the spreadsheet where SMS messages will be backed up. */
        const val SHEET_NAME = "SMS Backups"
        /** The name of the specific tab (sheet) within the spreadsheet where messages are stored. */
        const val MESSAGES_SHEET_TAB_NAME = "Messages"
        /** Index of the column (0-based) where the generated SMS ID is stored. Currently Column A. */
        const val SMS_ID_COLUMN_INDEX = 0
        /** Default header row values for a newly created spreadsheet. */
        val DEFAULT_COLUMNS = listOf("SMS ID", "Timestamp", "Sender", "Message Body")
    }

    /**
     * Initializes the [driveService] and [sheetsService] using the provided user credentials
     * and application context.
     */
    init {
        // Obtain Google Account credentials with necessary OAuth2 scopes for Drive and Sheets.
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE, SheetsScopes.SPREADSHEETS) // Scopes required by the app
        )
        credential.selectedAccount = account.account // Set the authenticated account

        // Initialize HTTP transport and JSON factory
        val transport = AndroidHttp.newCompatibleTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()

        // Build Google Drive service client
        driveService = Drive.Builder(transport, jsonFactory, credential)
            .setApplicationName(context.getString(R.string.app_name)) // Set application name for API requests
            .build()

        // Build Google Sheets service client
        sheetsService = Sheets.Builder(transport, jsonFactory, credential)
            .setApplicationName(context.getString(R.string.app_name)) // Set application name for API requests
            .build()
    }

    /**
     * Generates a unique ID (SHA-256 hash) for an SMS message based on its sender, timestamp, and body.
     * This ID is used for deduplication purposes to prevent backing up the same SMS multiple times.
     *
     * @param smsData The [SmsData] object for which to generate an ID.
     * @return A string representing the SHA-256 hash of the SMS content.
     */
    fun generateSmsId(smsData: SmsData): String {
        // Concatenate sender, timestamp, and body to create a unique input string for hashing.
        val input = "${smsData.sender}-${smsData.timestamp}-${smsData.body}"
        val digest = MessageDigest.getInstance("SHA-256") // Get SHA-256 message digest instance
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8)) // Compute hash
        // Convert byte array to a hexadecimal string representation.
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Finds an existing spreadsheet named [SHEET_NAME] in the user's Google Drive
     * or creates a new one if it doesn't exist.
     * The ID of the found or created spreadsheet is cached in [spreadsheetId] for subsequent calls.
     * This function operates on the IO dispatcher due to network operations.
     *
     * @return The ID of the spreadsheet if found or created successfully, or null if an error occurs.
     * @throws IOException if a network error occurs (e.g., no internet connection).
     */
    suspend fun findOrCreateSpreadsheet(): String? = withContext(Dispatchers.IO) {
        // Return cached spreadsheet ID if already available
        if (spreadsheetId != null) return@withContext spreadsheetId

        // Check for network connectivity before making API calls
        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.e(TAG, "findOrCreateSpreadsheet: No network connection.")
            throw IOException(context.getString(R.string.error_no_network)) // Throw specific error
        }

        try {
            // Search for the spreadsheet by name, mimeType, and owner
            Log.d(TAG, "Searching for spreadsheet: $SHEET_NAME")
            val query = "name = '$SHEET_NAME' and mimeType = 'application/vnd.google-apps.spreadsheet' and 'me' in owners and trashed = false"
            val files = driveService?.files()?.list()?.setQ(query)?.setSpaces("drive")?.execute()

            // If spreadsheet found, cache its ID and return it
            if (files != null && files.files.isNotEmpty()) {
                spreadsheetId = files.files[0].id
                Log.d(TAG, "Found existing spreadsheet. ID: $spreadsheetId")
                return@withContext spreadsheetId
            }

            // Spreadsheet not found, create a new one
            Log.d(TAG, "Spreadsheet '$SHEET_NAME' not found. Creating new one.")
            // Define header row for the new sheet
            val headerCells = DEFAULT_COLUMNS.map { CellData().setUserEnteredValue(ExtendedValue().setStringValue(it)) }
            val headerRow = RowData().setValues(headerCells)

            // Define the "Messages" sheet (tab) with its properties and initial header data
            val sheetTab = com.google.api.services.sheets.v4.model.Sheet()
                .setProperties(SheetProperties().setTitle(MESSAGES_SHEET_TAB_NAME))
                .setData(listOf(GridData().setRowData(listOf(headerRow)))) // Set initial data (header)

            // Define the spreadsheet properties (title) and include the "Messages" sheet
            val spreadsheetToCreate = Spreadsheet()
                .setProperties(SpreadsheetProperties().setTitle(SHEET_NAME))
                .setSheets(listOf(sheetTab)) // Add the defined sheet tab

            // Create the spreadsheet using the Sheets API
            val createdSpreadsheet = sheetsService?.spreadsheets()?.create(spreadsheetToCreate)?.execute()
            spreadsheetId = createdSpreadsheet?.spreadsheetId // Cache the new spreadsheet ID
            Log.d(TAG, "Created new spreadsheet. ID: $spreadsheetId")
            return@withContext spreadsheetId

        } catch (e: GoogleJsonResponseException) {
            // Handle errors from Google API calls (e.g., permission issues, quota exceeded)
            Log.e(TAG, "Google API error in findOrCreateSpreadsheet: ${e.statusCode} - ${e.details?.message}", e)
            // Specific error handling based on status code can be added here.
            // For findOrCreate, we might re-throw or return null to indicate failure.
            throw IOException("Google API error: ${e.details?.message ?: e.statusCode}", e) // Convert to IOException for consistent error type
        } catch (e: IOException) {
            // Handle network or other I/O errors
            Log.e(TAG, "Network or IO error in findOrCreateSpreadsheet: ${e.message}", e)
            throw e // Re-throw to be handled by the caller
        } catch (e: Exception) {
            // Handle any other unexpected errors
            Log.e(TAG, "Unexpected error in findOrCreateSpreadsheet: ${e.message}", e)
            throw IOException("Unexpected error: ${e.message}", e) // Convert to IOException
        }
    }

    /**
     * Appends a single SMS message to the specified Google Sheet.
     * Ensures the spreadsheet ID is available (calls [findOrCreateSpreadsheet] if needed).
     * This function operates on the IO dispatcher.
     *
     * @param smsData The [SmsData] object containing the SMS details to append.
     * @return True if the SMS was appended successfully, false otherwise.
     * @throws IOException if [findOrCreateSpreadsheet] fails due to network or critical API errors.
     */
    suspend fun appendSmsToSheet(smsData: SmsData): Boolean = withContext(Dispatchers.IO) {
        // Ensure spreadsheetId is initialized. This might throw IOException if findOrCreateSpreadsheet fails.
        if (spreadsheetId == null) {
            findOrCreateSpreadsheet() // This will throw if it fails critically
            if (spreadsheetId == null) {
                // If findOrCreateSpreadsheet completed but didn't set an ID (should ideally not happen if no exception)
                Log.e(TAG, "Spreadsheet ID is null even after attempting to find/create. Cannot append.")
                return@withContext false // Critical failure to obtain spreadsheet ID
            }
        }

        // Double-check network availability before attempting to append data.
        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.e(TAG, "appendSmsToSheet: No network connection before appending.")
            // Note: findOrCreateSpreadsheet already checks network. This is an additional safeguard
            // or if appendSmsToSheet is called much later.
            // Depending on desired behavior, could throw IOException here. For now, returns false.
            return@withContext false
        }

        try {
            // Generate the unique ID for this SMS (for the first column)
            val generatedId = generateSmsId(smsData)
            // Prepare the row data to be appended
            val values = listOf(
                listOf(
                    generatedId, // Column A: SMS ID
                    smsData.timestamp.toString(), // Column B: Timestamp
                    smsData.sender, // Column C: Sender
                    smsData.body    // Column D: Message Body
                )
            )
            val valueRange = ValueRange().setValues(values)
            // Specify the sheet and range. "A1" implies appending after the last row with data in any column.
            val range = "$MESSAGES_SHEET_TAB_NAME!A1"

            // Execute the append operation via Google Sheets API
            sheetsService?.spreadsheets()?.values()
                ?.append(spreadsheetId, range, valueRange)
                ?.setValueInputOption("USER_ENTERED") // Interpret data as if user typed it
                ?.execute()
            Log.d(TAG, "SMS from ${smsData.sender} (ID: $generatedId) appended to sheet successfully.")
            return@withContext true
        } catch (e: GoogleJsonResponseException) {
            Log.e(TAG, "Google API error in appendSmsToSheet: ${e.statusCode} - ${e.details?.message}", e)
        } catch (e: IOException) {
            // Network or I/O errors during the append operation itself
            Log.e(TAG, "Network or IO error in appendSmsToSheet: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in appendSmsToSheet: ${e.message}", e)
        }
        return@withContext false // Return false if any error occurs during append
    }

    /**
     * Retrieves a list of all existing SMS IDs (from the first column) from the Google Sheet.
     * This is used for deduplication to avoid backing up the same SMS multiple times.
     * Ensures the spreadsheet ID is available (calls [findOrCreateSpreadsheet] if needed).
     * This function operates on the IO dispatcher.
     *
     * @return A list of strings, where each string is an SMS ID. Returns an empty list if an error occurs
     *         or if the sheet is empty.
     * @throws IOException if [findOrCreateSpreadsheet] fails due to network or critical API errors.
     */
    suspend fun getExistingSmsIds(): List<String> = withContext(Dispatchers.IO) {
        // Ensure spreadsheetId is initialized. This might throw IOException.
        if (spreadsheetId == null) {
            findOrCreateSpreadsheet()
            if (spreadsheetId == null) {
                Log.e(TAG, "Spreadsheet ID is null even after attempting to find/create. Cannot get existing IDs.")
                return@withContext emptyList()
            }
        }

        if (!NetworkUtils.isNetworkAvailable(context)) {
            Log.w(TAG, "getExistingSmsIds: No network connection. Returning empty list.")
            // Could throw IOException, but for read operations, returning empty might be acceptable
            // to prevent blocking sync if temporarily offline and worker needs to complete.
            return@withContext emptyList()
        }

        val ids = mutableListOf<String>()
        try {
            // Define the range to read: Column A, starting from the second row (A2:A) to skip header.
            val range = "$MESSAGES_SHEET_TAB_NAME!A2:A"
            val response = sheetsService?.spreadsheets()?.values()?.get(spreadsheetId, range)?.execute()
            val values = response?.getValues() // List of rows, where each row is a list of cell values

            if (values != null) {
                for (row in values) {
                    // Add the ID from the first cell of the row, if it exists and is not null.
                    if (row.isNotEmpty() && row[0] != null) {
                        ids.add(row[0].toString())
                    }
                }
            }
            Log.d(TAG, "Fetched ${ids.size} existing SMS IDs from the sheet.")
        } catch (e: GoogleJsonResponseException) {
            Log.e(TAG, "Google API error in getExistingSmsIds: ${e.statusCode} - ${e.details?.message}", e)
            // On error, return empty list; caller should handle this gracefully.
        } catch (e: IOException) {
            Log.w(TAG, "Network or IO error in getExistingSmsIds: ${e.message}", e)
            // On IO error, also return empty list for now.
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in getExistingSmsIds: ${e.message}", e)
        }
        return@withContext ids
    }

    /**
     * Converts an [Exception] into a user-friendly error message string.
     * This is useful for displaying errors to the user in the UI.
     *
     * @param e The exception to be converted.
     * @return A string representing a user-friendly error message.
     */
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
