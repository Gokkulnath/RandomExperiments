package com.example.smsbackuptodrive

/**
 * Data class representing a single SMS message.
 * This class holds the essential information extracted from an SMS message
 * that is required for backup and processing.
 *
 * @property sender The phone number or address of the SMS message sender.
 * @property body The content (text) of the SMS message.
 * @property timestamp The timestamp (in milliseconds since epoch) when the SMS message was sent or received.
 */
data class SmsData(
    val sender: String,
    val body: String,
    val timestamp: Long
)
