package com.example.disasterapp.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log

class WiFiDirectManager(
    private val manager: WifiP2pManager,
    private val channel: WifiP2pManager.Channel,
    private val context: Context
) {

    private val TAG = "WiFiDirectManager"

    @SuppressLint("MissingPermission") // Caller (MainActivity) must check permissions
    fun discoverPeers(onSuccess: () -> Unit, onFailure: (Int) -> Unit) {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Discovery initiated successfully.")
                onSuccess()
            }

            override fun onFailure(reasonCode: Int) {
                Log.e(TAG, "Discovery failed with code $reasonCode")
                onFailure(reasonCode)
            }
        })
    }

    // Connect, sendData, etc. go here (implements Phase 4 relay)
}
