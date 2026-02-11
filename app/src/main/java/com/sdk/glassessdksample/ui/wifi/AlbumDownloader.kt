package com.sdk.glassessdksample.ui.wifi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WiFi Transfer - Download media files from HeyCyan Smart Glasses
 * Based on: https://github.com/ebowwa/HeyCyanSmartGlassesSDK
 * 
 * Glasses create a WiFi hotspot when transfer mode is enabled.
 * Files are accessible at http://{GLASSES_IP}/files/
 */
data class MediaItem(
    val fileName: String, 
    val type: Int  // 1 = JPG image, 2 = MP4 video, 3 = Audio
)

data class MediaConfig(
    val files: List<MediaItem>
)

class AlbumDownloader(private val ctx: Context) {
    
    companion object {
        private const val TAG = "AlbumDownloader"
        
        // ✅ PRIORITY IPs first (most common Glass addresses), then fallbacks
        // Ordered by likelihood for faster discovery
        val POSSIBLE_IPS = listOf(
            // Priority: Most common Glass IPs (tested first)
            "192.168.49.1",   // WiFi Direct Group Owner (most common)
            "192.168.49.99",  // WiFi Direct typical client
            "192.168.49.101", // WiFi Direct alternate
            "192.168.42.129", // HeyCyan glasses specific
            "192.168.43.129", // HeyCyan glasses specific 2
            "192.168.43.1",   // Android hotspot default
            
            // Fallback: Less common but possible
            "192.168.4.1",    // ESP default
            "192.168.137.1",  // Windows hotspot
            "192.168.31.1",   // Xiaomi
            "192.168.1.1",    // Common router
            "192.168.0.1",    // Common router
            "192.168.100.1",  // Some devices
            "192.168.123.1",  // Some devices
            "10.0.0.1",       // Apple default
            "172.20.10.1"     // iOS hotspot
        )
    }
    
    private val okClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    /**
     * Get gateway IP from current WiFi connection
     */
    @Suppress("DEPRECATION")
    private fun getGatewayIP(): String? {
        try {
            val wifiManager = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcpInfo = wifiManager.dhcpInfo
            val gateway = dhcpInfo.gateway
            if (gateway != 0) {
                val gatewayIP = String.format(
                    "%d.%d.%d.%d",
                    gateway and 0xff,
                    (gateway shr 8) and 0xff,
                    (gateway shr 16) and 0xff,
                    (gateway shr 24) and 0xff
                )
                Log.i(TAG, "📡 Gateway IP from DHCP: $gatewayIP")
                return gatewayIP
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get gateway IP: ${e.message}")
        }
        return null
    }
    
    /**
     * Discover glasses IP by testing common hotspot addresses
     */
    suspend fun discoverGlassesIP(): String? = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔍 Discovering glasses IP...")
        
        // First try the gateway IP from current WiFi connection
        val gatewayIP = getGatewayIP()
        if (gatewayIP != null && !POSSIBLE_IPS.contains(gatewayIP)) {
            Log.i(TAG, "🔍 Testing gateway IP first: $gatewayIP")
            if (testGlassesIP(gatewayIP)) {
                return@withContext gatewayIP
            }
        }
        
        for (ip in POSSIBLE_IPS) {
            if (testGlassesIP(ip)) {
                return@withContext ip
            }
        }
        
        Log.w(TAG, "❌ Could not find glasses on any known IP")
        null
    }
    
    /**
     * Test if glasses server is available at given IP
     */
    private fun testGlassesIP(ip: String): Boolean {
        try {
            val testUrl = "http://$ip/files/media.config"
            Log.d(TAG, "Testing: $testUrl")
            
            val request = Request.Builder()
                .url(testUrl)
                .build()
            
            val response = okClient.newCall(request).execute()
            if (response.isSuccessful) {
                Log.i(TAG, "✅ Found glasses at: $ip")
                response.close()
                return true
            }
            response.close()
        } catch (e: Exception) {
            // IP not available
        }
        return false
    }
    
