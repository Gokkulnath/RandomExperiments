package com.example.smsbackuptodrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * A [BroadcastReceiver] that listens for incoming SMS messages.
 * When an SMS is received, it extracts the sender, body, and timestamp,
 * and then enqueues a [SmsSyncWorker] to process and back up the SMS message.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver" // Logcat tag
    }

    /**
     * This method is called when the BroadcastReceiver is receiving an Intent broadcast.
     * It checks if the received intent is for an SMS message. If so, it extracts
     * SMS details and schedules a background task using WorkManager to sync the SMS.
     *
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    override fun onReceive(context: Context, intent: Intent) {
        // Check if the received intent action matches the SMS received action.
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            // Retrieve an array of SmsMessage objects from the intent.
            // An intent can contain multiple messages if it's a multi-part SMS.
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (smsMessage in messages) {
                // Extract relevant information from each SMS message.
                val sender = smsMessage.originatingAddress // Sender's phone number
                val messageBody = smsMessage.displayMessageBody // Content of the SMS
                val timestamp = smsMessage.timestampMillis // Time the message was received

                Log.d(TAG, "New SMS Received:")
                Log.d(TAG, "  Sender: $sender")
                Log.d(TAG, "  Body: $messageBody")
                Log.d(TAG, "  Timestamp: $timestamp")

                // Prepare input data for the SmsSyncWorker.
                // This data will be passed to the worker to identify the SMS to be synced.
                val workData = Data.Builder()
                    .putString(SmsSyncWorker.INPUT_DATA_SENDER, sender)
                    .putString(SmsSyncWorker.INPUT_DATA_BODY, messageBody)
                    .putLong(SmsSyncWorker.INPUT_DATA_TIMESTAMP, timestamp)
                    .build()

                // Create a one-time work request for the SmsSyncWorker.
                // This tells WorkManager to execute the SmsSyncWorker once with the provided input data.
                val smsSyncWorkRequest = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                    .setInputData(workData) // Pass the SMS data to the worker
                    .build()

                // Enqueue the work request with WorkManager.
                // WorkManager will handle the execution of the worker, including retries and constraints.
                WorkManager.getInstance(context).enqueue(smsSyncWorkRequest)
                Log.d(TAG, "Enqueued SMS from $sender to SmsSyncWorker for processing.")
            }
        }
    }
}
