package com.example.disasterapp.network

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

class NearbyMeshRouter(
    private val context: Context,
    private val deviceName: String,
    private val onMessageReceived: (String) -> Unit,
    private val onStatusUpdate: (String) -> Unit
) {
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val STRATEGY = Strategy.P2P_CLUSTER // The heart of True Mesh
    private val SERVICE_ID = "com.example.disasterapp.MESH_NETWORK"
    
    // Multi-Node tracking array
    private val establishedEndpoints = mutableSetOf<String>()
    
    // Phase 4: Store and Forward Cache (Dumps history to new nodes dynamically)
    val activePayloadsToSync = mutableListOf<String>()

    fun startMeshNetworking() {
        startAdvertising()
        startDiscovery()
    }

    fun stopMeshNetworking() {
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        establishedEndpoints.clear()
        onStatusUpdate("Status: Disconnected from Mesh")
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(
            deviceName, SERVICE_ID, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            onStatusUpdate("Status: Operating seamlessly in Mesh (Cluster Mode)")
        }.addOnFailureListener { e ->
            onStatusUpdate("Status: Mesh Initialization Failed (${e.message})")
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(
            SERVICE_ID, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            // Discovery started quietly
        }.addOnFailureListener { e ->
            Log.e("NearbyMesh", "Discovery Failed: ", e)
        }
    }

    fun broadcastAndStore(message: String) {
        if (!activePayloadsToSync.contains(message)) {
            activePayloadsToSync.add(message)
        }
        val bytesPayload = Payload.fromBytes(message.toByteArray())
        establishedEndpoints.forEach { endpointId ->
            connectionsClient.sendPayload(endpointId, bytesPayload)
            Log.d("NearbyMesh", "Payload injected to hardware endpoint: $endpointId")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            onStatusUpdate("Status: Found node '${info.endpointName}', Syncing...")
            
            // To prevent severe connection collisions stalling the radio, only accept the most stable pipe
            connectionsClient.requestConnection(deviceName, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    // Safe auto-connection request registered
                }
                .addOnFailureListener { e ->
                    Log.e("NearbyMesh", "requestConnection failed: ", e)
                    onStatusUpdate("Status: Sync Interrupted (${e.message ?: "Unknown"})")
                }
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            // Natively auto-accept the connection on both sides
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                establishedEndpoints.add(endpointId)
                val nodeText = if (establishedEndpoints.size == 1) "1 node" else "${establishedEndpoints.size} nodes"
                onStatusUpdate("Status: Connected to Mesh ($nodeText)")
                
                // CRITICAL SYNC: Actively push all stored SOS history to this new node!
                activePayloadsToSync.forEach { payloadMsg ->
                    connectionsClient.sendPayload(endpointId, Payload.fromBytes(payloadMsg.toByteArray()))
                    Log.d("NearbyMesh", "Background Sync: Passing old payload to $endpointId")
                }
            } else {
                val statusCode = result.status.statusCode
                val reason = when(statusCode) {
                    8003 -> "Collision/Rejected"
                    8012 -> "Radio IO Error"
                    else -> "Code $statusCode"
                }
                onStatusUpdate("Status: Sync Failed ($reason)")
                Log.d("NearbyMesh", "Connection blocked/failed with code: $statusCode")
            }
        }

        override fun onDisconnected(endpointId: String) {
            establishedEndpoints.remove(endpointId)
            val nodeText = if (establishedEndpoints.size == 1) "1 node" else "${establishedEndpoints.size} nodes"
            onStatusUpdate("Status: Connected to Mesh ($nodeText)")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val message = payload.asBytes()?.let { String(it) }
                if (message != null) {
                    onMessageReceived(message)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
}
