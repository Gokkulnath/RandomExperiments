package com.example.smsbackuptodrive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log

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

                // Trigger the SmsSyncService to handle processing and Google Drive upload.
                val serviceIntent = Intent(context, SmsSyncService::class.java).apply {
                    putExtra(SmsSyncService.EXTRA_SENDER, sender)
                    putExtra(SmsSyncService.EXTRA_BODY, messageBody)
                    putExtra(SmsSyncService.EXTRA_TIMESTAMP, timestamp)
                }
                SmsSyncService.enqueueWork(context, serviceIntent)
                Log.d(TAG, "Enqueued SMS to SmsSyncService for processing.")
            }
        }
    }
}
