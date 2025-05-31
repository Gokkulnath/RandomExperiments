package com.example.smsbackuptodrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (smsMessage in messages) {
                val sender = smsMessage.originatingAddress
                val messageBody = smsMessage.displayMessageBody
                val timestamp = smsMessage.timestampMillis

                Log.d(TAG, "New SMS Received:")
                Log.d(TAG, "Sender: $sender")
                Log.d(TAG, "Body: $messageBody")
                Log.d(TAG, "Timestamp: $timestamp")

                // Prepare data for the Worker
                val workData = Data.Builder()
                    .putString(SmsSyncWorker.INPUT_DATA_SENDER, sender)
                    .putString(SmsSyncWorker.INPUT_DATA_BODY, messageBody)
                    .putLong(SmsSyncWorker.INPUT_DATA_TIMESTAMP, timestamp)
                    .build()

                // Create a WorkRequest for SmsSyncWorker
                val smsSyncWorkRequest = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                    .setInputData(workData)
                    .build()

                // Enqueue the work
                WorkManager.getInstance(context).enqueue(smsSyncWorkRequest)
                Log.d(TAG, "Enqueued SMS to SmsSyncWorker for processing.")
            }
        }
    }
}
