package com.sdk.glassessdksample.ui.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Environment
import android.util.Log
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Glass Media Transfer - Download Photos/Videos from Glass via WiFi Hotspot
 * 
 * Flow:
 * 1. Trigger Glass Hotspot via BLE command
 * 2. Phone connects to Glass WiFi hotspot
 * 3. Socket connection on port 8888
 * 4. Download media files
 * 5. Show in app
 */
class GlassMediaTransfer(private val context: Context) {
    
    companion object {
        private const val TAG = "GlassMediaTransfer"
        private const val SERVER_PORT = 8888
        private const val SOCKET_TIMEOUT = 3000 // 3 seconds timeout (reduced for faster scanning)
        private const val MAX_RETRY_ATTEMPTS = 2
        
        // Glass hotspot IP addresses (standard for WiFi Direct/Hotspot)
        // WiFi Direct P2P typically uses 192.168.49.x range
        // When phone is GO: Glass gets 192.168.49.x (x > 1)
        // When Glass is GO: Glass is at 192.168.49.1
        val GLASS_IPS = listOf(
            "192.168.49.1",     // WiFi Direct P2P (Glass as GO)
            "192.168.43.1",     // Hotspot mode
            "192.168.42.1"      // USB tethering / alternate hotspot
        )
        
        // IPs to scan when Phone is Group Owner (Glass is client)
        // Glass will get an IP in 192.168.49.x range (x = 2-20)
        val GLASS_CLIENT_IPS = (2..20).map { "192.168.49.$it" }
    }
    
    private var serverIpAddress: String? = null
    private var isConnected = false
    private var serverPort: Int = SERVER_PORT
    private var downloadJob: Job? = null

    fun setServerPort(port: Int) {
        serverPort = port
        Log.i(TAG, "✅ Server port set to: $port")
    }
    
    /**
     * Set server IP address directly (from WifiP2pHelper)
     */
    fun setServerIp(ip: String) {
        serverIpAddress = ip
        isConnected = true
        Log.i(TAG, "✅ Server IP set to: $ip")
        listener?.onConnected(ip)
    }
    
    /**
     * Media file info
     */
    data class MediaFileInfo(
        val fileName: String,
        val fileType: String, // "photo" or "video"
        val fileSize: Long,
        val timestamp: Long,
        var localPath: String? = null,
        var isDownloaded: Boolean = false
    )
    
    /**
     * Listener for transfer events
     */
    interface TransferListener {
        fun onHotspotTriggered()
        fun onConnected(ip: String)
        fun onDisconnected()
        fun onMediaListReceived(files: List<MediaFileInfo>)
        fun onDownloadProgress(fileName: String, progress: Int)
        fun onDownloadComplete(fileName: String, localPath: String)
        fun onDownloadError(fileName: String, error: String)
        fun onError(error: String)
    }
    
    private var listener: TransferListener? = null
    
    fun setListener(l: TransferListener) {
        listener = l
    }
    
