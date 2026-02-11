package com.sdk.glassessdksample.ui.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * WiFi Transfer Manager for HeyCyan Smart Glasses
 * Based on: https://github.com/ebowwa/HeyCyanSmartGlassesSDK
 * 
 * Manages the complete WiFi transfer workflow:
 * 1. Enable transfer mode on glasses (via Bluetooth command)
 * 2. Connect to glasses WiFi hotspot
 * 3. Discover glasses IP
 * 4. Download media files
 */
class WifiTransferManager(private val context: Context) {
    
    companion object {
        private const val TAG = "WifiTransferManager"
        
        // HeyCyan glasses hotspot naming patterns
        val GLASSES_SSID_PATTERNS = listOf(
            "HeyCyan",
            "GLASSES",
            "SmartGlasses",
            "QCGlasses",
            "CY01",      // 🆕 CY01 Glass WiFi hotspot
            "M01",       // M01 Glass hotspot
            "DIRECT-",   // WiFi Direct pattern
            "Android"    // Generic Android hotspot
        )
    }
    
    private val wifiManager: WifiManager = 
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    
    private val albumDownloader = AlbumDownloader(context)
    
    /**
     * Listener interface for transfer status updates
     */
    interface TransferStatusListener {
        fun onStatusUpdate(status: String)
        fun onProgressUpdate(fileName: String, current: Int, total: Int)
        fun onTransferComplete(downloadedFiles: Int, totalFiles: Int)
        fun onTransferError(error: String)
    }
    
    private var statusListener: TransferStatusListener? = null
    
    fun setStatusListener(listener: TransferStatusListener) {
        this.statusListener = listener
    }
    
    /**
     * Check if currently connected to glasses WiFi
     */
    @Suppress("DEPRECATION")
    fun isConnectedToGlassesWiFi(): Boolean {
        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo.ssid?.replace("\"", "") ?: return false
        
        return GLASSES_SSID_PATTERNS.any { pattern ->
            ssid.contains(pattern, ignoreCase = true)
        }
    }
    
    /**
     * Get current WiFi SSID
     */
    @Suppress("DEPRECATION")
    fun getCurrentSSID(): String {
        return wifiManager.connectionInfo?.ssid?.replace("\"", "") ?: "Unknown"
    }
    
    /**
     * Start the complete WiFi transfer process
     */
    suspend fun startTransfer(
        outputDir: java.io.File
    ): TransferResult = withContext(Dispatchers.IO) {
        
        statusListener?.onStatusUpdate("Starting WiFi transfer...")
        
        // Step 1: Check WiFi connection
        if (!isConnectedToGlassesWiFi()) {
            Log.w(TAG, "Not connected to glasses WiFi")
            statusListener?.onStatusUpdate("Please connect to glasses WiFi hotspot first")
            
            // Try to discover glasses anyway (user might be on correct network)
        }
        
        statusListener?.onStatusUpdate("Discovering glasses...")
        
        // Step 2: Discover glasses IP
        val glassesIP = albumDownloader.discoverGlassesIP()
        
        if (glassesIP == null) {
            val error = "Could not find glasses. Make sure:\n" +
                    "1. Glasses are in transfer mode\n" +
                    "2. Phone is connected to glasses WiFi hotspot"
            statusListener?.onTransferError(error)
            return@withContext TransferResult.Error(error)
        }
        
        statusListener?.onStatusUpdate("Found glasses at $glassesIP")
        Log.i(TAG, "✅ Glasses found at: $glassesIP")
        
        // Step 3: Download all media
        statusListener?.onStatusUpdate("Downloading media files...")
        
        val downloadedFiles = albumDownloader.downloadAllMedia(
            baseIp = glassesIP,
            outputDir = outputDir
        ) { fileName, current, total ->
            statusListener?.onProgressUpdate(fileName, current, total)
            statusListener?.onStatusUpdate("Downloading $fileName ($current/$total)")
        }
        
        // Step 4: Report results
        if (downloadedFiles.isNotEmpty()) {
            statusListener?.onTransferComplete(downloadedFiles.size, downloadedFiles.size)
            statusListener?.onStatusUpdate("Transfer complete: ${downloadedFiles.size} files downloaded")
            
            TransferResult.Success(
                downloadedFiles = downloadedFiles.size,
                glassesIP = glassesIP
            )
        } else {
            statusListener?.onStatusUpdate("No files to download")
            TransferResult.NoFiles
        }
    }
    
    /**
     * Test connection to specific IP
     */
    suspend fun testConnection(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val address = InetAddress.getByName(ip)
            address.isReachable(5000)
        } catch (e: Exception) {
            Log.w(TAG, "Connection test failed: ${e.message}")
            false
        }
    }
    
    /**
     * Result of transfer operation
     */
    sealed class TransferResult {
        data class Success(val downloadedFiles: Int, val glassesIP: String) : TransferResult()
        data class Error(val message: String) : TransferResult()
        object NoFiles : TransferResult()
    }
}
