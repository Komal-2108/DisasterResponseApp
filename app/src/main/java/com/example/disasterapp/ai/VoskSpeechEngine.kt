package com.example.disasterapp.ai

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VoskSpeechEngine(private val context: Context, private val onModelReady: (Boolean) -> Unit) : RecognitionListener {
    
    private var model: Model? = null
    private var speechService: SpeechService? = null
    var isListening = false
    private var activeCallback: ((String) -> Unit)? = null
    
    init {
        // Deep unpacker to manually extract the assets folder and bypass Vosk's internal 
        // bug-prone StorageService sync requirements.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val destDir = File(context.getExternalFilesDir(null), "vosk-model")
                
                // --- HARD CACHE CLEAR ---
                // Deletes the stagnant Hindi model from the phone's internal storage
                // so the new English model from the assets folder is forcefully loaded.
                if (destDir.exists()) {
                    destDir.deleteRecursively()
                }
                
                copyAssetFolder(context.assets, "model", destDir.absolutePath)

                // Bind the native C++ engine dynamically to the extracted disk path
                model = Model(destDir.absolutePath)
                
                withContext(Dispatchers.Main) {
                    onModelReady(true)
                }
            } catch (e: Exception) {
                Log.e("VoskEngine", "Catastrophic Unpack Error", e)
                withContext(Dispatchers.Main) {
                    onModelReady(false)
                }
            }
        }
    }
    
    private fun copyAssetFolder(assetManager: android.content.res.AssetManager, fromAssetPath: String, toPath: String) {
        val files = assetManager.list(fromAssetPath) ?: return
        val targetDir = File(toPath)
        targetDir.mkdirs()

        for (filename in files) {
            val assetSubPath = if (fromAssetPath.isEmpty()) filename else "$fromAssetPath/$filename"
            val subFiles = assetManager.list(assetSubPath)

            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssetFolder(assetManager, assetSubPath, "$toPath/$filename")
            } else {
                val outFile = File(toPath, filename)
                if (!outFile.exists()) {
                    assetManager.open(assetSubPath).use { inputStream ->
                        FileOutputStream(outFile).use { outputStream ->
                            val buffer = ByteArray(1024)
                            var length: Int
                            while (inputStream.read(buffer).also { length = it } > 0) {
                                outputStream.write(buffer, 0, length)
                            }
                        }
                    }
                }
            }
        }
    }
    
    fun startListening(callback: (String) -> Unit) {
        if (model == null) {
            callback("ERROR: Model not loaded. Be sure 'model' is inside assets folder.")
            return
        }
        
        activeCallback = callback
        try {
            val recognizer = Recognizer(model, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)
            isListening = true
        } catch (e: Exception) {
            Log.e("VoskEngine", "Microphone bind error", e)
            callback("ERROR: Failed to start microphone")
        }
    }
    
    fun stopListening() {
        speechService?.stop()
        speechService = null
        isListening = false
    }

    override fun onPartialResult(hypothesis: String?) {
        // Ignored for now to avoid rapid spam. We want the full finalized burst.
    }

    override fun onResult(hypothesis: String?) {
        hypothesis?.let {
            val parsedText = extractTextFromJson(it)
            if (parsedText.isNotEmpty()) {
                activeCallback?.invoke(parsedText)
                stopListening()
            }
        }
    }

    override fun onFinalResult(hypothesis: String?) {
        hypothesis?.let {
            val parsedText = extractTextFromJson(it)
            if (parsedText.isNotEmpty()) {
                activeCallback?.invoke(parsedText)
            }
            stopListening()
        }
    }

    override fun onError(e: Exception?) {
        Log.e("VoskEngine", "Vosk Internal Error", e)
        activeCallback?.invoke("ERROR: Vosk encountered an issue")
        stopListening()
    }

    override fun onTimeout() {
        activeCallback?.invoke("ERROR: Timeout reached")
        stopListening()
    }
    
    private fun extractTextFromJson(jsonString: String): String {
        return try {
            val json = JSONObject(jsonString)
            json.optString("text", "")
        } catch (e: Exception) {
            ""
        }
    }
}
