package com.example.disasterapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.graphics.Bitmap
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.disasterapp.network.P2PBroadcastReceiver
import com.example.disasterapp.network.WiFiDirectManager

class MainActivity : AppCompatActivity() {

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var manager: WifiP2pManager
    private lateinit var receiver: P2PBroadcastReceiver
    private lateinit var wifiDirectManager: WiFiDirectManager
    
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvMessageLog: TextView
    private lateinit var layoutUserDashboard: View
    private lateinit var layoutRescuerDashboard: View
    private lateinit var tvRescuerLogs: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvMessageLog = findViewById(R.id.tvMessageLog)
        layoutUserDashboard = findViewById(R.id.layoutUserDashboard)
        layoutRescuerDashboard = findViewById(R.id.layoutRescuerDashboard)
        tvRescuerLogs = findViewById(R.id.tvRescuerLogs)

        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        receiver = P2PBroadcastReceiver(manager, channel, this)
        wifiDirectManager = WiFiDirectManager(manager, channel, this)

        findViewById<Button>(R.id.btnSos).setOnClickListener {
            checkPermissionsAndTriggerSos("MANUAL SOS BEACON SENT")
        }
        
        findViewById<Button>(R.id.btnAiScan).setOnClickListener {
            triggerAiHazardScan()
        }

        findViewById<Button>(R.id.btnRescuerMode).setOnClickListener {
            toggleRescuerMode(true)
        }
        
        findViewById<Button>(R.id.btnExitRescuer).setOnClickListener {
            toggleRescuerMode(false)
        }
        
        requestRequiredPermissions()
    }

    private fun toggleRescuerMode(enabled: Boolean) {
        if(enabled) {
            layoutUserDashboard.visibility = View.GONE
            layoutRescuerDashboard.visibility = View.VISIBLE
        } else {
            layoutRescuerDashboard.visibility = View.GONE
            layoutUserDashboard.visibility = View.VISIBLE
        }
    }

    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
        val btnAi = findViewById<Button>(R.id.btnAiScan)
        if (bitmap != null) {
            Toast.makeText(this, "AI: Analyzing spatial image...", Toast.LENGTH_SHORT).show()
            btnAi.text = "Analyzing..."
            
            Handler(Looper.getMainLooper()).postDelayed({
                btnAi.text = "Run AI Hazard Scan"
                btnAi.isEnabled = true
                
                // Simulate varying AI results based on the captured image
                val hazards = listOf(
                    "Safe Zone. No structural hazards detected.", 
                    "Safe Zone. No structural hazards detected.", 
                    "Warning: Debris Obstruction Detected"
                )
                val result = hazards.random()
                Toast.makeText(this, "AI Result: $result", Toast.LENGTH_LONG).show()
                
                if (result.contains("Warning")) {
                     checkPermissionsAndTriggerSos("AI-ALERT: $result")
                }
            }, 3000)
        } else {
            btnAi.text = "Run AI Hazard Scan"
            btnAi.isEnabled = true
        }
    }

    private fun triggerAiHazardScan() {
        val btnAi = findViewById<Button>(R.id.btnAiScan)
        btnAi.text = "Opening Camera..."
        btnAi.isEnabled = false
        takePictureLauncher.launch(null)
    }

    private fun requestRequiredPermissions() {
        val requiredPerms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPerms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        
        val missingPerms = requiredPerms.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (missingPerms.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPerms.toTypedArray())
        } else {
            startDiscovery()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startDiscovery()
        } else {
            Toast.makeText(this, "Permissions required for offline networking", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            tvConnectionStatus.text = "Status: Discovery Failed (GPS is OFF)"
            Toast.makeText(this, "CRITICAL: Please turn ON your phone's Location (GPS) to use Mesh Networking!", Toast.LENGTH_LONG).show()
            return
        }

        wifiDirectManager.discoverPeers(
            onSuccess = { tvConnectionStatus.text = "Status: Scanning Network" },
            onFailure = { reason -> 
                val reasonText = if(reason == 2) "BUSY / GPS OFF (Code: 2)" else "Code: $reason"
                tvConnectionStatus.text = "Status: Discovery Failed ($reasonText)" 
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun checkPermissionsAndTriggerSos(messageType: String) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) 
            ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

        val lat = location?.latitude ?: 0.0
        val lon = location?.longitude ?: 0.0
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        
        val logEntry = "\n[$timestamp] \uD83D\uDEA8 $messageType\n   Location: $lat, $lon\n   Peers Reached: 0 (No active mesh)"
        tvMessageLog.text = tvMessageLog.text.toString() + "\n" + logEntry

        val scrollView = tvMessageLog.parent as? android.widget.ScrollView
        scrollView?.post {
            scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
        
        // Also add to rescuer dashboard for Phase 8 demonstration
        val rescuerLog = "\n[HIGH PRIORITY] $timestamp - $messageType\nLocal Mesh Node: Active | Coordination: $lat, $lon\n"
        tvRescuerLogs.text = tvRescuerLogs.text.toString() + rescuerLog
    }

    fun onPeersAvailable(peers: WifiP2pDeviceList) {
        val peerCount = peers.deviceList.size
        if (peerCount > 0) {
            tvConnectionStatus.text = "Status: Connected to Mesh ($peerCount nodes)"
        } else {
            tvConnectionStatus.text = "Status: No peers found yet"
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(receiver, intentFilter)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(receiver)
    }
}
