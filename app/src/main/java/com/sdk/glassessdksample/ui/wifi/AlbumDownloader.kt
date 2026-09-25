package com.sdk.glassessdksample.ui.wifi

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

/** Why [AlbumDownloader.downloadFile] returned null. */
enum class DownloadFailure {
    /** Couldn't reach the glasses, or the transfer broke off. */
    NETWORK,
    /** The glasses answered with an error for this file. */
    SERVER,
    /** The phone couldn't save the file (out of space, or the folder refused it). */
    STORAGE,
}

data class MediaConfig(
    val files: List<MediaItem>
)

class AlbumDownloader(private val ctx: Context) {
    
    companion object {
        private const val TAG = "AlbumDownloader"
        /** Leave this much room on the phone after a download. */
        private const val MIN_FREE_BYTES = 20L * 1024 * 1024
        
        // ✅ PRIORITY IPs first (most common Glass addresses), then fallbacks
        // Ordered by likelihood for faster discovery
        val POSSIBLE_IPS = listOf(
            // Priority: Most common Glass IPs (tested first)
            "192.168.6.1",    // ✅ Confirmed Glass IP (from logs)
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
    
    // 🌐 The network bound to the Glass (SoftAP / WiFi Direct). When set, ALL HTTP
    // requests are forced through this interface so traffic actually reaches the Glass
    // (192.168.6.1) instead of leaking out the phone's home WiFi (which has internet
    // and is therefore Android's preferred default route).
    @Volatile private var boundNetwork: android.net.Network? = null

    // ⚡ Fast scan client - short timeout for IP discovery
    @Volatile private var scanClient = buildScanClient()

    // ⚡ Download client - longer timeout for actual file transfers
    @Volatile private var okClient = buildDownloadClient()

    private fun buildScanClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)  // ⚡ 1.5s per IP (was 10s)
            .readTimeout(3, TimeUnit.SECONDS)
        boundNetwork?.let { b.socketFactory(it.socketFactory) }
        return b.build()
    }

