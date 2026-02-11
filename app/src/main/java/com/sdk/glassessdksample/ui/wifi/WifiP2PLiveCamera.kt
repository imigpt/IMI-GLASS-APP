package com.sdk.glassessdksample.ui.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * WiFi P2P Live Camera - Fast image streaming from glasses via WiFi Direct
 * 
 * How it works:
 * 1. Connect to Glass hotspot for FAST image transfer
 * 2. Use Mobile Data for Gemini AI API (bound to cellular network)
 * 3. Best of both worlds - fast transfer + internet access
 * 
 * Flow:
 * 📷 Glass Camera → (WiFi P2P) → Phone → (Mobile Data) → Gemini AI → (Bluetooth) → Glass Speaker
 */
class WifiP2PLiveCamera(private val context: Context) {
    
    companion object {
        private const val TAG = "WifiP2PLiveCamera"
        
        // Glasses hotspot IPs
        val GLASSES_IPS = listOf(
            "192.168.42.129",
            "192.168.43.129", 
            "192.168.49.1",
            "192.168.43.1"
        )
        
        // Live photo endpoint (glasses serve latest captured photo)
        const val LIVE_PHOTO_ENDPOINT = "/files/live.jpg"
        const val CAPTURE_COMMAND_ENDPOINT = "/capture"
    }
    
    private val okClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private var glassesIP: String? = null
    private var isStreaming = false
    private var streamJob: Job? = null
    private var cellularNetwork: Network? = null
    
    /**
     * Listener for live camera events
     */
    interface LiveCameraListener {
        fun onPhotoReceived(imageBytes: ByteArray)
        fun onStatusUpdate(status: String)
        fun onError(error: String)
    }
    
    private var listener: LiveCameraListener? = null
    
    fun setListener(l: LiveCameraListener) {
        listener = l
    }
    
    /**
     * Bind to cellular network for internet access while on Glass WiFi
     * This allows using mobile data for Gemini API even when WiFi is on Glass hotspot
     */
    fun bindToCellularNetwork() {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    cellularNetwork = network
                    Log.i(TAG, "✅ Cellular network bound for Gemini API")
                    listener?.onStatusUpdate("Mobile data ready for AI")
                }
                
                override fun onLost(network: Network) {
                    if (cellularNetwork == network) {
                        cellularNetwork = null
                        Log.w(TAG, "⚠️ Cellular network lost")
                    }
                }
            })
            
            Log.i(TAG, "📶 Requesting cellular network binding...")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind cellular: ${e.message}")
        }
    }
    
    /**
     * Get OkHttpClient bound to cellular network for API calls
     */
    fun getCellularClient(): OkHttpClient {
        val network = cellularNetwork
        return if (network != null) {
            OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        } else {
            // Fallback to default (might use WiFi)
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
    
    /**
     * Discover glasses IP on WiFi hotspot
     */
    suspend fun discoverGlasses(): String? = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔍 Discovering glasses on WiFi...")
        listener?.onStatusUpdate("Searching for glasses...")
        
        for (ip in GLASSES_IPS) {
            if (testConnection(ip)) {
                glassesIP = ip
                Log.i(TAG, "✅ Found glasses at: $ip")
                listener?.onStatusUpdate("Glasses found at $ip")
                return@withContext ip
            }
        }
        
        Log.w(TAG, "❌ Glasses not found on WiFi")
        listener?.onError("Glasses not found. Connect to Glass hotspot first.")
        null
    }
    
    /**
     * Test connection to glasses
     */
    private fun testConnection(ip: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://$ip/files/")
                .head()
                .build()
            
            val response = okClient.newCall(request).execute()
            val success = response.isSuccessful || response.code == 404 // 404 means server is there
            response.close()
            success
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Capture photo from glasses via WiFi
     * Faster than Bluetooth!
     */
    suspend fun capturePhoto(): ByteArray? = withContext(Dispatchers.IO) {
        val ip = glassesIP ?: return@withContext null
        
        try {
            // First trigger capture
            try {
                val captureRequest = Request.Builder()
                    .url("http://$ip$CAPTURE_COMMAND_ENDPOINT")
                    .build()
                okClient.newCall(captureRequest).execute().close()
            } catch (e: Exception) {
                // Capture endpoint might not exist, continue anyway
            }
            
            // Small delay for capture
            delay(300)
            
            // Fetch the latest photo
            val request = Request.Builder()
                .url("http://$ip$LIVE_PHOTO_ENDPOINT")
                .build()
            
            val response = okClient.newCall(request).execute()
            
            if (response.isSuccessful) {
                val bytes = response.body?.bytes()
                response.close()
                
                if (bytes != null && bytes.size > 1000) {
                    Log.d(TAG, "📷 Photo received: ${bytes.size} bytes")
                    return@withContext bytes
                }
            }
            response.close()
            null
            
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture error: ${e.message}")
            null
        }
    }
    
    /**
     * Start continuous live streaming
     */
    fun startStreaming(intervalMs: Long = 2000L) {
        if (isStreaming) return
        
        isStreaming = true
        Log.i(TAG, "🎥 Starting WiFi P2P live stream...")
        listener?.onStatusUpdate("Live stream starting...")
        
        streamJob = CoroutineScope(Dispatchers.IO).launch {
            // Discover glasses first
            if (glassesIP == null) {
                discoverGlasses()
            }
            
            if (glassesIP == null) {
                listener?.onError("Cannot start stream - glasses not found")
                isStreaming = false
                return@launch
            }
            
            listener?.onStatusUpdate("Live stream active")
            
            while (isStreaming) {
                try {
                    val photo = capturePhoto()
                    if (photo != null) {
                        listener?.onPhotoReceived(photo)
                    }
                    delay(intervalMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Stream error: ${e.message}")
                    delay(1000)
                }
            }
        }
    }
    
    /**
     * Stop streaming
     */
    fun stopStreaming() {
        isStreaming = false
        streamJob?.cancel()
        streamJob = null
        Log.i(TAG, "🎥 WiFi P2P live stream stopped")
        listener?.onStatusUpdate("Stream stopped")
    }
    
    /**
     * Check if cellular network is available for API calls
     */
    fun isCellularAvailable(): Boolean = cellularNetwork != null
    
    /**
     * Check if connected to glasses WiFi
     */
    fun isConnectedToGlasses(): Boolean = glassesIP != null
}
