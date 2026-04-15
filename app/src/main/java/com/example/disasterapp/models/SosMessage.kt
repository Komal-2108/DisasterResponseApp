package com.example.disasterapp.models

import java.util.UUID

data class SosMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val senderId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double,
    val distressInfo: String,
    // Time-To-Live to prevent infinite loops when relaying via mesh (Phase 4)
    var ttl: Int = 10
) {
    // Serialization logic would go here typically (e.g. JSON stringify mapping)
}