    /**
     * Fetch media config from glasses
     * Config file at: http://{IP}/files/media.config
     */
    suspend fun fetchConfig(baseIp: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val url = "http://$baseIp/files/media.config"
        Log.i(TAG, "📥 Fetching config from: $url")
        
        try {
            val request = Request.Builder().url(url).build()
            val response = okClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Config fetch failed: ${response.code}")
                return@withContext emptyList()
            }
            
            val body = response.body?.string() ?: ""
            response.close()
            
            Log.d(TAG, "Config response: $body")
            
            // Parse config. Support two formats:
            // 1) legacy CSV per-line: "filename,type" (type: 1=JPG,2=MP4,3=Audio)
            // 2) plaintext newline-separated filenames (most glass firmwares)
            val items = mutableListOf<MediaItem>()
            body.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach

                val parts = trimmed.split(",")
                if (parts.size >= 2) {
                    try {
                        val fileName = parts[0].trim()
                        val fileType = parts[1].trim().toIntOrNull() ?: when {
                            fileName.endsWith(".mp4", true) -> 2
                            fileName.endsWith(".jpg", true) -> 1
                            else -> 1
                        }
                        items.add(MediaItem(fileName, fileType))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse line: $line")
                    }
                } else {
                    // Plain filename per line — infer type by extension
                    val fileName = trimmed
                    val fileType = when {
                        fileName.endsWith(".mp4", true) -> 2
                        fileName.endsWith(".jpg", true) -> 1
                        else -> 1
                    }
                    items.add(MediaItem(fileName, fileType))
                }
            }

            Log.i(TAG, "✅ Found ${items.size} media files")
            items
            
        } catch (e: Exception) {
            Log.e(TAG, "Config fetch error: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Download a single file from glasses
     */
    suspend fun downloadFile(
        baseIp: String,
        fileName: String,
        outputDir: File,
        progressCallback: ((Int) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val url = "http://$baseIp/files/$fileName"
        Log.i(TAG, "📥 Downloading: $url")
        
        try {
            val request = Request.Builder().url(url).build()
            val response = okClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.w(TAG, "Download failed: ${response.code}")
                response.close()
                return@withContext null
            }
            
            val body = response.body ?: run {
                response.close()
                return@withContext null
            }
            
            // Create output file
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            val outputFile = File(outputDir, fileName)
            
            // Stream download with progress
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            
            outputFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            progressCallback?.invoke(progress)
                        }
                    }
                }
            }
            
            response.close()
            Log.i(TAG, "✅ Downloaded: $fileName (${downloadedBytes / 1024} KB)")
            outputFile
            
        } catch (e: IOException) {
            Log.e(TAG, "Download error: ${e.message}")
            null
        }
    }
    
    /**
     * Download all media files from glasses
     */
    suspend fun downloadAllMedia(
        baseIp: String,
        outputDir: File,
        progressCallback: ((String, Int, Int) -> Unit)? = null
    ): List<File> = withContext(Dispatchers.IO) {
        val downloadedFiles = mutableListOf<File>()
        
        // Get file list
        val mediaItems = fetchConfig(baseIp)
        if (mediaItems.isEmpty()) {
            Log.w(TAG, "No media files found")
            return@withContext emptyList()
        }
        
        Log.i(TAG, "📥 Starting download of ${mediaItems.size} files...")
        
        mediaItems.forEachIndexed { index, item ->
            progressCallback?.invoke(item.fileName, index + 1, mediaItems.size)
            
            val file = downloadFile(baseIp, item.fileName, outputDir) { progress ->
                Log.d(TAG, "${item.fileName}: $progress%")
            }
            
            if (file != null) {
                downloadedFiles.add(file)
            }
        }
        
        Log.i(TAG, "✅ Download complete: ${downloadedFiles.size}/${mediaItems.size} files")
        downloadedFiles
    }
}
