package com.example.disasterapp.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.util.Log

class MeshNetworkService : Service() {

    companion object {
        const val CHANNEL_ID = "MeshNetworkChannel"
        const val ALERT_CHANNEL_ID = "MeshSosAlertChannel"
        const val BROADCAST_MESSAGE = "com.example.disasterapp.MESSAGE_RECEIVED"
        const val BROADCAST_STATUS = "com.example.disasterapp.STATUS_UPDATE"
    }

    private lateinit var nearbyMeshRouter: NearbyMeshRouter
    private val processedMessages = java.util.HashSet<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, buildNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, buildNotification())
        }
        
        nearbyMeshRouter = NearbyMeshRouter(
            this,
            Build.MODEL,
            onMessageReceived = { msg ->
                handleIncomingPayloadAndRelay(msg)
            },
            onStatusUpdate = { status ->
                val intent = Intent(BROADCAST_STATUS).apply { 
                    setPackage(packageName)
                    putExtra("status", status) 
                }
                sendBroadcast(intent)
            }
        )
    }

    private fun handleIncomingPayloadAndRelay(incomingMessage: String) {
        val idMatch = Regex("\\[ID:(.*?)\\]").find(incomingMessage)
        val ttlMatch = Regex("\\[TTL:(\\d+)\\]").find(incomingMessage)
        
        if (idMatch != null && ttlMatch != null) {
            val msgId = idMatch.groupValues[1]
            var ttl = ttlMatch.groupValues[1].toInt()
            
            if (!processedMessages.contains(msgId)) {
                processedMessages.add(msgId)
                
                val cleanMsg = incomingMessage.replace(Regex("\\[ID:.*?\\]\\[TTL:\\d+\\] "), "")
                triggerSosNotification(cleanMsg)
                
                // 1. Broadcast to UI so it can display the original message
                val intent = Intent(BROADCAST_MESSAGE).apply { 
                    setPackage(packageName)
                    putExtra("payload", incomingMessage) 
                }
                sendBroadcast(intent)
                
                // 2. Hardware Multi-Hop Relay Mechanism (Background Auto-Relay)
                ttl -= 1
                if (ttl > 0) {
                    val outgoingRelay = "[ID:$msgId][TTL:$ttl] $cleanMsg"
                    nearbyMeshRouter.broadcastAndStore(outgoingRelay)
                    Log.d("MeshService", "Background Relaying Signal! TTL is now $ttl")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val payloadToBroadcast = intent?.getStringExtra("BROADCAST_PAYLOAD")
        if (payloadToBroadcast != null) {
            val idMatch = Regex("\\[ID:(.*?)\\]").find(payloadToBroadcast)
            if (idMatch != null) {
                processedMessages.add(idMatch.groupValues[1])
            }
            nearbyMeshRouter.broadcastAndStore(payloadToBroadcast)
        } else {
            nearbyMeshRouter.startMeshNetworking()
            Log.d("MeshService", "Background Service actively scanning/advertising...")
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        nearbyMeshRouter.stopMeshNetworking()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We rely strictly on Broadcast Intents, not Bound Binders for simplicity
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Network Service",
                NotificationManager.IMPORTANCE_LOW
            )
            
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "SOS Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts when nearby devices request rescue."
                enableVibration(true)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
            manager?.createNotificationChannel(alertChannel)
        }
    }
    
    private fun triggerSosNotification(message: String) {
        val intent = Intent(this, Class.forName("com.example.disasterapp.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent: android.app.PendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚨 EMERGENCY SOS RECEIVED 🚨")
            .setContentText("A nearby mesh node just relayed a critical distress signal!")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Details: $message"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
            
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Disaster Mesh Active")
            .setContentText("Maintaining offline radio clusters independently in the background.")
            .setSmallIcon(android.R.drawable.ic_dialog_info) 
            .setOngoing(true)
            .build()
    }
}
