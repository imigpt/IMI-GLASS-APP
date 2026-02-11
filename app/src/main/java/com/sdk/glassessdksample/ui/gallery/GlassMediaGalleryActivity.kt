package com.sdk.glassessdksample.ui.gallery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sdk.glassessdksample.ui.wifi.GlassMediaTransfer
import com.sdk.glassessdksample.ui.wifi.WifiP2pHelper
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.net.InetSocketAddress
import java.net.Socket


/**
 * Glass Media Gallery - View Photos/Videos from Glass
 * 
 * Features:
 * - Trigger Glass Hotspot
 * - Connect to Glass WiFi
 * - Download photos/videos
 * - Display in grid view
 * - Play videos / View photos
 */
class GlassMediaGalleryActivity : AppCompatActivity(), 
    GlassMediaTransfer.TransferListener,
    WifiP2pHelper.WifiP2pCallback {
    
    companion object {
        private const val TAG = "GlassMediaGallery"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val NEARBY_PERMISSION_REQUEST = 1002
        
        fun launch(context: Context) {
            context.startActivity(Intent(context, GlassMediaGalleryActivity::class.java))
        }
    }
    
    private lateinit var glassMediaTransfer: GlassMediaTransfer
    private lateinit var wifiP2pHelper: WifiP2pHelper
    // HTTP-based album downloader for Glass HTTP server (port 80)
    private val albumDownloader by lazy { com.sdk.glassessdksample.ui.wifi.AlbumDownloader(this) }
    private val mediaFiles = mutableListOf<GlassMediaTransfer.MediaFileInfo>()
    private var adapter: MediaAdapter? = null
    
    private var recyclerView: RecyclerView? = null
    private var progressBar: ProgressBar? = null
    private var tvStatus: TextView? = null
    private var tvProgress: TextView? = null
    private var btnConnect: Button? = null
    private var btnRefresh: Button? = null
    private var btnDiscover: Button? = null
    private var layoutProgress: View? = null
    
    // Track if we went to WiFi settings
    private var wentToWifiSettings = false
    private var isConnectedToGlass = false
    private var discoveredDevices = mutableListOf<WifiP2pDevice>()
    
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // BLE notification listener for Glass IP (notification type 8)
    private val bleDeviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, rsp: GlassesDeviceNotifyRsp?) {
            try {
                if (rsp == null) return
                
                val loadData = rsp.loadData ?: return
                if (loadData.size < 11) return
                
                // loadData[6] contains the notification type
                val notifyType = loadData[6].toInt() and 0xFF
                
                Log.d(TAG, "📡 BLE Notification type: $notifyType")
                
                when (notifyType) {
                    // Type 8: Glass IP Address after P2P connect
                    8 -> {
                        val ip = "${loadData[7].toInt() and 0xFF}.${loadData[8].toInt() and 0xFF}.${loadData[9].toInt() and 0xFF}.${loadData[10].toInt() and 0xFF}"
                        Log.d(TAG, "🌐 BLE returned Glass IP: $ip")

                        runOnUiThread {
                            updateStatus("📡 Glass IP from BLE: $ip")
                            Toast.makeText(this@GlassMediaGalleryActivity, "Glass IP: $ip", Toast.LENGTH_SHORT).show()

                            // Stop any background IP scanning since BLE gave the IP
                            try {
                                // Use reflection to avoid potential unresolved-call issues during compile
                                val m = wifiP2pHelper::class.java.getMethod("stopDiscovery")
                                m.invoke(wifiP2pHelper)
                            } catch (e: Exception) {
                                Log.w(TAG, "Error stopping scanner: ${e.message}")
                            }

                            // Probe common ports and proceed only if server responds
                            testPortsAndProceed(ip)
                        }
                    }
                    
                    // Type 9: Error codes
                    9 -> {
                        val errorCode = loadData[7].toInt() and 0xFF
                        Log.w(TAG, "⚠️ BLE Error notification: $errorCode")
                        if (errorCode == 255) {
                            // Need to reset P2P and try again
                            runOnUiThread {
                                updateStatus("⚠️ P2P Error - Retrying...")
                                glassMediaTransfer.triggerGlassHotspot() // Reset and retry
                            }
                        }
                    }
                    
                    // Type 1: Media count update
                    1 -> {
                        if (loadData.size >= 13) {
                            val imageCount = ((loadData[7].toInt() and 0xFF) or ((loadData[8].toInt() and 0xFF) shl 8))
                            val videoCount = ((loadData[9].toInt() and 0xFF) or ((loadData[10].toInt() and 0xFF) shl 8))
                            val recordCount = ((loadData[11].toInt() and 0xFF) or ((loadData[12].toInt() and 0xFF) shl 8))
                            val total = imageCount + videoCount + recordCount
                            Log.d(TAG, "📷 Media count: $imageCount images, $videoCount videos, $recordCount records")
                            runOnUiThread {
                                updateStatus("📷 Glass has $total files")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing BLE notification: ${e.message}")
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Simple layout - we'll create it programmatically
        createLayout()
        
        // Check and request location permission for WiFi SSID
        checkLocationPermission()
        
        // Initialize WiFi P2P helper
        wifiP2pHelper = WifiP2pHelper(this)
        wifiP2pHelper.setCallback(this)
        wifiP2pHelper.initialize()
        wifiP2pHelper.registerReceiver()
        
        // Initialize transfer manager
        glassMediaTransfer = GlassMediaTransfer(this)
        glassMediaTransfer.setListener(this)
        
        // Register BLE notification listener for Glass IP (type 8)
        // Using listener ID 2 like the original app
        LargeDataHandler.getInstance().addOutDeviceListener(2, bleDeviceNotifyListener)
        Log.d(TAG, "📡 BLE notification listener registered for Glass IP")
        
        // Setup adapter
        adapter = MediaAdapter(this, mediaFiles) { fileInfo ->
            openMedia(fileInfo)
        }
        recyclerView?.adapter = adapter
        
        // Load any existing downloaded files
        loadLocalFiles()
        
        updateStatus("Press 'Connect to Glass' to start")
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume - wentToWifiSettings: $wentToWifiSettings, isConnectedToGlass: $isConnectedToGlass")
        
        // Re-register P2P receiver
        wifiP2pHelper.registerReceiver()
        
        // If we returned from WiFi settings, auto-check connection
        if (wentToWifiSettings && !isConnectedToGlass) {
            wentToWifiSettings = false
            updateStatus("🔍 Checking Glass WiFi connection...")
            
            mainScope.launch {
                // Wait for WiFi Direct connection to be fully established
                delay(2000)
                tryCurrentConnection()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Unregister to avoid leaks but will re-register in onResume
        // Don't cleanup - just unregister receiver
        try {
            wifiP2pHelper.unregisterReceiver()
        } catch (e: IllegalArgumentException) {
            // On some Oplus/Realme devices this throws when already unregistered
            Log.d(TAG, "Receiver already unregistered (Oplus safety catch)")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering: ${e.message}")
        }
    }
    
    /**
     * Auto-check and connect to Glass when returning from WiFi settings
     * Uses WifiP2pHelper for proper P2P IP detection
     */
    private fun checkAndConnectToGlass() {
        mainScope.launch {
            updateStatus("🔍 Searching for Glass on WiFi...")
            
            // Log phone's IP for debugging
            logPhoneNetworkInfo()
            
            // First try P2P helper to detect Glass IP
            val glassIp = withContext(Dispatchers.IO) {
                wifiP2pHelper.tryGetGlassIpFromCurrentConnection()
            }
            
            if (glassIp != null) {
                onGlassConnected(glassIp)
                return@launch
            }
            
            // Fallback: Try GlassMediaTransfer's scan method
            val connected = glassMediaTransfer.connectToGlass()
            if (connected) {
                isConnectedToGlass = true
                updateStatus("✅ Connected! Getting media list...")
                btnConnect?.text = "✅ Connected"
                btnConnect?.isEnabled = false
                glassMediaTransfer.getMediaList()
                return@launch
            }
            
            // Connection failed - show detailed error
            val currentSSID = getCurrentWifiSSID()
            Log.d(TAG, "Current SSID: $currentSSID")
            
            if (currentSSID != null && (currentSSID.contains("M01") || currentSSID.contains("Glass") || currentSSID.contains("DIRECT"))) {
                // Connected to Glass WiFi but can't reach socket server
                updateStatus("⚠️ WiFi connected but Glass server not responding")
                showRetryDialog()
            } else {
                updateStatus("❌ Cannot connect - please check WiFi")
                showConnectionOptionsDialog()
            }
        }
    }
    
    /**
     * Log phone's network info for debugging
     */
    private fun logPhoneNetworkInfo() {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isUp && !iface.isLoopback) {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            Log.i(TAG, "📱 Network: ${iface.name} = ${addr.hostAddress}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error logging network info: ${e.message}")
        }
    }
    
    /**
     * Show retry dialog when connection fails
     */
    private fun showRetryDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Connection Failed")
            .setMessage(
                "Glass WiFi se connect hai lekin server respond nahi kar raha.\n\n" +
                "Possible issues:\n" +
                "• Glass ka hotspot abhi start ho raha hai\n" +
                "• Glass restart karo aur phir try karo\n" +
                "• WiFi Direct properly connect nahi hua\n\n" +
                "Retry karna chahte ho?"
            )
            .setPositiveButton("🔄 Retry") { _, _ ->
                mainScope.launch {
                    updateStatus("🔄 Retrying connection...")
                    delay(2000)
                    checkAndConnectToGlass()
                }
            }
            .setNegativeButton("WiFi Settings") { _, _ ->
                wentToWifiSettings = true
                openWifiDirectSettings()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }
    
    /**
     * Show dialog when permission is required
     */
    private fun showPermissionRequiredDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("📍 Permission Required")
            .setMessage(
                "WiFi SSID detect karne ke liye Location permission chahiye.\n\n" +
                "Please permission allow karo."
            )
            .setPositiveButton("Grant Permission") { _, _ ->
                checkLocationPermission()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Get current WiFi SSID
     */
    private fun getCurrentWifiSSID(): String? {
        // Check location permission first
        if (!hasLocationPermission()) {
            Log.w(TAG, "Location permission not granted - cannot get SSID")
            return null
        }
        
        // Check if location is enabled
        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services disabled - cannot get SSID")
            return null
        }
        
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ use ConnectivityManager
                getSSIDFromConnectivityManager()
            } else {
                // Legacy method
                getSSIDFromWifiManager()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting SSID: ${e.message}")
            null
        }
    }
    
    /**
     * Get SSID using ConnectivityManager (Android 10+)
     */
    private fun getSSIDFromConnectivityManager(): String? {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null
        
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ssid = wifiInfo?.ssid?.replace("\"", "")
            Log.d(TAG, "SSID from ConnectivityManager: $ssid")
            return if (ssid == "<unknown ssid>" || ssid == null) null else ssid
        }
        return null
    }
    
    /**
     * Get SSID using WifiManager (legacy)
     */
    private fun getSSIDFromWifiManager(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ssid = wifiInfo?.ssid?.replace("\"", "")
        Log.d(TAG, "SSID from WifiManager: $ssid")
        return if (ssid == "<unknown ssid>" || ssid == null) null else ssid
    }
    
    /**
     * Check if location permission is granted
     */
    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, 
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if location services are enabled
     */
    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
    
    /**
     * Check and request location permission
     */
    private fun checkLocationPermission() {
        val permissionsNeeded = mutableListOf<String>()
        
        // Location permissions
        if (!hasLocationPermission()) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        
        // Android 12+ needs NEARBY_WIFI_DEVICES permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        
        if (permissionsNeeded.isNotEmpty()) {
            Log.d(TAG, "Requesting permissions: $permissionsNeeded")
            ActivityCompat.requestPermissions(
                this,
                permissionsNeeded.toTypedArray(),
                LOCATION_PERMISSION_REQUEST
            )
        } else {
            Log.d(TAG, "All permissions already granted")
            // Check if location is enabled
            if (!isLocationEnabled()) {
                showLocationEnableDialog()
            }
        }
    }
    
    /**
     * Check if NEARBY_WIFI_DEVICES permission is granted (Android 12+)
     */
    private fun hasNearbyPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == 
                PackageManager.PERMISSION_GRANTED
        } else {
            true // Not needed for older Android versions
        }
    }
    
    /**
     * Show dialog to enable location services
     */
    private fun showLocationEnableDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("📍 Location Required")
            .setMessage(
                "WiFi SSID padhne ke liye Location ON hona chahiye.\n\n" +
                "Yeh Android ka requirement hai - Location ON karo to Glass WiFi detect ho payega."
            )
            .setPositiveButton("📍 Enable Location") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST) {
            val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                Log.d(TAG, "All permissions granted: ${permissions.toList()}")
                Toast.makeText(this, "✅ All permissions granted", Toast.LENGTH_SHORT).show()
                
                // Check if location is enabled
                if (!isLocationEnabled()) {
                    showLocationEnableDialog()
                }
            } else {
                // Check which permissions were denied
                val deniedPermissions = permissions.filterIndexed { index, _ -> 
                    grantResults.getOrNull(index) != PackageManager.PERMISSION_GRANTED 
                }
                Log.w(TAG, "Permissions denied: $deniedPermissions")
                
                if (deniedPermissions.any { it.contains("NEARBY") }) {
                    Toast.makeText(this, "⚠️ Nearby Devices permission needed for WiFi P2P", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "⚠️ Location permission needed for WiFi detection", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun createLayout() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        // Title
        val tvTitle = TextView(this).apply {
            text = "📷 Glass Media Gallery"
            textSize = 24f
            setPadding(0, 0, 0, 16)
        }
        rootLayout.addView(tvTitle)
        
        // Status
        tvStatus = TextView(this).apply {
            text = "Status: Not connected"
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        rootLayout.addView(tvStatus)
        
        // Progress layout
        layoutProgress = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            max = 100
        }
        (layoutProgress as LinearLayout).addView(progressBar)
        
        tvProgress = TextView(this).apply {
            text = "0%"
            setPadding(8, 0, 0, 0)
        }
        (layoutProgress as LinearLayout).addView(tvProgress)
        
        rootLayout.addView(layoutProgress)
        
        // Buttons layout with spacing
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 12, 0, 20)
        }
        
        btnConnect = Button(this).apply {
            text = "🔗 Connect"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setBackgroundColor(0xFF2A2A2A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { startConnection() }
        }
        buttonLayout.addView(btnConnect)
        
        btnDiscover = Button(this).apply {
            text = "🔍 Discover"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8
            }
            setBackgroundColor(0xFF2A2A2A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { 
                updateStatus("🔍 Searching for Glass devices...")
                wifiP2pHelper.startDiscovery()
            }
        }
        buttonLayout.addView(btnDiscover)
        
        btnRefresh = Button(this).apply {
            text = "🔄 Refresh"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setBackgroundColor(0xFF2A2A2A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { refreshMediaList() }
        }
        buttonLayout.addView(btnRefresh)
        
        rootLayout.addView(buttonLayout)
        
        // Download All button with modern styling
        val btnDownloadAll = Button(this).apply {
            text = "⬇️ Download All Media"
            setBackgroundColor(0xFF4CAF50.toInt()) // Green accent
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(24, 16, 24, 16)
            setOnClickListener { downloadAllMedia() }
        }
        rootLayout.addView(btnDownloadAll)
        
        // RecyclerView for media grid
        recyclerView = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@GlassMediaGalleryActivity, 3)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(recyclerView)
        
        setContentView(rootLayout)
    }
    
    /**
     * Start connection process
     */
    private fun startConnection() {
        updateStatus("📡 Sending BLE command to Glass...")
        btnConnect?.isEnabled = false
        
        // Step 1: Trigger Glass hotspot/P2P mode
        glassMediaTransfer.triggerGlassHotspot()
        
        // Show options dialog
        mainScope.launch {
            delay(2000) // Wait for Glass P2P to start
            showConnectionOptionsDialog()
        }
    }
    
    /**
     * Show connection options - Auto P2P Discovery or Manual WiFi
     */
    private fun showConnectionOptionsDialog() {
        btnConnect?.isEnabled = true
        
        android.app.AlertDialog.Builder(this)
            .setTitle("📶 Connect to Glass")
            .setMessage(
                "Glass ka P2P mode triggered ho gaya hai!\n\n" +
                "Connection method choose karo:"
            )
            .setPositiveButton("🔍 Auto Discovery") { _, _ ->
                updateStatus("🔍 Searching for Glass devices...")
                wifiP2pHelper.startDiscovery()
            }
            .setNeutralButton("📶 Manual WiFi") { _, _ ->
                wentToWifiSettings = true
                showWifiConnectionDialog()
            }
            .setNegativeButton("Try Current") { _, _ ->
                // Try to detect Glass IP from current P2P connection
                tryCurrentConnection()
            }
            .show()
    }
    
    /**
     * Try to get Glass IP from current WiFi/P2P connection
     */
    private fun tryCurrentConnection() {
        updateStatus("🔍 Checking current connection...")
        
        mainScope.launch {
            // First try WifiP2pHelper method
            val glassIp = withContext(Dispatchers.IO) {
                wifiP2pHelper.tryGetGlassIpFromCurrentConnection()
            }
            
            if (glassIp != null) {
                onGlassConnected(glassIp)
            } else {
                // Fallback to GlassMediaTransfer scan
                updateStatus("🔍 Scanning for Glass server...")
                val connected = glassMediaTransfer.connectToGlass()
                if (!connected) {
                    showConnectionOptionsDialog()
                }
            }
        }
    }
    
    /**
     * Show dialog to guide user to connect WiFi
     */
    private fun showWifiConnectionDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("📶 Connect to Glass WiFi")
            .setMessage(
                "Glass ka WiFi hotspot ON ho gaya hai!\n\n" +
                "Ab yeh karo:\n" +
                "1️⃣ Phone ki WiFi Settings kholo\n" +
                "2️⃣ WiFi Direct / P2P section mein jao\n" +
                "3️⃣ \"M01_F736...\" network dhundo\n" +
                "4️⃣ Us network se connect karo\n" +
                "5️⃣ Wapas yahan aao - AUTO CONNECT hoga!\n\n" +
                "Note: Glass WiFi SSID usually starts with M01_"
            )
            .setPositiveButton("📶 WiFi Direct Settings") { _, _ ->
                wentToWifiSettings = true
                openWifiDirectSettings()
            }
            .setNeutralButton("🔄 Retry Connection") { _, _ ->
                checkAndConnectToGlass()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    /**
     * Open WiFi Direct settings
     * Tries multiple intents since different Android versions use different paths
     */
    private fun openWifiDirectSettings() {
        val intents = listOf(
            // Android WiFi Direct P2P settings
            android.content.Intent("android.settings.WIFI_P2P_SETTINGS"),
            android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS),
            android.content.Intent().apply {
                action = android.provider.Settings.ACTION_WIRELESS_SETTINGS
            }
        )
        
        for (intent in intents) {
            try {
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Intent failed: ${e.message}")
            }
        }
        
        // Fallback to regular WiFi settings
        try {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open WiFi settings", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Refresh media list from Glass
     */
    private fun refreshMediaList() {
        if (!glassMediaTransfer.isConnectedToGlass()) {
            Toast.makeText(this, "Not connected to Glass", Toast.LENGTH_SHORT).show()
            return
        }
        
        mainScope.launch {
            updateStatus("Refreshing media list...")
            glassMediaTransfer.getMediaList()
        }
    }
    
    /**
     * Fetch file list from Glass and start download
     * Called when BLE returns Glass IP (notification type 8)
     */
    private fun fetchAndDownloadFromGlass() {
        updateStatus("📋 Fetching file list from Glass...")
        layoutProgress?.visibility = View.VISIBLE
        
        mainScope.launch {
            try {
                // Get file list from Glass via socket
                val files = glassMediaTransfer.getMediaList()
                
                if (files.isNotEmpty()) {
                    mediaFiles.clear()
                    mediaFiles.addAll(files)
                    adapter?.notifyDataSetChanged()
                    
                    updateStatus("📥 Found ${files.size} files. Starting download...")
                    
                    // Start downloading all files
                    val toDownload = files.filter { !it.isDownloaded }
                    if (toDownload.isNotEmpty()) {
                        glassMediaTransfer.downloadAllFiles(toDownload)
                    } else {
                        updateStatus("✅ All ${files.size} files already downloaded")
                        layoutProgress?.visibility = View.GONE
                    }
                } else {
                    // No files from socket - Glass may need different protocol
                    updateStatus("📷 Connected! Press 'Download All' to fetch files")
                    layoutProgress?.visibility = View.GONE
                    Toast.makeText(this@GlassMediaGalleryActivity, 
                        "Connected to Glass at ${glassMediaTransfer.isConnectedToGlass()}", 
                        Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching files: ${e.message}")
                updateStatus("⚠️ Error: ${e.message}")
                layoutProgress?.visibility = View.GONE
            }
        }
    }
    
    /**
     * Download all media from Glass
     */
    private fun downloadAllMedia() {
        if (mediaFiles.isEmpty()) {
            Toast.makeText(this, "No media to download", Toast.LENGTH_SHORT).show()
            return
        }
        
        val toDownload = mediaFiles.filter { !it.isDownloaded }
        if (toDownload.isEmpty()) {
            Toast.makeText(this, "All files already downloaded", Toast.LENGTH_SHORT).show()
            return
        }
        
        layoutProgress?.visibility = View.VISIBLE
        updateStatus("Downloading ${toDownload.size} files...")
        
        glassMediaTransfer.downloadAllFiles(toDownload)
    }
    
    /**
     * Load already downloaded files
     */
    private fun loadLocalFiles() {
        val photosDir = glassMediaTransfer.getPhotosDirectory()
        val videosDir = glassMediaTransfer.getVideosDirectory()
        
        val existingFiles = mutableListOf<GlassMediaTransfer.MediaFileInfo>()
        
        // Load photos
        photosDir.listFiles()?.forEach { file ->
            existingFiles.add(GlassMediaTransfer.MediaFileInfo(
                fileName = file.name,
                fileType = "photo",
                fileSize = file.length(),
                timestamp = file.lastModified(),
                localPath = file.absolutePath,
                isDownloaded = true
            ))
        }
        
        // Load videos
        videosDir.listFiles()?.forEach { file ->
            existingFiles.add(GlassMediaTransfer.MediaFileInfo(
                fileName = file.name,
                fileType = "video",
                fileSize = file.length(),
                timestamp = file.lastModified(),
                localPath = file.absolutePath,
                isDownloaded = true
            ))
        }
        
        if (existingFiles.isNotEmpty()) {
            mediaFiles.clear()
            mediaFiles.addAll(existingFiles.sortedByDescending { it.timestamp })
            adapter?.notifyDataSetChanged()
            updateStatus("${existingFiles.size} local files found")
        }
    }
    
    /**
     * Open media file
     */
    private fun openMedia(fileInfo: GlassMediaTransfer.MediaFileInfo) {
        val localPath = fileInfo.localPath
        if (localPath == null) {
            // Need to download first
            mainScope.launch {
                updateStatus("Downloading ${fileInfo.fileName}...")
                layoutProgress?.visibility = View.VISIBLE
                
                val path = glassMediaTransfer.downloadFile(fileInfo)
                if (path != null) {
                    fileInfo.localPath = path
                    fileInfo.isDownloaded = true
                    adapter?.notifyDataSetChanged()
                    openLocalFile(path, fileInfo.fileType)
                }
                
                layoutProgress?.visibility = View.GONE
            }
            return
        }
        
        openLocalFile(localPath, fileInfo.fileType)
    }
    
    private fun openLocalFile(path: String, fileType: String) {
        try {
            val file = File(path)
            
            if (!file.exists()) {
                Log.e(TAG, "File does not exist: $path")
                Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Determine file type from extension if fileType is not clear
            val extension = file.extension.lowercase()
            val isImage = fileType.equals("image", ignoreCase = true) || 
                          extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
            val isVideo = fileType.equals("video", ignoreCase = true) || 
                          extension in listOf("mp4", "mkv", "avi", "mov", "3gp", "webm")
            
            Log.d(TAG, "Opening file: $path (type: $fileType, ext: $extension, isImage: $isImage, isVideo: $isVideo)")
            
            when {
                isImage -> {
                    // Get all image paths for swipe navigation
                    val allImagePaths = mediaFiles
                        .filter { f -> 
                            val fExt = f.fileName.substringAfterLast('.').lowercase()
                            f.fileType.equals("image", ignoreCase = true) || 
                            fExt in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
                        }
                        .mapNotNull { it.localPath }
                    val currentIndex = allImagePaths.indexOf(path).coerceAtLeast(0)
                    
                    // Open image in our in-app full-screen viewer with swipe navigation
                    ImageViewerActivity.open(this, path, file.name, ArrayList(allImagePaths), currentIndex)
                }
                isVideo -> {
                    // Open video in our in-app video player
                    VideoPlayerActivity.open(this, path, file.name)
                }
                else -> {
                    Toast.makeText(this, "Unsupported file type: $extension", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: ${e.message}", e)
            Toast.makeText(this, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun updateStatus(status: String) {
        tvStatus?.text = "Status: $status"
        Log.d(TAG, status)
    }

    // Probes common ports on the given Glass IP; if open port found, set server ip/port and proceed
    private fun testPortsAndProceed(glassIp: String) {
        Thread {
            // Put 80 first as logs showed it working for some devices
            val commonPorts = listOf(80, 8888, 8899, 8080, 9000, 5000)
            var openPort: Int? = null

            for (port in commonPorts) {
                try {
                    Socket().use {
                        it.connect(InetSocketAddress(glassIp, port), 800)
                    }
                    Log.e("PORT_TEST", "✅ OPEN PORT FOUND: $port")
                    openPort = port
                    break
                } catch (e: Exception) {
                    Log.d("PORT_TEST", "❌ closed $port")
                }
            }

            runOnUiThread {
                if (openPort == 80) {
                    // HTTP path
                    updateStatus("✅ Connected via HTTP (Port 80)")
                    isConnectedToGlass = true
                    try {
                        val m = wifiP2pHelper::class.java.getMethod("stopDiscovery")
                        m.invoke(wifiP2pHelper)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error stopping scanner: ${e.message}")
                    }

                    mainScope.launch {
                        downloadViaHttp(glassIp)
                    }

                } else if (openPort != null) {
                    // Raw socket path
                    glassMediaTransfer.setServerIp(glassIp)
                    glassMediaTransfer.setServerPort(openPort)
                    isConnectedToGlass = true
                    updateStatus("✅ Glass socket ready (port $openPort)")

                    try {
                        val m = wifiP2pHelper::class.java.getMethod("stopDiscovery")
                        m.invoke(wifiP2pHelper)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error stopping scanner: ${e.message}")
                    }

                    mainScope.launch {
                        delay(500)
                        fetchAndDownloadFromGlass()
                    }

                } else {
                    isConnectedToGlass = false
                    updateStatus("❌ Glass server failed to respond")
                }
            }
        }.start()
    }

    // HTTP download flow using AlbumDownloader (for port 80)
    private suspend fun downloadViaHttp(ip: String) {
        layoutProgress?.visibility = View.VISIBLE
        updateStatus("📋 Fetching HTTP file list...")

        // 1. List Fetch karo
        val files = try {
            withContext(Dispatchers.IO) { albumDownloader.fetchConfig(ip) }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP fetchConfig failed: ${e.message}")
            layoutProgress?.visibility = View.GONE
            updateStatus("⚠️ HTTP fetch failed: ${e.message}")
            return
        }

        if (files.isNotEmpty()) {
            val totalFiles = files.size
            updateStatus("📥 Found $totalFiles files. Checking local storage...")

            // 2. Progress Bar Setup (Fixed 0% issue)
            runOnUiThread {
                progressBar?.isIndeterminate = false
                progressBar?.max = totalFiles
                progressBar?.progress = 0
                tvProgress?.text = "0/$totalFiles"
            }

            // UI List Setup
            val galleryFiles = files.map { item ->
                GlassMediaTransfer.MediaFileInfo(
                    fileName = item.fileName,
                    fileType = if (item.type == 2) "video" else "photo",
                    fileSize = 0L,
                    timestamp = System.currentTimeMillis(),
                    localPath = null,
                    isDownloaded = false
                )
            }

            mediaFiles.clear()
            mediaFiles.addAll(galleryFiles)
            adapter?.notifyDataSetChanged()

            var downloadedCount = 0
            var skippedCount = 0
            val outputDir = glassMediaTransfer.getPhotosDirectory()

            // 3. Ek-ek file check aur download karo
            files.forEachIndexed { index, item ->
                val currentFileNum = index + 1
                val localFile = File(outputDir, item.fileName)

                // --- CHECK: Kya file pehle se hai? ---
                if (localFile.exists() && localFile.length() > 0) {
                    // Haan hai -> SKIP DOWNLOAD
                    skippedCount++
                    
                    // UI update (Dikhao ki downloaded hai)
                    mediaFiles.find { it.fileName == item.fileName }?.apply {
                        localPath = localFile.absolutePath
                        isDownloaded = true
                    }
                    
                    // Progress Bar update (Silent)
                    runOnUiThread {
                        progressBar?.progress = currentFileNum
                        tvProgress?.text = "$currentFileNum/$totalFiles"
                    }
                } else {
                    // Nahi hai -> DOWNLOAD KARO
                    runOnUiThread {
                        progressBar?.progress = currentFileNum
                        tvProgress?.text = "$currentFileNum/$totalFiles"
                        updateStatus("Downloading $currentFileNum/$totalFiles: ${item.fileName}")
                    }

                    val file = withContext(Dispatchers.IO) { 
                        albumDownloader.downloadFile(ip, item.fileName, outputDir) 
                    }
                    
                    if (file != null) {
                        downloadedCount++
                        mediaFiles.find { it.fileName == item.fileName }?.apply {
                            localPath = file.absolutePath
                            isDownloaded = true
                        }
                        // Thode thode der mein UI refresh karo
                        if (downloadedCount % 5 == 0) {
                            runOnUiThread { adapter?.notifyDataSetChanged() }
                        }
                    }
                }
            }
            
            // 4. Final Result Message
            runOnUiThread {
                adapter?.notifyDataSetChanged()
                layoutProgress?.visibility = View.GONE
                
                if (downloadedCount == 0 && skippedCount > 0) {
                    // Agar sab pehle se tha
                    updateStatus("✅ All files already downloaded!")
                    Toast.makeText(this@GlassMediaGalleryActivity, "All files already exist", Toast.LENGTH_SHORT).show()
                } else {
                    // Agar kuch naya download hua
                    updateStatus("✅ Complete: $downloadedCount new, $skippedCount skipped")
                    Toast.makeText(this@GlassMediaGalleryActivity, "Downloaded $downloadedCount new files", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            updateStatus("⚠️ No files found via HTTP")
            layoutProgress?.visibility = View.GONE
        }
    }
    
    // Transfer Listener callbacks
    
    override fun onHotspotTriggered() {
        runOnUiThread {
            updateStatus("Glass Hotspot triggered. Connect to Glass WiFi...")
            Toast.makeText(this, "Please connect to Glass WiFi hotspot", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onConnected(ip: String) {
        runOnUiThread {
            updateStatus("Connected to Glass at $ip")
        }
    }
    
    override fun onDisconnected() {
        runOnUiThread {
            updateStatus("Disconnected from Glass")
        }
    }
    
    override fun onMediaListReceived(files: List<GlassMediaTransfer.MediaFileInfo>) {
        runOnUiThread {
            mediaFiles.clear()
            mediaFiles.addAll(files.sortedByDescending { it.timestamp })
            adapter?.notifyDataSetChanged()
            updateStatus("${files.size} files found on Glass")
        }
    }
    
    override fun onDownloadProgress(fileName: String, progress: Int) {
        runOnUiThread {
            progressBar?.progress = progress
            tvProgress?.text = "$progress%"
        }
    }
    
    override fun onDownloadComplete(fileName: String, localPath: String) {
        runOnUiThread {
            // Update the file info
            mediaFiles.find { it.fileName == fileName }?.apply {
                this.localPath = localPath
                this.isDownloaded = true
            }
            adapter?.notifyDataSetChanged()
            updateStatus("Downloaded: $fileName")
        }
    }
    
    override fun onDownloadError(fileName: String, error: String) {
        runOnUiThread {
            updateStatus("Error: $fileName - $error")
            Toast.makeText(this, "Download failed: $fileName", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onError(error: String) {
        runOnUiThread {
            updateStatus("Error: $error")
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            btnConnect?.isEnabled = true
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        mainScope.cancel()
        glassMediaTransfer.disconnect()
        wifiP2pHelper.cleanup()
        
        // Remove BLE notification listener
        try {
            LargeDataHandler.getInstance().removeOutDeviceListener(2)
            Log.d(TAG, "📡 BLE notification listener removed")
        } catch (e: Exception) {
            Log.w(TAG, "Error removing BLE listener: ${e.message}")
        }
    }
    
    // ===============================
    // WifiP2pHelper.WifiP2pCallback implementations
    // ===============================
    
    override fun onP2pStateChanged(enabled: Boolean) {
        runOnUiThread {
            if (enabled) {
                Log.i(TAG, "✅ WiFi P2P enabled")
            } else {
                Log.w(TAG, "⚠️ WiFi P2P disabled")
                updateStatus("⚠️ WiFi Direct is disabled")
            }
        }
    }
    
    override fun onPeersDiscovered(peers: List<WifiP2pDevice>) {
        runOnUiThread {
            discoveredDevices.clear()
            discoveredDevices.addAll(peers)
            
            if (peers.isNotEmpty()) {
                updateStatus("Found ${peers.size} devices")
                showDeviceSelectionDialog(peers)
            } else {
                updateStatus("No devices found. Retrying...")
                // Auto-retry discovery
                mainScope.launch {
                    delay(3000)
                    wifiP2pHelper.startDiscovery()
                }
            }
        }
    }
    
    override fun onGlassConnected(glassIp: String) {
        runOnUiThread {
            btnConnect?.text = "⏳ Checking server..."
            btnConnect?.isEnabled = false
            updateStatus("📡 Glass IP detected: $glassIp")

            // Probe ports then proceed
            testPortsAndProceed(glassIp)
        }
    }
    
    override fun onP2pDisconnected() {
        runOnUiThread {
            isConnectedToGlass = false
            updateStatus("P2P Disconnected from Glass")
            btnConnect?.text = "🔗 Connect to Glass"
            btnConnect?.isEnabled = true
        }
    }
    
    override fun onP2pError(message: String) {
        // --- CHANGE: If we're already connected (or downloading), ignore transient scanner errors ---
        if (isConnectedToGlass) {
            Log.w(TAG, "Ignored P2P Error because already connected: $message")
            return
        }

        runOnUiThread {
            updateStatus("P2P Error: $message")
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            btnConnect?.isEnabled = true
        }
    }
    
    /**
     * Show dialog to select which device to connect to
     */
    private fun showDeviceSelectionDialog(devices: List<WifiP2pDevice>) {
        val deviceNames = devices.map { "${it.deviceName} (${it.deviceAddress})" }.toTypedArray()
        
        android.app.AlertDialog.Builder(this)
            .setTitle("📱 Select Glass Device")
            .setItems(deviceNames) { _, which ->
                val selectedDevice = devices[which]
                updateStatus("Connecting to ${selectedDevice.deviceName}...")
                wifiP2pHelper.connectToDevice(selectedDevice)
            }
            .setNegativeButton("Manual Connect") { _, _ ->
                showWifiConnectionDialog()
            }
            .setNeutralButton("🔄 Refresh") { _, _ ->
                updateStatus("Searching for devices...")
                wifiP2pHelper.startDiscovery()
            }
            .show()
    }
    
    /**
     * Media Adapter for RecyclerView
     */
    class MediaAdapter(
        private val context: Context,
        private val items: List<GlassMediaTransfer.MediaFileInfo>,
        private val onClick: (GlassMediaTransfer.MediaFileInfo) -> Unit
    ) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {
        
        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(android.R.id.icon)
            val textView: TextView = view.findViewById(android.R.id.text1)
            val videoIcon: ImageView = view.findViewById(android.R.id.icon1)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val layout = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    300
                )
                setPadding(4, 4, 4, 4)
            }
            
            val imageView = ImageView(context).apply {
                id = android.R.id.icon
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFF333333.toInt())
            }
            layout.addView(imageView)
            
            // Video icon overlay
            val videoIcon = ImageView(context).apply {
                id = android.R.id.icon1
                layoutParams = FrameLayout.LayoutParams(48, 48).apply {
                    gravity = android.view.Gravity.CENTER
                }
                visibility = View.GONE
            }
            layout.addView(videoIcon)
            
            // File name text
            val textView = TextView(context).apply {
                id = android.R.id.text1
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.BOTTOM
                }
                setBackgroundColor(0x99000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 10f
                maxLines = 1
            }
            layout.addView(textView)
            
            return ViewHolder(layout)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]

            holder.textView.text = item.fileName

            // Video icon logic
            holder.videoIcon.visibility = if (item.fileType == "video") View.VISIBLE else View.GONE

            // Clear previous image to free memory immediately
            holder.imageView.setImageBitmap(null)

            val localPath = item.localPath
            if (localPath != null && File(localPath).exists()) {
                try {
                    if (item.fileType == "video") {
                        // Video Thumbnail (Already optimized by Android)
                        val bitmap = android.media.ThumbnailUtils.createVideoThumbnail(
                            localPath,
                            android.provider.MediaStore.Images.Thumbnails.MINI_KIND
                        )
                        if (bitmap != null) {
                            holder.imageView.setImageBitmap(bitmap)
                        } else {
                            holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    } else {
                        // --- PHOTO MEMORY FIX: Load smaller version ---
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = false
                        options.inSampleSize = 8 // Load 1/8th size (Small Thumbnail)

                        val bitmap = BitmapFactory.decodeFile(localPath, options)

                        if (bitmap != null) {
                            holder.imageView.setImageBitmap(bitmap)
                        } else {
                            holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GalleryAdapter", "Error loading thumbnail: ${e.message}")
                    holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } else {
                holder.imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }
        
        override fun getItemCount() = items.size
    }
}