    /**
     * Step 1: Trigger Glass Hotspot/P2P via BLE command
     * Glass will turn on its WiFi P2P mode for photo/video transfer
     * 
     * BLE Commands discovered from decompiled app:
     * - {2, 1, 4}  = Start import/transfer (triggers P2P)
     * - {2, 1, 15} = Reset device P2P
     * - {2, 4}     = Read album count
     * - {2, 1, 9}  = Complete download signal
     */
    fun triggerGlassHotspot() {
        Log.i(TAG, "📡 Triggering Glass P2P Transfer via BLE...")
        
        try {
            // First reset P2P state on Glass
            val resetCommand = byteArrayOf(2, 1, 15)
            LargeDataHandler.getInstance().glassesControl(resetCommand) { code, error ->
                Log.i(TAG, "🔄 P2P Reset command sent: code=$code")
            }
            
            // Then start import/transfer mode - this triggers Glass P2P
            // Command: {2, 1, 4} = dataType=2, glassWorkType=1, action=4 (start transfer)
            val startTransferCommand = byteArrayOf(2, 1, 4)
            
            LargeDataHandler.getInstance().glassesControl(startTransferCommand) { code, error ->
                Log.i(TAG, "📤 Transfer command response: code=$code")
                if (error != null) {
                    when (error.dataType) {
                        1 -> {
                            if (error.glassWorkType == 4 && error.errorCode == 0) {
                                Log.i(TAG, "✅ Transfer mode started successfully!")
                                listener?.onHotspotTriggered()
                            } else {
                                Log.w(TAG, "⚠️ Glass workType: ${error.workTypeIng}")
                                // Still trigger - user connects to Glass WiFi
                                listener?.onHotspotTriggered()
                            }
                        }
                        else -> {
                            Log.i(TAG, "📡 Command sent, waiting for P2P...")
                            listener?.onHotspotTriggered()
                        }
                    }
                } else {
                    Log.i(TAG, "✅ Command sent successfully")
                    listener?.onHotspotTriggered()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering transfer: ${e.message}")
            listener?.onError("Failed to trigger transfer: ${e.message}")
        }
    }
    
    /**
     * Read album counts from Glass
     */
    fun readAlbumCounts() {
        try {
            val readCountCommand = byteArrayOf(2, 4)
            LargeDataHandler.getInstance().glassesControl(readCountCommand) { code, error ->
                if (error != null && error.dataType == 4) {
                    val imageCount = error.imageCount
                    val videoCount = error.videoCount
                    val recordCount = error.recordCount
                    Log.i(TAG, "📊 Album: $imageCount images, $videoCount videos, $recordCount records")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading album counts: ${e.message}")
        }
    }
    
    /**
     * Signal download complete to Glass
     */
    fun signalDownloadComplete() {
        try {
            val completeCommand = byteArrayOf(2, 1, 9)
            LargeDataHandler.getInstance().glassesControl(completeCommand) { code, error ->
                Log.i(TAG, "✅ Download complete signal sent: code=$code")
            }
            // Remove callback
            LargeDataHandler.getInstance().removeGlassesControlCallback()
        } catch (e: Exception) {
            Log.e(TAG, "Error signaling complete: ${e.message}")
        }
    }
    
    /**
     * Step 2: Connect to Glass WiFi hotspot
     * Returns true if connection found
     * Handles both cases:
     * - Phone is Group Owner (Glass gets 192.168.49.x, x>1)
     * - Glass is Group Owner (Glass is at 192.168.49.1)
     */
    suspend fun connectToGlass(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔍 Searching for Glass on WiFi...")
        
        // First, detect phone's IP to determine who is Group Owner
        val phoneIp = getPhoneP2pIp()
        Log.i(TAG, "📱 Phone P2P IP: $phoneIp")
        
        val isPhoneGroupOwner = phoneIp == "192.168.49.1"
        Log.i(TAG, "📱 Phone is Group Owner: $isPhoneGroupOwner")
        
        // Determine which IPs to scan
        val ipsToScan = if (isPhoneGroupOwner) {
            // Phone is GO, Glass is client - scan 192.168.49.2-20
            Log.i(TAG, "🔍 Scanning for Glass as P2P client (192.168.49.2-20)...")
            GLASS_CLIENT_IPS
        } else {
            // Glass is GO or other mode - try standard IPs
            Log.i(TAG, "🔍 Scanning standard Glass IPs...")
            GLASS_IPS
        }
        
        // Try to find Glass
        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            Log.i(TAG, "🔄 Connection attempt $attempt of $MAX_RETRY_ATTEMPTS")
            
            for (ip in ipsToScan) {
                // Skip phone's own IP
                if (ip == phoneIp) continue
                
                Log.d(TAG, "Trying IP: $ip")
                if (testConnection(ip)) {
                    serverIpAddress = ip
                    isConnected = true
                    Log.i(TAG, "✅ Glass found at: $ip")
                    withContext(Dispatchers.Main) {
                        listener?.onConnected(ip)
                    }
                    return@withContext true
                }
            }
            
            // Wait before next attempt (Glass server may be starting)
            if (attempt < MAX_RETRY_ATTEMPTS) {
                Log.d(TAG, "⏳ Waiting 3 seconds before retry...")
                delay(3000)
            }
        }
        
        Log.w(TAG, "❌ Glass not found on any IP after $MAX_RETRY_ATTEMPTS attempts")
        withContext(Dispatchers.Main) {
            listener?.onError("Glass not found. Glass server may not be running on port $serverPort.")
        }
        false
    }
    
    /**
     * Get phone's P2P IP address
     */
    private fun getPhoneP2pIp(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                // Look for P2P interface
                if (iface.name.contains("p2p") || iface.name == "wlan0") {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val ip = addr.hostAddress
                            if (ip?.startsWith("192.168.49.") == true) {
                                return ip
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting P2P IP: ${e.message}")
        }
        return null
    }
    
    /**
     * Check if phone is connected to Glass WiFi
     * Note: Returns true if we have any WiFi connection with valid IP
     * SSID detection requires location permission which may not be available
     */
    private fun isConnectedToGlassWiFi(): Boolean {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo.ssid?.replace("\"", "") ?: ""
            
            Log.d(TAG, "Current WiFi SSID: $ssid")
            
            // Check if we have a valid IP - this works even without location permission
            val ipAddress = wifiInfo.ipAddress
            val hasValidIp = ipAddress != 0
            
            if (hasValidIp) {
                val ipString = String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    (ipAddress shr 8) and 0xff,
                    (ipAddress shr 16) and 0xff,
                    (ipAddress shr 24) and 0xff
                )
                Log.i(TAG, "📱 Phone IP: $ipString")
                
                // Check if IP is in Glass hotspot/WiFi Direct range
                val isGlassRange = ipString.startsWith("192.168.42.") ||
                                   ipString.startsWith("192.168.43.") ||
                                   ipString.startsWith("192.168.49.") ||  // WiFi Direct P2P
                                   ipString.startsWith("192.168.1.") ||
                                   ipString.startsWith("192.168.0.")
                                   
                if (isGlassRange) {
                    Log.i(TAG, "✅ IP is in Glass hotspot/P2P range: $ipString")
                    return true
                }
            }
            
            // Also check WiFi Direct P2P connection using NetworkInterfaces
            try {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.name.contains("p2p") || iface.name.contains("wlan")) {
                        val addresses = iface.inetAddresses
                        while (addresses.hasMoreElements()) {
                            val addr = addresses.nextElement()
                            if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                                val ip = addr.hostAddress ?: ""
                                Log.d(TAG, "Network interface ${iface.name}: $ip")
                                if (ip.startsWith("192.168.49.") || ip.startsWith("192.168.43.")) {
                                    Log.i(TAG, "✅ Found P2P interface with Glass IP: $ip")
                                    return true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking network interfaces: ${e.message}")
            }
            
            // If SSID is known, check it
            if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                val isGlassWifi = ssid.contains("M01", ignoreCase = true) ||
                        ssid.contains("glass", ignoreCase = true) ||
                        ssid.contains("heycyan", ignoreCase = true) ||
                        ssid.contains("cyan", ignoreCase = true) ||
                        ssid.contains("direct", ignoreCase = true) ||
                        ssid.contains("p2p", ignoreCase = true)
                        
                if (isGlassWifi) {
                    Log.i(TAG, "✅ Connected to Glass WiFi: $ssid")
                    return true
                }
            }
            
            // Just return true if we have any valid IP - will check via socket
            return hasValidIp
        } catch (e: Exception) {
            Log.e(TAG, "Error checking WiFi: ${e.message}")
            return true // Return true to attempt connection anyway
        }
    }
    
    /**
     * Test socket connection to Glass
     * Uses increased timeout for WiFi Direct which can be slower
     */
    private fun testConnection(ip: String): Boolean {
        return try {
            val socket = Socket()
            socket.soTimeout = SOCKET_TIMEOUT
            socket.connect(InetSocketAddress(ip, serverPort), SOCKET_TIMEOUT)
            Log.i(TAG, "✅ Socket connection successful to $ip:$serverPort")
            socket.close()
            true
        } catch (e: Exception) {
            Log.d(TAG, "❌ Connection failed to $ip: ${e.message}")
            false
        }
    }
    
    /**
     * Step 3: Get list of media files from Glass
     */
    suspend fun getMediaList(): List<MediaFileInfo> = withContext(Dispatchers.IO) {
        val ip = serverIpAddress ?: run {
            listener?.onError("Not connected to Glass")
            return@withContext emptyList()
        }
        
        Log.i(TAG, "📋 Getting media list from Glass...")
        
        var socket: Socket? = null
        var dis: DataInputStream? = null
        var dos: DataOutputStream? = null
        
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, serverPort), 5000)
            
            dos = DataOutputStream(socket.getOutputStream())
            dis = DataInputStream(socket.getInputStream())
            
            // Send LIST_FILES command
            dos.writeUTF("LIST_FILES")
            dos.flush()
            
            // Read file count
            val fileCount = dis.readInt()
            Log.d(TAG, "📦 Found $fileCount files")
            
            val fileList = mutableListOf<MediaFileInfo>()
            
            for (i in 0 until fileCount) {
                val fileName = dis.readUTF()
                val fileType = dis.readUTF()
                val fileSize = dis.readLong()
                val timestamp = dis.readLong()
                
                fileList.add(MediaFileInfo(
                    fileName = fileName,
                    fileType = fileType,
                    fileSize = fileSize,
                    timestamp = timestamp
                ))
            }
            
            withContext(Dispatchers.Main) {
                listener?.onMediaListReceived(fileList)
            }
            
            fileList
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting media list: ${e.message}")
            withContext(Dispatchers.Main) {
                listener?.onError("Failed to get media list: ${e.message}")
            }
            emptyList()
        } finally {
            try {
                dis?.close()
                dos?.close()
                socket?.close()
            } catch (e: Exception) { }
        }
    }
    
