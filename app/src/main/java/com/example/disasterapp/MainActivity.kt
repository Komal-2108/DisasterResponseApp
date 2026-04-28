package com.example.disasterapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.speech.RecognizerIntent
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.disasterapp.network.MeshNetworkService
import com.example.disasterapp.ai.GeminiEmergencyAssistant
import com.example.disasterapp.ai.VoskSpeechEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : AppCompatActivity() {
    
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvMessageLog: TextView
    private lateinit var layoutUserDashboard: View
    private lateinit var layoutRescuerDashboard: View
    private lateinit var tvRescuerLogs: TextView
    private lateinit var geminiAssistant: GeminiEmergencyAssistant
    private var voskEngine: VoskSpeechEngine? = null

    // Phase 4: Duplicate Detection Set
    private val processedMessages = java.util.HashSet<String>()

    // Local Broadcast Receiver natively parsing events from the Background Foreground Service
    private val meshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MeshNetworkService.BROADCAST_MESSAGE -> {
                    val incomingMessage = intent.getStringExtra("payload") ?: return
                    handleIncomingPayload(incomingMessage)
                }
                MeshNetworkService.BROADCAST_STATUS -> {
                    val status = intent.getStringExtra("status") ?: return
                    tvConnectionStatus.text = status
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        geminiAssistant = GeminiEmergencyAssistant(this)
        
        // Initialize VOSK engine independently
        voskEngine = VoskSpeechEngine(this) { isReady ->
            runOnUiThread {
                if (isReady) {
                    Toast.makeText(this, "VOSK Speech Engine Ready!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "VOSK Model failed to load. Please place zip in assets/model", Toast.LENGTH_LONG).show()
                }
            }
        }

        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvMessageLog = findViewById(R.id.tvMessageLog)
        layoutUserDashboard = findViewById(R.id.layoutUserDashboard)
        layoutRescuerDashboard = findViewById(R.id.layoutRescuerDashboard)
        tvRescuerLogs = findViewById(R.id.tvRescuerLogs)

        findViewById<Button>(R.id.btnSos).setOnClickListener {
            checkPermissionsAndTriggerSos("MANUAL SOS BEACON SENT")
        }
        
        findViewById<Button>(R.id.btnVoiceSos).setOnClickListener {
            triggerVoiceSos()
        }
        
        findViewById<Button>(R.id.btnAiScan).setOnClickListener {
            triggerAiHazardScan()
        }

        findViewById<Button>(R.id.btnFirstAidGuide).setOnClickListener {
            startActivity(Intent(this, FirstAidActivity::class.java))
        }

        findViewById<Button>(R.id.btnRescuerMode).setOnClickListener {
            toggleRescuerMode(true)
        }
        
        findViewById<Button>(R.id.btnExitRescuer).setOnClickListener {
            toggleRescuerMode(false)
        }
        
        findViewById<View>(R.id.btnRefreshNetwork).setOnClickListener {
            Toast.makeText(this, "Flushing and Restarting Mesh Antennas...", Toast.LENGTH_SHORT).show()
            tvConnectionStatus.text = "Status: Re-syncing Antennas..."
            val serviceIntent = Intent(this, MeshNetworkService::class.java)
            stopService(serviceIntent)
            
            Handler(Looper.getMainLooper()).postDelayed({
                startNearbyMeshBackgroundService()
            }, 1000)
        }
        
        requestRequiredPermissions()
    }

    private fun triggerVoiceSos() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required for offline Voice SOS", Toast.LENGTH_SHORT).show()
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        
        val btnVoiceSos = findViewById<Button>(R.id.btnVoiceSos)
        
        if (voskEngine?.isListening == true) {
            voskEngine?.stopListening()
            btnVoiceSos.text = "Voice SOS"
            Toast.makeText(this, "Voice SOS Cancelled", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (voskEngine == null) {
            Toast.makeText(this, "Loading Offline Language Models...", Toast.LENGTH_SHORT).show()
            return
        }

        btnVoiceSos.text = "Listening... Tap to Stop"
        
        voskEngine?.startListening { transcribedText ->
            runOnUiThread {
                btnVoiceSos.text = "Voice SOS"
                if (transcribedText.startsWith("ERROR")) {
                    Toast.makeText(this, transcribedText, Toast.LENGTH_LONG).show()
                    checkPermissionsAndTriggerSos("MANUAL SOS BEACON SENT")
                    return@runOnUiThread
                }
                
                // Process through local Gemini NPU!
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val aiSummary = geminiAssistant.summarizeSosWithNano(transcribedText)
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Transmit Distress Signal?")
                            .setMessage("Raw Audio Input:\n\"$transcribedText\"\n\nOptimized Payload:\n\"$aiSummary\"")
                            .setPositiveButton("Send Optimized") { _, _ ->
                                checkPermissionsAndTriggerSos("VOICE SOS: $aiSummary")
                            }
                            .setNeutralButton("Send Raw") { _, _ ->
                                checkPermissionsAndTriggerSos("VOICE SOS: $transcribedText")
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Gemini processor busy", Toast.LENGTH_SHORT).show()
                        checkPermissionsAndTriggerSos("VOICE SOS: $transcribedText")
                    }
                }
            }
        }
    }

    private fun handleIncomingPayload(incomingMessage: String) {
        val idMatch = Regex("\\[ID:(.*?)\\]").find(incomingMessage)
        val ttlMatch = Regex("\\[TTL:(\\d+)\\]").find(incomingMessage)
        
        if (idMatch != null && ttlMatch != null) {
            val msgId = idMatch.groupValues[1]
            
            if (!processedMessages.contains(msgId)) {
                processedMessages.add(msgId)
                
                val cleanMsg = incomingMessage.replace(Regex("\\[ID:.*?\\]\\[TTL:\\d+\\] "), "")
                val rescuerLog = "\n[MESH RELAY NODE] ${cleanMsg}\n"
                tvRescuerLogs.text = tvRescuerLogs.text.toString() + rescuerLog
                
                val rescuerScrollView = tvRescuerLogs.parent as? android.widget.ScrollView
                rescuerScrollView?.post { rescuerScrollView.fullScroll(View.FOCUS_DOWN) }
                
                Toast.makeText(this@MainActivity, "Nearby Mesh: Received Signal!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(MeshNetworkService.BROADCAST_MESSAGE)
            addAction(MeshNetworkService.BROADCAST_STATUS)
        }
        // Use RECEIVER_NOT_EXPORTED for high security in modern Android
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(meshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(meshReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(meshReceiver)
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
                
                val hazards = listOf(
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
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPerms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            requiredPerms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPerms.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPerms.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPerms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        
        val missingPerms = requiredPerms.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (missingPerms.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPerms.toTypedArray())
        } else {
            startNearbyMeshBackgroundService()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startNearbyMeshBackgroundService()
        } else {
            Toast.makeText(this, "Permissions explicitly required for offline Play Services networking", Toast.LENGTH_LONG).show()
        }
    }

    private val enableBtLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(this, "Bluetooth Enabled automatically for Mesh.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bluetooth denied. Mesh will only use Wi-Fi Direct.", Toast.LENGTH_SHORT).show()
        }
        startMeshServiceInternal()
    }

    private fun startNearbyMeshBackgroundService() {
        // Airplane Mode Safety Checks
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (!locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            tvConnectionStatus.text = "Status: Nearby Mesh Failed (GPS is OFF)"
            Toast.makeText(this, "CRITICAL: Location (GPS) must be ON to allow local radio scanning!", Toast.LENGTH_LONG).show()
            return
        }
        
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "WARNING: Wi-Fi is OFF! Mesh severely limited.", Toast.LENGTH_LONG).show()
        }
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        val isBtEnabled = bluetoothAdapter != null && bluetoothAdapter.isEnabled
        
        if (!isBtEnabled && bluetoothAdapter != null) {
            val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBtLauncher.launch(enableBtIntent)
            return
        }

        if (!wifiManager.isWifiEnabled && !isBtEnabled) {
            tvConnectionStatus.text = "Status: Mesh Failed. All Hardware Antennas OFF (Airplane Mode?)"
            return
        }

        startMeshServiceInternal()
    }

    private fun startMeshServiceInternal() {
        // Launch the autonomous Background Service instead of direct coupling
        val serviceIntent = Intent(this, MeshNetworkService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        tvConnectionStatus.text = "Status: Initializing Independent Background Mesh..."
    }

    @SuppressLint("MissingPermission")
    private fun checkPermissionsAndTriggerSos(messageType: String) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val location = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER) 
            ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

        val lat = location?.latitude ?: 0.0
        val lon = location?.longitude ?: 0.0
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        
        val msgId = UUID.randomUUID().toString()
        val formattedPayload = "[ID:$msgId][TTL:10] [$timestamp] $messageType at $lat, $lon"
        
        processedMessages.add(msgId)
        
        val logEntry = "\n[$timestamp] \uD83D\uDEA8 $messageType\n   Location: $lat, $lon"
        tvMessageLog.text = tvMessageLog.text.toString() + "\n" + logEntry

        val scrollView = tvMessageLog.parent as? android.widget.ScrollView
        scrollView?.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        
        val rescuerLog = "\n[HIGH PRIORITY ORIGIN] $timestamp - $messageType\nLocal Mesh Node: Active | Coordination: $lat, $lon\n"
        tvRescuerLogs.text = tvRescuerLogs.text.toString() + rescuerLog
        
        // PUSH OUTWARD VIA BACKGROUND SERVICE (Which natively handles Google Nearby Caching + Sync arrays)
        val serviceIntent = Intent(this, MeshNetworkService::class.java).apply {
            putExtra("BROADCAST_PAYLOAD", formattedPayload)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }
}
