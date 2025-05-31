package com.example.smsbackuptodrive

data class SmsData(
    val id: String,
    val sender: String,
    val body: String,
    val timestamp: Long
)