    /**
     * Step 4: Download a media file
     */
    suspend fun downloadFile(fileInfo: MediaFileInfo): String? = withContext(Dispatchers.IO) {
        val ip = serverIpAddress ?: return@withContext null
        
        Log.i(TAG, "⬇️ Downloading: ${fileInfo.fileName}")
        
        var socket: Socket? = null
        var dis: DataInputStream? = null
        var dos: DataOutputStream? = null
        var fos: FileOutputStream? = null
        
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(ip, serverPort), 5000)
            
            dos = DataOutputStream(socket.getOutputStream())
            dis = DataInputStream(socket.getInputStream())
            
            // Send download request
            dos.writeUTF(fileInfo.fileName)
            dos.flush()
            
            // Read file size
            val fileSize = dis.readLong()
            
            // Create local file path
            val localPath = getDownloadPath(fileInfo.fileName, fileInfo.fileType)
            val outputFile = File(localPath)
            outputFile.parentFile?.mkdirs()
            
            fos = FileOutputStream(outputFile)
            
            // Download in chunks
            val buffer = ByteArray(8192)
            var totalBytesRead = 0L
            var bytesRead: Int
            var lastProgressReport = 0
            
            while (totalBytesRead < fileSize) {
                bytesRead = dis.read(buffer)
                if (bytesRead == -1) break
                
                fos.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                
                // Report progress every 5%
                val progress = ((totalBytesRead * 100) / fileSize).toInt()
                if (progress >= lastProgressReport + 5) {
                    lastProgressReport = progress
                    withContext(Dispatchers.Main) {
                        listener?.onDownloadProgress(fileInfo.fileName, progress)
                    }
                }
            }
            