    private fun buildDownloadClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)   // ⚡ 5s (was 10s)
            .readTimeout(30, TimeUnit.SECONDS)     // ⚡ 30s (was 60s)
            .writeTimeout(15, TimeUnit.SECONDS)    // ⚡ 15s (was 30s)
        boundNetwork?.let { b.socketFactory(it.socketFactory) }
        return b.build()
    }

    /**
     * 🌐 Route all HTTP through the given network (the Glass hotspot/WiFi Direct link).
     * Pass null to go back to the default network. Rebuilds the OkHttp clients so the
     * new socket factory takes effect immediately.
     */
    fun bindToNetwork(network: android.net.Network?) {
        if (boundNetwork == network) return
        boundNetwork = network
        scanClient = buildScanClient()
        okClient = buildDownloadClient()
        Log.i(TAG, if (network != null) "🌐 HTTP now bound to Glass network: $network" else "🌐 HTTP unbound (default network)")
    }

    /** The network HTTP is currently bound to (null = default route). */
    fun currentBoundNetwork(): android.net.Network? = boundNetwork

    /**
     * Quick TCP reachability test to [ip]:[port] over the SAME network HTTP uses
     * (the bound Glass network, or the default route). This is the source of truth for
     * "can we actually talk to the Glass right now".
     */
    suspend fun isReachable(ip: String, port: Int = 80, timeoutMs: Int = 1500): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val sock = boundNetwork?.socketFactory?.createSocket() ?: java.net.Socket()
                sock.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
                sock.close()
                true
            } catch (_: Exception) {
                false
            }
        }
    
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
     * ⚡ Discover glasses IP - scans ALL IPs in PARALLEL instead of sequential
     * Much faster: tests all IPs simultaneously, returns on first success
     */
    suspend fun discoverGlassesIP(): String? = withContext(Dispatchers.IO) {
        Log.i(TAG, "🔍 Discovering glasses IP (PARALLEL scan)...")

        // Priority 1: Try gateway from current WiFi (fastest path)
        val gatewayIP = getGatewayIP()
        if (gatewayIP != null) {
            Log.i(TAG, "🔍 Testing gateway IP first: $gatewayIP")
            if (testGlassesIPFast(gatewayIP)) {
                Log.i(TAG, "⚡ Found via gateway: $gatewayIP")
                return@withContext gatewayIP
            }
        }

        // Priority 2: Scan ALL possible IPs in parallel at once
        val allIps = if (gatewayIP != null) POSSIBLE_IPS.filter { it != gatewayIP } else POSSIBLE_IPS
        val results = allIps.map { ip ->
            async { if (testGlassesIPFast(ip)) ip else null }
        }.awaitAll()

        val found = results.firstOrNull { it != null }
        if (found != null) {
            Log.i(TAG, "⚡ Parallel scan found glasses at: $found")
        } else {
            Log.w(TAG, "❌ Could not find glasses on any known IP")
        }
        found
    }
    
    /**
     * ⚡ Fast IP test using short-timeout scanClient
     */
    private fun testGlassesIPFast(ip: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("http://$ip/files/media.config")
                .build()
            val response = scanClient.newCall(request).execute()
            val ok = response.isSuccessful
            response.close()
            if (ok) Log.i(TAG, "✅ Found glasses at: $ip")
            ok
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Test if glasses server is available at given IP (legacy, uses download client)
     */
    private fun testGlassesIP(ip: String): Boolean = testGlassesIPFast(ip)
    
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
                        val fileType = parts[1].trim().toIntOrNull() ?: typeFromName(fileName)
                        items.add(MediaItem(fileName, fileType))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse line: $line")
                    }
                } else {
                    // Plain filename per line — infer type by extension
                    val fileName = trimmed
                    val fileType = typeFromName(fileName)
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
    
    /** Infer the media type (1 = image, 2 = video, 3 = audio) from the extension. */
    private fun typeFromName(fileName: String): Int =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4", "mov", "3gp", "mkv", "webm" -> 2
            "opus", "wav", "m4a", "aac", "mp3", "amr", "ogg", "pcm" -> 3
            else -> 1
        }

    /**
     * Download a single file from glasses.
     *
     * Cancelling the calling coroutine aborts the transfer straight away (the HTTP
     * call is cancelled, which unblocks the read).
     *
     * The file is written under a hidden temporary name and only renamed when
     * complete, so an interrupted download never leaves a truncated file that looks
     * already saved. The temporary name keeps the real extension: Android's shared
     * folders refuse files whose type doesn't belong there (e.g. "x.jpg.part" in
     * Pictures fails with EPERM).
     *
     * On failure returns null and, if given, reports why through [onFailure].
     */
    suspend fun downloadFile(
        baseIp: String,
        fileName: String,
        outputDir: File,
        onFailure: ((DownloadFailure) -> Unit)? = null,
        progressCallback: ((Int) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val url = "http://$baseIp/files/$fileName"
        Log.i(TAG, "📥 Downloading: $url")

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val outputFile = File(outputDir, fileName)
        val partFile = File(outputDir, tempNameFor(fileName))
        var failure: DownloadFailure? = DownloadFailure.NETWORK

        val call = okClient.newCall(Request.Builder().url(url).build())
        // execute() blocks on the socket and ignores coroutine cancellation, so a
        // watcher cancels the call itself as soon as this coroutine is cancelled.
        val cancelWatcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Download failed: ${response.code}")
                    failure = DownloadFailure.SERVER
                    return@withContext null
                }
                val body = response.body ?: return@withContext null

                // Stream download with progress
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                if (totalBytes > 0 && outputDir.usableSpace < totalBytes + MIN_FREE_BYTES) {
                    Log.e(TAG, "Not enough space for $fileName ($totalBytes bytes, ${outputDir.usableSpace} free)")
                    failure = DownloadFailure.STORAGE
                    return@withContext null
                }

                val out = try {
                    partFile.outputStream()
                } catch (e: IOException) {
                    Log.e(TAG, "Cannot create ${partFile.name}: ${e.message}")
                    failure = DownloadFailure.STORAGE
                    return@withContext null
                }
                out.use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            ensureActive()
                            try {
                                output.write(buffer, 0, bytesRead)
                            } catch (e: IOException) {
                                failure = DownloadFailure.STORAGE
                                throw e
                            }
                            downloadedBytes += bytesRead

                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                                progressCallback?.invoke(progress)
                            }
                        }
                    }
                }

                if (totalBytes > 0 && downloadedBytes < totalBytes) {
                    Log.w(TAG, "Download incomplete: $fileName ($downloadedBytes/$totalBytes bytes)")
                    return@withContext null
                }
                if (!partFile.renameTo(outputFile)) {
                    Log.e(TAG, "Could not move $partFile to $outputFile")
                    failure = DownloadFailure.STORAGE
                    return@withContext null
                }
                failure = null
                Log.i(TAG, "✅ Downloaded: $fileName (${downloadedBytes / 1024} KB)")
                outputFile
            }
        } catch (e: IOException) {
            // Also how a cancelled call surfaces; withContext then rethrows the cancellation.
            Log.e(TAG, "Download error: ${e.message}")
            null
        } finally {
            cancelWatcher.cancel()
            if (partFile.exists()) partFile.delete()
            failure?.let { f -> if (isActive) onFailure?.invoke(f) }
        }
    }

    /** "IMG_1.jpg" → ".IMG_1.part.jpg": hidden, and still a .jpg to the file system. */
    private fun tempNameFor(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "")
        val base = fileName.substringBeforeLast('.')
        return if (ext.isEmpty()) ".$fileName.part" else ".$base.part.$ext"
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