            fos.flush()
            
            Log.i(TAG, "✅ Downloaded: ${fileInfo.fileName} -> $localPath")
            
            withContext(Dispatchers.Main) {
                listener?.onDownloadComplete(fileInfo.fileName, localPath)
            }
            
            localPath
            
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            withContext(Dispatchers.Main) {
                listener?.onDownloadError(fileInfo.fileName, e.message ?: "Unknown error")
            }
            null
        } finally {
            try {
                fos?.close()
                dis?.close()
                dos?.close()
                socket?.close()
            } catch (e: Exception) { }
        }
    }
    
    /**
     * Download all media files
     */
    fun downloadAllFiles(files: List<MediaFileInfo>) {
        downloadJob?.cancel()
        
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            for (file in files) {
                if (!isActive) break
                downloadFile(file)
                delay(100) // Small delay between downloads
            }
        }
    }
    
    /**
     * Get local download path
     */
    private fun getDownloadPath(fileName: String, fileType: String): String {
        val baseDir = Environment.getExternalStoragePublicDirectory(
            if (fileType == "video") Environment.DIRECTORY_MOVIES
            else Environment.DIRECTORY_PICTURES
        ).path + "/GlassMedia/"
        
        return baseDir + fileName
    }
    
    /**
     * Get path to downloaded files directory
     */
    fun getPhotosDirectory(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        ).path + "/GlassMedia/")
        dir.mkdirs()
        return dir
    }
    
    fun getVideosDirectory(): File {
        val dir = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES
        ).path + "/GlassMedia/")
        dir.mkdirs()
        return dir
    }
    
    /**
     * Disconnect and cleanup
     */
    fun disconnect() {
        downloadJob?.cancel()
        downloadJob = null
        serverIpAddress = null
        isConnected = false
        listener?.onDisconnected()
    }
    
    /**
     * Check if connected to Glass
     */
    fun isConnectedToGlass(): Boolean = isConnected && serverIpAddress != null
}
