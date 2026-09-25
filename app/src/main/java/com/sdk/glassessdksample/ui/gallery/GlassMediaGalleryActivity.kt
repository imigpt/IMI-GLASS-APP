package com.sdk.glassessdksample.ui.gallery

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pDevice
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.DateUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.ui.wifi.DownloadFailure
import com.sdk.glassessdksample.ui.wifi.GlassMediaTransfer
import com.sdk.glassessdksample.ui.wifi.GlassMediaTransfer.MediaFileInfo
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
import com.sdk.glassessdksample.utils.SystemBarsInsets


/**
 * Glass Media Gallery — one screen with two modes:
 *
 *  - View:     media already saved on the phone, grouped by day, filterable by type.
 *  - Download: connect to the glasses (BLE → Wi-Fi Direct → HTTP/socket), list what is
 *              stored on them, pick files and download only those.
 */
class GlassMediaGalleryActivity : AppCompatActivity(), 
    GlassMediaTransfer.TransferListener,
    WifiP2pHelper.WifiP2pCallback {
    
    companion object {
        private const val TAG = "GlassMediaGallery"
        private const val LOCATION_PERMISSION_REQUEST = 1001
        private const val NEARBY_PERMISSION_REQUEST = 1002
        /** The Glass acts as Wi-Fi Direct Group Owner at this fixed address. */
        private const val GLASS_IP = "192.168.6.1"
        /** Any interface holding an address in this range is the Glass link. */
        private const val GLASS_SUBNET_PREFIX = "192.168.6."
        /** Give up looking for the glasses after this long and tell the user. */
        private const val CONNECT_TIMEOUT_MS = 75_000L
        private const val GRID_COLUMNS = 3
        
        fun launch(context: Context) {
            context.startActivity(Intent(context, GlassMediaGalleryActivity::class.java))
        }
    }

    private enum class Mode { VIEW, DOWNLOAD }

    private enum class Filter(val kind: GalleryMediaKind?, val label: String) {
        ALL(null, "media"),
        IMAGE(GalleryMediaKind.IMAGE, "images"),
        VIDEO(GalleryMediaKind.VIDEO, "videos"),
        RECORDING(GalleryMediaKind.RECORDING, "recordings"),
    }
    
    private lateinit var glassMediaTransfer: GlassMediaTransfer
    private lateinit var wifiP2pHelper: WifiP2pHelper
    // HTTP-based album downloader for Glass HTTP server (port 80)
    private val albumDownloader by lazy { com.sdk.glassessdksample.ui.wifi.AlbumDownloader(this) }
    // 🌐 Callback holding the Wi-Fi Direct network. Android routes every socket over
    // the DEFAULT network (home WiFi, which has internet) unless a socket is explicitly
    // bound elsewhere. The Glass lives on a P2P-only subnet with no internet, so
    // unbound sockets were being routed out wlan0 to the home router and dropped:
    //     failed to connect to /192.168.6.1 (port 80) from /192.168.1.36
    // Holding this callback both keeps the P2P network alive and gives us the Network
    // object whose socketFactory pins traffic to the p2p interface.
    private var p2pNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private var mode = Mode.VIEW
    private var filter = Filter.ALL
    /** Media saved on the phone (View mode). */
    private val localMedia = mutableListOf<MediaFileInfo>()
    /** Media stored on the glasses (Download mode); filled once connected. */
    private val remoteMedia = mutableListOf<MediaFileInfo>()
    /** Glass HTTP server address; null when the raw socket transfer is in use. */
    private var glassHttpIp: String? = null
    private val selectedFileNames = mutableSetOf<String>()
    /** View mode only: long-press (or the menu) selects files for deletion. */
    private var isViewSelectionMode = false
    private var isConnecting = false
    private var connectionTimeoutJob: Job? = null
    private var downloadJob: Job? = null
    /** Per-file progress for socket downloads, which report through the listener. */
    private var socketProgress: ((Int) -> Unit)? = null
    /** Last error reported by a socket download, used to tell storage from network. */
    private var lastSocketError: String? = null

    private lateinit var adapter: GalleryMediaAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var tabView: View
    private lateinit var tabDownload: View
    private lateinit var chips: Map<Filter, TextView>
    private lateinit var layoutDownloadHeader: View
    private lateinit var tvSelectAction: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var statusDot: View
    private lateinit var layoutEmpty: View
    private lateinit var ivEmptyIcon: ImageView
    private lateinit var tvEmptyTitle: TextView
    private lateinit var tvEmptyMessage: TextView
    private lateinit var btnEmptyAction: TextView
    private lateinit var layoutActionBar: View
    private lateinit var btnCancelSelection: View
    private lateinit var btnPrimaryAction: View
    private lateinit var ivPrimaryAction: ImageView
    private lateinit var tvPrimaryAction: TextView
    
    // Track if we went to WiFi settings
    private var wentToWifiSettings = false
    private var isConnectedToGlass = false
    /** True once this session has read the media list from the glasses. */
    private var isMediaListLoaded = false
    private var discoveredDevices = mutableListOf<WifiP2pDevice>()
    private var discoveryRetryJob: Job? = null
    private var progressDialog: Dialog? = null
    private var connectionProgressValue: Int = 0
    
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
                            // Guard: ignore duplicate BLE notifications during an active download
                            if (isConnectedToGlass || isMediaListLoaded) {
                                Log.d(TAG, "Ignoring duplicate BLE IP notification (already connected/done)")
                                return@runOnUiThread
                            }

                            updateStatus("📡 Glass IP from BLE: $ip")
                            isConnectedToGlass = true
                            discoveryRetryJob?.cancel()

                            // Stop P2P discovery — BLE gave us the IP, no need to scan
                            wifiP2pHelper.stopDiscovery()

                            // BLE confirmed the IP — Glass HTTP server is on port 80.
                            // But BLE only proves the Glass is READY, not that the phone
                            // has a route to it: this notification arrives BEFORE the
                            // Wi-Fi Direct link finishes forming. Downloading straight
                            // away therefore ran over home WiFi and timed out with
                            // "Config fetch error ... after 5000ms" → "No files found".
                            // Bind to the P2P network first, then fetch.
                            appendConnectionStep("Device connected via BLE")
                            appendConnectionStep("Preparing download")
                            updateConnectionProgress(55)
                            bindToP2pNetworkThen {
                                mainScope.launch { loadRemoteListViaHttp(ip) }
                            }
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
        setContentView(R.layout.activity_glass_media_gallery)
        SystemBarsInsets.apply(this)

        bindViews()

        // Initialize WiFi P2P helper
        wifiP2pHelper = WifiP2pHelper(this)
        wifiP2pHelper.setCallback(this)
        wifiP2pHelper.initialize()
        wifiP2pHelper.registerReceiver()
        
        // Initialize transfer manager
        glassMediaTransfer = GlassMediaTransfer(this)
        glassMediaTransfer.setListener(this)
        
        // Widen the BLE pipe. The bulk media download here runs over WiFi, but the
        // BLE link still carries control commands and thumbnail data, so the larger
        // MTU and faster interval shorten the setup phase before WiFi takes over.
        com.sdk.glassessdksample.ui.BleSpeedTuner.tune(this, "MediaGallery")

        // Register BLE notification listener for Glass IP (type 8)
        // Using listener ID 2 like the original app
        LargeDataHandler.getInstance().addOutDeviceListener(2, bleDeviceNotifyListener)
        Log.d(TAG, "📡 BLE notification listener registered for Glass IP")

        loadLocalFiles()
        render()
    }

    private fun bindViews() {
        recyclerView = findViewById(R.id.recyclerView)
        tabView = findViewById(R.id.tabView)
        tabDownload = findViewById(R.id.tabDownload)
        layoutDownloadHeader = findViewById(R.id.layoutDownloadHeader)
        tvSelectAction = findViewById(R.id.tvSelectAction)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        statusDot = findViewById(R.id.statusDot)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        ivEmptyIcon = findViewById(R.id.ivEmptyIcon)
        tvEmptyTitle = findViewById(R.id.tvEmptyTitle)
        tvEmptyMessage = findViewById(R.id.tvEmptyMessage)
        btnEmptyAction = findViewById(R.id.btnEmptyAction)
        layoutActionBar = findViewById(R.id.layoutActionBar)
        btnCancelSelection = findViewById(R.id.btnCancelSelection)
        btnPrimaryAction = findViewById(R.id.btnPrimaryAction)
        ivPrimaryAction = findViewById(R.id.ivPrimaryAction)
        tvPrimaryAction = findViewById(R.id.tvPrimaryAction)
        chips = mapOf(
            Filter.ALL to findViewById(R.id.chipAll),
            Filter.IMAGE to findViewById(R.id.chipImage),
            Filter.VIDEO to findViewById(R.id.chipVideo),
            Filter.RECORDING to findViewById(R.id.chipRecording),
        )

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showOverflowMenu(it) }
        tabView.setOnClickListener { setMode(Mode.VIEW) }
        tabDownload.setOnClickListener { setMode(Mode.DOWNLOAD) }
        chips.forEach { (f, chip) ->
            chip.setOnClickListener {
                filter = f
                render()
                recyclerView.scrollToPosition(0)
            }
        }
        tvSelectAction.setOnClickListener { toggleSelectAll() }
        btnCancelSelection.setOnClickListener { clearSelection() }
        btnPrimaryAction.setOnClickListener {
            if (mode == Mode.DOWNLOAD) downloadSelected() else deleteSelectedImages()
        }

        adapter = GalleryMediaAdapter(
            scope = mainScope,
            onTap = ::onTileTapped,
            onLongPress = ::onTileLongPressed,
        )
        recyclerView.layoutManager = GridLayoutManager(this, GRID_COLUMNS).apply {
            spanSizeLookup = adapter.spanSizeLookup(GRID_COLUMNS)
        }
        recyclerView.adapter = adapter

        // Back first leaves selection, then the screen.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isViewSelectionMode || selectedFileNames.isNotEmpty()) {
                    clearSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
                loadRemoteListViaSocket()
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
                startConnection()
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
     * Shown when we joined the Glass Wi-Fi but its server doesn't answer.
     */
    private fun showRetryDialog() {
        showMessageDialog(
            title = "Your glasses aren't responding",
            message = "Your phone is on the glasses' Wi-Fi, but the glasses didn't answer. " +
                "They may still be starting up — wait a few seconds and try again. " +
                "If it keeps happening, restart your glasses.",
            primaryText = "Try again",
            onPrimary = {
                mainScope.launch {
                    delay(2000)
                    checkAndConnectToGlass()
                }
            },
            secondaryText = "Wi-Fi settings",
            onSecondary = {
                wentToWifiSettings = true
                openWifiDirectSettings()
            }
        )
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
        showMessageDialog(
            title = "Turn on Location",
            message = "Android needs Location turned on to find your glasses over Wi-Fi Direct. " +
                "Your location isn't stored or shared.",
            primaryText = "Turn on",
            onPrimary = { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
            secondaryText = "Not now"
        )
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
                    Toast.makeText(this, "Allow Nearby devices so the app can find your glasses", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "Allow Location so the app can find your glasses", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    
    /**
     * Start connection process: wake the glasses' transfer mode over BLE, then find
     * them over Wi-Fi Direct. Ends in [showRemoteList] or [connectionFailed].
     */
    private fun startConnection() {
        if (isConnecting) return
        // Wi-Fi Direct needs these; ask only when the user actually connects.
        if (!hasLocationPermission() || !hasNearbyPermission()) {
            checkLocationPermission()
            return
        }
        if (!isLocationEnabled()) {
            showLocationEnableDialog()
            return
        }
        isConnecting = true
        showConnectionProgressDialog()

        // Refresh / Reconnect while the glasses are still reachable: just re-read the
        // list. Re-running Wi-Fi Direct here tore down the working link and failed.
        val knownIp = glassHttpIp
        if (knownIp != null) {
            updateConnectionProgress(60)
            mainScope.launch {
                if (albumDownloader.isReachable(knownIp)) {
                    isConnectedToGlass = true
                    loadRemoteListViaHttp(knownIp)
                } else {
                    glassHttpIp = null
                    startWifiDirectConnection()
                }
            }
            return
        }
        startWifiDirectConnection()
    }

    /** Full connection: BLE wake-up, then Wi-Fi Direct discovery and connect. */
    private fun startWifiDirectConnection() {
        isMediaListLoaded = false
        isConnectedToGlass = false
        connectionProgressValue = 0  // may follow the quick path, which jumped ahead
        updateConnectionProgress(5)
        updateStatus("📡 Sending BLE command to Glass...")
        render()

        connectionTimeoutJob?.cancel()
        connectionTimeoutJob = mainScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (isConnecting) {
                wifiP2pHelper.stopDiscovery()
                connectionFailed(
                    "We couldn't find your glasses",
                    "Make sure your glasses are turned on, charged and close to your phone, " +
                        "and that they're connected in the app. Then try again."
                )
            }
        }

        // Step 1: Trigger Glass hotspot/P2P mode
        glassMediaTransfer.triggerGlassHotspot()
        
        // Always use auto discovery (no method selection popup)
        mainScope.launch {
            delay(2000) // Wait for Glass P2P to start
            if (isConnecting) startAutoDiscovery()
        }
    }
    
    /**
     * Start Auto Discovery directly (default flow)
     */
    private fun startAutoDiscovery() {
        appendConnectionStep("Searching for Glass device")
        updateConnectionProgress(25)
        updateStatus("🔍 Searching for Glass devices...")
        wifiP2pHelper.startDiscovery()
    }

    /** User pressed Cancel in the connecting popup. */
    private fun cancelConnection() {
        if (!isConnecting) return
        isConnecting = false
        connectionTimeoutJob?.cancel()
        discoveryRetryJob?.cancel()
        wifiP2pHelper.stopDiscovery()
        dismissProgressDialog()
        render()
    }

    /** Stop connecting and explain what went wrong, with a retry. */
    private fun connectionFailed(title: String, message: String) {
        Log.w(TAG, "Connection failed: $title — $message")
        isConnecting = false
        connectionTimeoutJob?.cancel()
        discoveryRetryJob?.cancel()
        dismissProgressDialog()
        render()
        showMessageDialog(
            title = title,
            message = message,
            primaryText = "Try again",
            onPrimary = { startConnection() },
            secondaryText = "Close"
        )
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
                if (connected) {
                    isConnectedToGlass = true
                    loadRemoteListViaSocket()
                } else {
                    startConnection()
                }
            }
        }
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
    

    // ===============================
    // Screen state & rendering
    // ===============================

    private fun setMode(newMode: Mode) {
        if (mode == newMode) return
        mode = newMode
        selectedFileNames.clear()
        isViewSelectionMode = false
        if (newMode == Mode.VIEW) loadLocalFiles()
        render()
        recyclerView.scrollToPosition(0)
    }

    /** Redraw everything from the current state. Cheap enough to call on any change. */
    private fun render() {
        styleTab(tabView, R.id.tabViewIcon, R.id.tabViewLabel, active = mode == Mode.VIEW)
        styleTab(tabDownload, R.id.tabDownloadIcon, R.id.tabDownloadLabel, active = mode == Mode.DOWNLOAD)
        chips.forEach { (f, chip) ->
            val active = f == filter
            chip.setBackgroundResource(if (active) R.drawable.bg_gm_chip_active else R.drawable.bg_gm_chip_inactive)
            chip.setTextColor(color(if (active) R.color.gm_on_accent else R.color.gm_text_primary))
            chip.setTypeface(chip.typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }

        val source = if (mode == Mode.VIEW) localMedia else remoteMedia
        val visible = source.filter { filter.kind == null || it.kind() == filter.kind }
        val rows = if (mode == Mode.VIEW) groupByDay(visible) else visible.map { GalleryRow.Tile(it) }
        adapter.submit(
            rows = rows,
            selected = selectedFileNames,
            selectionEnabled = mode == Mode.DOWNLOAD || isViewSelectionMode,
            showSavedBadge = mode == Mode.DOWNLOAD,
        )

        renderDownloadHeader(visible)
        renderEmptyState(source, visible)
        renderActionBar()
    }

    private fun styleTab(tab: View, iconId: Int, labelId: Int, active: Boolean) {
        tab.setBackgroundResource(if (active) R.drawable.bg_gm_segment_active else 0)
        val tint = color(if (active) R.color.gm_on_accent else R.color.gm_text_primary)
        tab.findViewById<ImageView>(iconId).imageTintList = ColorStateList.valueOf(tint)
        tab.findViewById<TextView>(labelId).apply {
            setTextColor(tint)
            setTypeface(typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun renderDownloadHeader(visible: List<MediaFileInfo>) {
        val show = mode == Mode.DOWNLOAD && isMediaListLoaded && remoteMedia.isNotEmpty()
        layoutDownloadHeader.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val selectable = visible.filterNot { it.isDownloaded }
        val count = selectedFileNames.size
        tvSelectAction.visibility = if (selectable.isEmpty() && count == 0) View.GONE else View.VISIBLE
        tvSelectAction.text = if (count == 0) "Select all" else "$count selected"

        val saved = remoteMedia.count { it.isDownloaded }
        val onGlasses = "${remoteMedia.size} on your glasses · $saved saved"
        tvConnectionStatus.text = if (isConnectedToGlass) "Connected · $onGlasses" else "Disconnected · $onGlasses"
        statusDot.backgroundTintList = ColorStateList.valueOf(
            if (isConnectedToGlass) Color.parseColor("#34C759") else color(R.color.gm_text_secondary)
        )
    }

    private fun renderEmptyState(source: List<MediaFileInfo>, visible: List<MediaFileInfo>) {
        data class Empty(val icon: Int, val title: String, val message: String, val action: String?, val onAction: (() -> Unit)?)

        val empty: Empty? = when {
            visible.isNotEmpty() -> null
            mode == Mode.VIEW && source.isEmpty() -> Empty(
                R.drawable.ic_gm_image, "No media yet",
                "Photos, videos and recordings you download from your glasses will show up here.",
                "Download from glasses"
            ) { setMode(Mode.DOWNLOAD) }
            mode == Mode.VIEW -> Empty(
                iconFor(filter), "No ${filter.label} yet", "Try a different filter.", null, null
            )
            !isMediaListLoaded -> Empty(
                R.drawable.ic_gm_download, "Connect your glasses",
                "Turn on your glasses and keep them close to your phone. We'll show what's on them " +
                    "so you can choose what to download.",
                if (isConnecting) "Connecting…" else "Connect to glasses"
            ) { startConnection() }
            source.isEmpty() -> Empty(
                R.drawable.ic_gm_download, "Nothing on your glasses",
                "Take some photos or videos with your glasses, then refresh.",
                "Refresh"
            ) { startConnection() }
            else -> Empty(
                iconFor(filter), "No ${filter.label} on your glasses", "Try a different filter.", null, null
            )
        }

        layoutEmpty.visibility = if (empty == null) View.GONE else View.VISIBLE
        recyclerView.visibility = if (empty == null) View.VISIBLE else View.INVISIBLE
        if (empty == null) return

        ivEmptyIcon.setImageResource(empty.icon)
        tvEmptyTitle.text = empty.title
        tvEmptyMessage.text = empty.message
        btnEmptyAction.visibility = if (empty.action == null) View.GONE else View.VISIBLE
        btnEmptyAction.text = empty.action
        btnEmptyAction.isEnabled = !isConnecting
        btnEmptyAction.alpha = if (isConnecting) 0.6f else 1f
        btnEmptyAction.setOnClickListener { empty.onAction?.invoke() }
    }

    private fun renderActionBar() {
        val count = selectedFileNames.size
        val show = (mode == Mode.DOWNLOAD && isMediaListLoaded && remoteMedia.isNotEmpty()) ||
            (mode == Mode.VIEW && isViewSelectionMode)
        layoutActionBar.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        if (mode == Mode.DOWNLOAD) {
            btnPrimaryAction.setBackgroundResource(R.drawable.bg_gm_button_primary)
            ivPrimaryAction.setImageResource(R.drawable.ic_gm_download)
            ivPrimaryAction.imageTintList = ColorStateList.valueOf(color(R.color.gm_on_accent))
            tvPrimaryAction.setTextColor(color(R.color.gm_on_accent))
            tvPrimaryAction.text = "Download ($count)"
        } else {
            btnPrimaryAction.setBackgroundResource(R.drawable.bg_gm_button_danger)
            ivPrimaryAction.setImageResource(R.drawable.ic_gm_delete)
            ivPrimaryAction.imageTintList = ColorStateList.valueOf(Color.WHITE)
            tvPrimaryAction.setTextColor(Color.WHITE)
            tvPrimaryAction.text = "Delete ($count)"
        }
        btnPrimaryAction.isEnabled = count > 0
        btnPrimaryAction.alpha = if (count > 0) 1f else 0.45f
        // In View mode Cancel also leaves selection mode, so it's always useful there.
        val cancelEnabled = count > 0 || mode == Mode.VIEW
        btnCancelSelection.isEnabled = cancelEnabled
        btnCancelSelection.alpha = if (cancelEnabled) 1f else 0.45f
    }

    private fun iconFor(f: Filter) = when (f.kind) {
        GalleryMediaKind.VIDEO -> R.drawable.ic_gm_video
        GalleryMediaKind.RECORDING -> R.drawable.ic_gm_audio
        else -> R.drawable.ic_gm_image
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    /** "Today", "Yesterday", "Mon, 22 Sep" … headers, newest first. */
    private fun groupByDay(items: List<MediaFileInfo>): List<GalleryRow> {
        val rows = mutableListOf<GalleryRow>()
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val sameYearFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        val otherYearFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        items.sortedByDescending { it.timestamp }
            .groupBy { dayKey(it.timestamp) }
            .forEach { (_, dayItems) ->
                val ts = dayItems.first().timestamp
                val title = when {
                    DateUtils.isToday(ts) -> "Today"
                    DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
                    Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR) == thisYear ->
                        sameYearFormat.format(Date(ts))
                    else -> otherYearFormat.format(Date(ts))
                }
                rows += GalleryRow.Header(title, dayItems.size)
                dayItems.forEach { rows += GalleryRow.Tile(it) }
            }
        return rows
    }

    private fun dayKey(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    // ===============================
    // Taps, selection, menu
    // ===============================

    private fun onTileTapped(item: MediaFileInfo) {
        when {
            mode == Mode.DOWNLOAD && item.isDownloaded -> openMedia(item)
            mode == Mode.DOWNLOAD -> toggleSelection(item)
            isViewSelectionMode -> toggleSelection(item)
            else -> openMedia(item)
        }
    }

    private fun onTileLongPressed(item: MediaFileInfo) {
        if (mode == Mode.DOWNLOAD) {
            if (!item.isDownloaded) toggleSelection(item)
            return
        }
        if (!isViewSelectionMode) {
            isViewSelectionMode = true
            selectedFileNames.clear()
        }
        toggleSelection(item)
    }

    private fun toggleSelection(item: MediaFileInfo) {
        if (!selectedFileNames.remove(item.fileName)) selectedFileNames.add(item.fileName)
        render()
    }

    /** Download header: select every visible file not yet saved, or clear if all are. */
    private fun toggleSelectAll() {
        val selectable = remoteMedia
            .filter { !it.isDownloaded && (filter.kind == null || it.kind() == filter.kind) }
            .map { it.fileName }
        if (selectable.isNotEmpty() && selectedFileNames.containsAll(selectable)) {
            selectedFileNames.removeAll(selectable.toSet())
        } else {
            selectedFileNames.addAll(selectable)
        }
        render()
    }

    private fun clearSelection() {
        selectedFileNames.clear()
        isViewSelectionMode = false
        render()
    }

    private data class MenuEntry(val icon: Int, val label: String, val onClick: () -> Unit)

    /** Settings button: a dark dropdown card with an icon per option. */
    private fun showOverflowMenu(anchor: View) {
        val entries = mutableListOf<MenuEntry>()
        if (mode == Mode.VIEW) {
            if (isViewSelectionMode) {
                entries += MenuEntry(R.drawable.ic_close, "Cancel selection") { clearSelection() }
            } else {
                entries += MenuEntry(R.drawable.ic_gm_select, "Select items") {
                    isViewSelectionMode = true
                    render()
                }
            }
            entries += MenuEntry(R.drawable.ic_gm_download, "Download from glasses") { setMode(Mode.DOWNLOAD) }
        } else {
            if (!isConnecting) {
                entries += MenuEntry(
                    R.drawable.ic_refresh,
                    if (isMediaListLoaded) "Refresh from glasses" else "Connect to glasses"
                ) { startConnection() }
            }
            if (isMediaListLoaded && remoteMedia.any { !it.isDownloaded }) {
                entries += MenuEntry(R.drawable.ic_gm_select, "Select all") {
                    selectedFileNames.addAll(remoteMedia.filterNot { it.isDownloaded }.map { it.fileName })
                    render()
                }
            }
            if (selectedFileNames.isNotEmpty()) {
                entries += MenuEntry(R.drawable.ic_close, "Clear selection") { clearSelection() }
            }
            entries += MenuEntry(R.drawable.ic_eye, "View saved media") { setMode(Mode.VIEW) }
        }
        entries += MenuEntry(R.drawable.ic_gm_wifi, "Wi-Fi Direct settings") {
            wentToWifiSettings = true
            openWifiDirectSettings()
        }

        val inflater = layoutInflater
        val content = inflater.inflate(R.layout.popup_gallery_menu, null) as LinearLayout
        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12 * resources.displayMetrics.density
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
        }
        entries.forEach { entry ->
            val row = inflater.inflate(R.layout.item_gallery_menu, content, false)
            row.findViewById<ImageView>(R.id.menuIcon).setImageResource(entry.icon)
            row.findViewById<TextView>(R.id.menuLabel).text = entry.label
            row.setOnClickListener {
                popup.dismiss()
                entry.onClick()
            }
            content.addView(row)
        }

        // Right-align the card under the settings button.
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val gap = (8 * resources.displayMetrics.density).toInt()
        popup.showAsDropDown(anchor, anchor.width - content.measuredWidth, gap)
    }
    
    /**
     * Delete the selected files from the phone (View mode).
     */
    private fun deleteSelectedImages() {
        val selectedCount = selectedFileNames.size
        if (selectedCount == 0) return
        val noun = if (selectedCount == 1) "item" else "items"

        showMessageDialog(
            title = "Delete $selectedCount $noun?",
            message = "${if (selectedCount == 1) "It" else "They"} will be removed from this phone. " +
                "Anything still on your glasses isn't affected.",
            primaryText = "Delete",
            primaryIsDestructive = true,
            onPrimary = {
                var deletedCount = 0
                val iterator = localMedia.iterator()
                while (iterator.hasNext()) {
                    val item = iterator.next()
                    if (item.fileName !in selectedFileNames) continue
                    val file = item.localPath?.let(::File)
                    if (file == null || !file.exists() || file.delete()) {
                        iterator.remove()
                        deletedCount++
                        // It's no longer saved, so it can be downloaded again.
                        remoteMedia.find { it.fileName == item.fileName }?.apply {
                            localPath = null
                            isDownloaded = false
                        }
                    }
                }
                clearSelection()
                val deletedNoun = if (deletedCount == 1) "item" else "items"
                Snackbar.make(recyclerView, "Deleted $deletedCount $deletedNoun", Snackbar.LENGTH_SHORT).show()
            },
            secondaryText = "Cancel"
        )
    }

    // ===============================
    // Local files (View mode)
    // ===============================

    private fun audioDirectory(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).path + "/GlassMedia/"
        )
        dir.mkdirs()
        return dir
    }

    private fun localDirectories() = listOf(
        glassMediaTransfer.getPhotosDirectory(),
        glassMediaTransfer.getVideosDirectory(),
        audioDirectory(),
    )

    /** The complete saved copy of [fileName] on the phone, if any. */
    private fun localFileFor(fileName: String): File? =
        localDirectories().map { File(it, fileName) }.firstOrNull { it.exists() && isCompleteFile(it) }

    /**
     * Earlier builds could leave a download cut short (the image shows its top part
     * and then black). A complete JPEG ends with the FF D9 marker; anything else
     * must not count as saved, so it can be downloaded again.
     */
    private fun isCompleteFile(file: File): Boolean {
        if (file.length() <= 0) return false
        val ext = file.extension.lowercase()
        if (ext != "jpg" && ext != "jpeg") return true
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val tailSize = minOf(64L, raf.length()).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(raf.length() - tailSize)
                raf.readFully(tail)
                // Allow trailing padding after the end-of-image marker.
                (0 until tailSize - 1).any { tail[it] == 0xFF.toByte() && tail[it + 1] == 0xD9.toByte() }
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Where a downloaded file goes. Images and videos keep the original folder. */
    private fun downloadDirFor(item: MediaFileInfo): File =
        if (item.kind() == GalleryMediaKind.RECORDING) audioDirectory() else glassMediaTransfer.getPhotosDirectory()
    
    /**
     * Load already downloaded files
     */
    private fun loadLocalFiles() {
        val seen = mutableSetOf<String>()
        val existing = localDirectories()
            .flatMap { it.listFiles()?.toList().orEmpty() }
            // Hidden files are downloads still in progress.
            .filter { it.isFile && !it.name.startsWith(".") && it.length() > 0 && seen.add(it.name) }
            .map { file ->
                val ext = file.extension.lowercase()
                MediaFileInfo(
                    fileName = file.name,
                    fileType = when (ext) {
                        in AUDIO_EXTENSIONS -> "audio"
                        in VIDEO_EXTENSIONS -> "video"
                        else -> "photo"
                    },
                    fileSize = file.length(),
                    timestamp = file.lastModified(),
                    localPath = file.absolutePath,
                    isDownloaded = true
                )
            }
        localMedia.clear()
        localMedia.addAll(existing.sortedByDescending { it.timestamp })
        updateStatus("${localMedia.size} local files found")
    }
    
    /**
     * Open media file
     */
    private fun openMedia(fileInfo: MediaFileInfo) {
        val localPath = fileInfo.localPath
        if (localPath == null) {
            Toast.makeText(this, "Download this file first to open it", Toast.LENGTH_SHORT).show()
            return
        }
        openLocalFile(localPath, fileInfo)
    }
    
    private fun openLocalFile(path: String, fileInfo: MediaFileInfo) {
        try {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "File does not exist: $path")
                Toast.makeText(this, "This file is no longer on your phone", Toast.LENGTH_SHORT).show()
                loadLocalFiles()
                render()
                return
            }

            when (fileInfo.kind()) {
                GalleryMediaKind.IMAGE -> {
                    // All saved images, for swipe navigation in the viewer
                    val allImagePaths = localMedia
                        .filter { it.kind() == GalleryMediaKind.IMAGE }
                        .mapNotNull { it.localPath }
                        .ifEmpty { listOf(path) }
                    val currentIndex = allImagePaths.indexOf(path).coerceAtLeast(0)
                    ImageViewerActivity.open(this, path, file.name, ArrayList(allImagePaths), currentIndex)
                }
                // The in-app player uses VideoView, which plays audio-only files too.
                GalleryMediaKind.VIDEO, GalleryMediaKind.RECORDING ->
                    VideoPlayerActivity.open(this, path, file.name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening file: ${e.message}", e)
            Toast.makeText(this, "Couldn't open this file", Toast.LENGTH_SHORT).show()
        }
    }
    
    /** Technical progress goes to logcat; the UI shows friendly text instead. */
    private fun updateStatus(status: String) {
        Log.d(TAG, status)
    }

    // ===============================
    // Popups
    // ===============================

    /** A card that sits near the bottom of the screen, like the app's other popups. */
    private fun newGalleryDialog(layout: Int, cancelable: Boolean): Dialog =
        Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(layout)
            setCancelable(cancelable)
            window?.let { w ->
                val density = resources.displayMetrics.density
                val margin = (14 * density).toInt()
                w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                w.setLayout(resources.displayMetrics.widthPixels - 2 * margin, ViewGroup.LayoutParams.WRAP_CONTENT)
                w.setGravity(android.view.Gravity.BOTTOM)
                w.attributes = w.attributes.apply { y = (18 * density).toInt() }
                w.setDimAmount(0.6f)
            }
        }

    /** One consistent popup for every message on this screen. */
    private fun showMessageDialog(
        title: String,
        message: String,
        primaryText: String,
        onPrimary: (() -> Unit)? = null,
        secondaryText: String? = null,
        onSecondary: (() -> Unit)? = null,
        primaryIsDestructive: Boolean = false,
    ) {
        if (isFinishing || isDestroyed) return
        val dialog = newGalleryDialog(R.layout.dialog_gallery_message, cancelable = true)
        dialog.findViewById<TextView>(R.id.tvDialogTitle).text = title
        dialog.findViewById<TextView>(R.id.tvDialogMessage).text = message
        dialog.findViewById<TextView>(R.id.btnDialogPrimary).apply {
            text = primaryText
            if (primaryIsDestructive) {
                setBackgroundResource(R.drawable.bg_gm_button_danger)
                setTextColor(Color.WHITE)
            }
            setOnClickListener { dialog.dismiss(); onPrimary?.invoke() }
        }
        dialog.findViewById<TextView>(R.id.btnDialogSecondary).apply {
            if (secondaryText == null) {
                visibility = View.GONE
            } else {
                text = secondaryText
                setOnClickListener { dialog.dismiss(); onSecondary?.invoke() }
            }
        }
        dialog.show()
    }

    /**
     * Progress popup: header, Connect → Discover → Sync stepper, spinner, status
     * line, detail line and a progress bar. Close (X) and Cancel both call [onCancel].
     */
    private fun showProgressDialog(title: String, subtitle: String, onCancel: () -> Unit) {
        dismissProgressDialog()
        if (isFinishing || isDestroyed) return
        val dialog = newGalleryDialog(R.layout.dialog_gallery_progress, cancelable = false)
        dialog.findViewById<TextView>(R.id.tvDialogTitle).text = title
        dialog.findViewById<TextView>(R.id.tvDialogSubtitle).text = subtitle
        dialog.findViewById<View>(R.id.btnDialogCancel).setOnClickListener { onCancel() }
        dialog.findViewById<View>(R.id.btnDialogClose).setOnClickListener { onCancel() }
        progressDialog = dialog
        updateProgressDialog(step = 0, percent = 0)
        dialog.show()
    }

    /** Update any part of the progress popup; null leaves that part unchanged. */
    private fun updateProgressDialog(
        step: Int? = null,
        status: String? = null,
        detail: String? = null,
        percent: Int? = null,
    ) {
        val dialog = progressDialog ?: return
        step?.let { renderStepper(dialog, it) }
        status?.let { dialog.findViewById<TextView>(R.id.tvDialogStatus).text = it }
        detail?.let { dialog.findViewById<TextView>(R.id.tvDialogDetail).text = it }
        percent?.let {
            val p = it.coerceIn(0, 100)
            dialog.findViewById<ProgressBar>(R.id.dialogProgress).progress = p
            dialog.findViewById<TextView>(R.id.tvDialogPercent).text = "$p%"
        }
    }

    /** Steps before [active] are done (check), [active] is highlighted, the rest pending. */
    private fun renderStepper(dialog: Dialog, active: Int) {
        val circles = listOf(R.id.stepCircle1, R.id.stepCircle2, R.id.stepCircle3)
        val numbers = listOf(R.id.stepNumber1, R.id.stepNumber2, R.id.stepNumber3)
        val checks = listOf(R.id.stepCheck1, R.id.stepCheck2, R.id.stepCheck3)
        val labels = listOf(R.id.stepLabel1, R.id.stepLabel2, R.id.stepLabel3)
        for (i in circles.indices) {
            val done = i < active
            val current = i == active
            dialog.findViewById<View>(circles[i]).setBackgroundResource(
                when {
                    done -> R.drawable.bg_gm_step_done
                    current -> R.drawable.bg_gm_step_active
                    else -> R.drawable.bg_gm_step_pending
                }
            )
            dialog.findViewById<View>(numbers[i]).visibility = if (done) View.INVISIBLE else View.VISIBLE
            dialog.findViewById<View>(checks[i]).visibility = if (done) View.VISIBLE else View.GONE
            dialog.findViewById<TextView>(labels[i]).setTextColor(
                color(if (done || current) R.color.gm_text_primary else R.color.gm_text_secondary)
            )
        }
        listOf(R.id.stepLine1, R.id.stepLine2).forEachIndexed { i, id ->
            dialog.findViewById<View>(id).setBackgroundColor(
                color(if (i < active) R.color.gm_accent else R.color.gm_border)
            )
        }
    }

    private fun dismissProgressDialog() {
        progressDialog?.takeIf { it.isShowing }?.dismiss()
        progressDialog = null
    }

    private fun showConnectionProgressDialog() {
        connectionProgressValue = 0
        showProgressDialog(
            "Connect to Glass",
            "Access your photos and videos from your smart glasses."
        ) { cancelConnection() }
        updateConnectionProgress(0)
    }

    /** Detailed connection steps are for logcat; the popup shows the stage. */
    private fun appendConnectionStep(step: String) {
        Log.d(TAG, "Connection step: $step")
    }

    private fun updateConnectionProgress(value: Int) {
        if (!isConnecting) return
        val clamped = value.coerceIn(0, 100)
        if (clamped < connectionProgressValue) return
        connectionProgressValue = clamped
        val stage = connectionStage(clamped)
        updateProgressDialog(stage.step, stage.status, stage.detail, clamped)
    }

    private data class Stage(val step: Int, val status: String, val detail: String)

    /** Maps connection progress to the stepper: 0 = Connect, 1 = Discover, 2 = Sync. */
    private fun connectionStage(progress: Int) = when {
        progress < 25 -> Stage(0, "Connecting to your glasses…", "Keep your glasses nearby.")
        progress < 45 -> Stage(1, "Looking for your glasses…", "Make sure they're turned on.")
        progress < 60 -> Stage(1, "Connecting over Wi-Fi…", "This can take up to a minute.")
        else -> Stage(2, "Reading your media…", "Almost there.")
    }

    /** Connection attempt is over (success or not); [finalStatus] is logged. */
    private fun closeConnectionProgressDialog(finalStatus: String) {
        isConnecting = false
        connectionTimeoutJob?.cancel()
        dismissProgressDialog()
        updateStatus(finalStatus)
        render()
    }

    /**
     * 🌐 Acquire the Wi-Fi Direct network and route Glass traffic over it.
     *
     * Without this, sockets follow Android's default network. That default is the
     * phone's home WiFi (it has internet, so the OS prefers it), and the Glass subnet
     * is only reachable over the p2p interface — so every connection attempt was
     * routed to the home router and refused. Confirmed from the device routing table:
     *     192.168.6.1 via 192.168.1.1 dev wlan0 src 192.168.1.36
     *
     * requestNetwork() with NET_CAPABILITY_NOT_INTERNET is the supported way to ask
     * for such a network: the P2P link is deliberately internet-less, so the normal
     * "give me WiFi" request would never match it.
     *
     * [onReady] runs once the network is available and HTTP has been pinned to it.
     * It is invoked at most once per call, on the main thread.
     */
    private fun bindToP2pNetworkThen(onReady: () -> Unit) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "🌐 No ConnectivityManager — proceeding on default route")
            onReady()
            return
        }

        // Releasing any previous request first: a stale callback from an earlier
        // attempt would otherwise keep an old (possibly dead) P2P network alive.
        releaseP2pNetwork()

        // Run off the main thread: isReachable() does real TCP connects.
        Thread {
            val bound = tryBindToGlass(cm, GLASS_IP)
            runOnUiThread {
                if (bound) {
                    appendConnectionStep("Network route locked to Glass")
                } else {
                    // Say what actually happened. The previous build claimed
                    // "Network route locked to Glass" even when it had bound to the
                    // home WiFi, which made a failure look like a success.
                    appendConnectionStep("No route to Glass yet — trying anyway")
                }
                onReady()
            }
        }.start()
    }

    /**
     * Bind HTTP to whatever network can actually reach [targetIp], if any.
     *
     * Mirrors the strategy already proven in VisionChatActivity.ensureGlassNetworkBound().
     * Order matters, cheapest first:
     *
     *  0. Already reachable on current routing? An active Wi-Fi Direct group installs a
     *     connected route that ConnectivityManager never surfaces as a Network object,
     *     so this case CANNOT be detected by inspecting networks — only by trying it.
     *     This is the normal working case and must not be blocked.
     *  1. Otherwise bind to a connected Network holding an address in the Glass subnet
     *     (a manually-joined Glass hotspot), and verify it really reaches the Glass.
     *
     * Returns true only when the Glass is genuinely reachable — never on a guess.
     */
    private fun tryBindToGlass(cm: ConnectivityManager, targetIp: String): Boolean {
        // 0. Cheapest: does the current routing already work?
        if (runBlocking { albumDownloader.isReachable(targetIp) }) {
            Log.i(TAG, "🌐 $targetIp already reachable on current routing — no binding needed")
            return true
        }

        // 1. Look for a network whose own address sits in the Glass subnet. Matching on
        //    the address (not just "is WiFi") is what stops us binding to the home
        //    network: plain WiFi matches a TRANSPORT_WIFI request, and binding to it
        //    sent every request out wlan0 to the home router.
        for (net in cm.allNetworks) {
            val lp = cm.getLinkProperties(net) ?: continue
            val iface = lp.interfaceName ?: "?"
            val matches = lp.linkAddresses.any { la ->
                la.address?.hostAddress?.startsWith(GLASS_SUBNET_PREFIX) == true
            }
            if (!matches) continue

            Log.i(TAG, "🌐 Trying candidate network $net (iface=$iface) for $targetIp")
            albumDownloader.bindToNetwork(net)
            if (runBlocking { albumDownloader.isReachable(targetIp) }) {
                Log.i(TAG, "🌐 Bound Glass HTTP to $net (iface=$iface)")
                return true
            }
            albumDownloader.bindToNetwork(null)
        }

        Log.w(TAG, "🌐 No network can reach $targetIp — the Wi-Fi Direct group has no " +
                "interface on this phone. Proceeding on the default route (will likely fail).")
        return false
    }

    /** Release the P2P network request and stop forcing HTTP through it. */
    private fun releaseP2pNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        p2pNetworkCallback?.let {
            try {
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.w(TAG, "🌐 unregisterNetworkCallback: ${e.message}")
            }
        }
        p2pNetworkCallback = null
        albumDownloader.bindToNetwork(null)
    }

    // Probes common ports on the given Glass IP; if open port found, set server ip/port and proceed
    private fun testPortsAndProceed(glassIp: String) {
        Thread {
            // Put 80 first as logs showed it working for some devices
            val commonPorts = listOf(80, 8888, 8899, 8080, 9000, 5000)
            var openPort: Int? = null

            // Probe over the SAME network the download will use. A bare Socket() follows
            // the default route (home WiFi) and reported every port closed even though
            // the Glass server was up — that was the "Glass server failed to respond".
            val p2p = albumDownloader.currentBoundNetwork()
            for (port in commonPorts) {
                try {
                    val sock = p2p?.socketFactory?.createSocket() ?: Socket()
                    sock.use {
                        it.connect(InetSocketAddress(glassIp, port), 1500)
                    }
                    Log.e("PORT_TEST", "✅ OPEN PORT FOUND: $port (bound=${p2p != null})")
                    openPort = port
                    break
                } catch (e: Exception) {
                    Log.d("PORT_TEST", "❌ closed $port (bound=${p2p != null})")
                }
            }

            runOnUiThread {
                if (openPort == 80) {
                    // HTTP path
                    appendConnectionStep("Device connected")
                    appendConnectionStep("Preparing download")
                    updateConnectionProgress(55)
                    updateStatus("✅ Connected via HTTP (Port 80)")
                    isConnectedToGlass = true
                    try {
                        val m = wifiP2pHelper::class.java.getMethod("stopDiscovery")
                        m.invoke(wifiP2pHelper)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error stopping scanner: ${e.message}")
                    }

                    mainScope.launch {
                        loadRemoteListViaHttp(glassIp)
                    }

                } else if (openPort != null) {
                    // Raw socket path
                    appendConnectionStep("Device connected")
                    appendConnectionStep("Preparing download")
                    updateConnectionProgress(55)
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
                        loadRemoteListViaSocket()
                    }

                } else {
                    isConnectedToGlass = false
                    appendConnectionStep("Failed to reach Glass server")
                    connectionFailed(
                        "Your glasses aren't responding",
                        "We found your glasses, but their file server didn't answer. " +
                            "Restart your glasses and try again."
                    )
                }
            }
        }.start()
    }


    // ===============================
    // Media on the glasses (Download mode)
    // ===============================

    /** HTTP server on port 80: read the file list. Nothing is downloaded yet. */
    private suspend fun loadRemoteListViaHttp(ip: String) {
        glassHttpIp = ip
        appendConnectionStep("Fetching media list")
        updateConnectionProgress(65)
        updateStatus("📋 Fetching HTTP file list...")

        val items = try {
            withContext(Dispatchers.IO) { albumDownloader.fetchConfig(ip) }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP fetchConfig failed: ${e.message}")
            connectionFailed(
                "Couldn't read your glasses",
                "We connected, but couldn't get the list of files. Please try again."
            )
            return
        }

        showRemoteList(items.map { item ->
            MediaFileInfo(
                fileName = item.fileName,
                fileType = when (item.type) {
                    2 -> "video"
                    3 -> "audio"
                    else -> "photo"
                },
                fileSize = 0L,
                timestamp = System.currentTimeMillis(),
            )
        })
    }

    /** Raw socket server: read the file list. Nothing is downloaded yet. */
    private fun loadRemoteListViaSocket() {
        glassHttpIp = null
        appendConnectionStep("Fetching media list from device")
        updateConnectionProgress(65)
        mainScope.launch {
            val files = try {
                glassMediaTransfer.getMediaList()
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching files: ${e.message}")
                connectionFailed(
                    "Couldn't read your glasses",
                    "We connected, but couldn't get the list of files. Please try again."
                )
                return@launch
            }
            showRemoteList(files)
        }
    }

    private fun showRemoteList(files: List<MediaFileInfo>) {
        remoteMedia.clear()
        files.sortedByDescending { it.fileName }.forEach { f ->
            localFileFor(f.fileName)?.let {
                f.localPath = it.absolutePath
                f.isDownloaded = true
            }
            remoteMedia += f
        }
        // Keep a selection made before a reconnect, minus anything now saved or gone.
        val selectable = remoteMedia.filterNot { it.isDownloaded }.map { it.fileName }.toSet()
        selectedFileNames.retainAll(selectable)

        isMediaListLoaded = true
        closeConnectionProgressDialog("Found ${remoteMedia.size} files on Glass")
        if (mode != Mode.DOWNLOAD) setMode(Mode.DOWNLOAD)
    }

    /** Download only the files the user picked. */
    private fun downloadSelected() {
        val targets = remoteMedia.filter { it.fileName in selectedFileNames && !it.isDownloaded }
        if (targets.isEmpty() || downloadJob?.isActive == true) return

        showProgressDialog(
            "Downloading media",
            "Saving the files you picked to your phone."
        ) { stopDownload() }
        // Connected and discovered already — this is the Sync step.
        updateProgressDialog(step = 2, status = "Preparing download…", detail = "Keep your glasses nearby.")
        downloadJob = mainScope.launch {
            var saved = 0
            var cancelled = false
            val failures = mutableListOf<DownloadFailure>()
            try {
                for ((index, item) in targets.withIndex()) {
                    ensureActive()
                    updateProgressDialog(
                        status = "Downloading ${index + 1} of ${targets.size}…",
                        detail = item.fileName,
                        percent = index * 100 / targets.size
                    )
                    val onFileProgress: (Int) -> Unit = { pct ->
                        runOnUiThread {
                            if (downloadJob?.isActive == true) {
                                updateProgressDialog(percent = (index * 100 + pct) / targets.size)
                            }
                        }
                    }
                    val (path, failure) = downloadOne(item, onFileProgress)
                    if (path != null) {
                        item.localPath = path
                        item.isDownloaded = true
                        selectedFileNames.remove(item.fileName)
                        saved++
                        render()
                        continue
                    }
                    failures += failure ?: DownloadFailure.NETWORK
                    // Every remaining file would fail the same way.
                    if (failure == DownloadFailure.STORAGE) break
                    if (failures.takeLast(2).count { it == DownloadFailure.NETWORK } == 2) break
                }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            } finally {
                dismissProgressDialog()
                loadLocalFiles()
                render()
                reportDownloadResult(saved, targets.size, cancelled, failures)
            }
        }
    }

    /** Cancel/X in the download popup. The popup closes when the job unwinds. */
    private fun stopDownload() {
        val job = downloadJob ?: return
        if (!job.isActive) return
        progressDialog?.let { dialog ->
            dialog.findViewById<View>(R.id.btnDialogCancel).isEnabled = false
            dialog.findViewById<View>(R.id.btnDialogCancel).alpha = 0.5f
            dialog.findViewById<View>(R.id.btnDialogClose).isEnabled = false
        }
        updateProgressDialog(status = "Stopping download…", detail = "Just a moment.")
        job.cancel()
    }

    /** Returns the saved path, or null with the reason it failed. */
    private suspend fun downloadOne(
        item: MediaFileInfo,
        onProgress: (Int) -> Unit,
    ): Pair<String?, DownloadFailure?> {
        val ip = glassHttpIp
        if (ip != null) {
            var failure: DownloadFailure? = null
            val file = withContext(Dispatchers.IO) {
                albumDownloader.downloadFile(
                    ip, item.fileName, downloadDirFor(item),
                    onFailure = { failure = it },
                    progressCallback = onProgress
                )
            }
            return file?.absolutePath to failure
        }

        socketProgress = onProgress
        lastSocketError = null
        val path = try {
            glassMediaTransfer.downloadFile(item)
        } finally {
            socketProgress = null
        }
        val error = lastSocketError.orEmpty()
        val failure = when {
            path != null -> null
            listOf("EPERM", "ENOSPC", "EACCES", "open failed").any { it in error } -> DownloadFailure.STORAGE
            else -> DownloadFailure.NETWORK
        }
        return path to failure
    }

    private fun reportDownloadResult(
        saved: Int,
        total: Int,
        cancelled: Boolean,
        failures: List<DownloadFailure>,
    ) {
        val noun = if (saved == 1) "file" else "files"
        if (cancelled) {
            Snackbar.make(
                recyclerView,
                if (saved == 0) "Download stopped" else "Download stopped · $saved $noun saved",
                Snackbar.LENGTH_LONG
            ).show()
            return
        }
        if (saved == total) {
            Snackbar.make(recyclerView, "$saved $noun saved to your phone", Snackbar.LENGTH_LONG)
                .setAction("View") { setMode(Mode.VIEW) }
                .setActionTextColor(color(R.color.gm_accent))
                .show()
            return
        }

        val progress = if (saved == 0) "" else "$saved of $total files were saved. "
        val stillSelected = "The rest are still selected."
        when {
            DownloadFailure.STORAGE in failures -> showMessageDialog(
                title = "Couldn't save to your phone",
                message = progress + "Your phone didn't allow the files to be saved — it's " +
                    "probably running out of storage. Free up some space, then try again. $stillSelected",
                primaryText = "Try again",
                onPrimary = { downloadSelected() },
                secondaryText = "Close"
            )
            DownloadFailure.SERVER in failures && DownloadFailure.NETWORK !in failures -> showMessageDialog(
                title = if (saved == 0) "Download failed" else "Some files didn't download",
                message = progress + "Your glasses couldn't send some files. Try again in a moment. $stillSelected",
                primaryText = "Try again",
                onPrimary = { downloadSelected() },
                secondaryText = "Close"
            )
            else -> showMessageDialog(
                title = if (saved == 0) "Lost connection to your glasses" else "Some files didn't download",
                message = progress + "Your glasses stopped responding. Keep them close to your phone " +
                    "and reconnect. $stillSelected",
                primaryText = "Reconnect",
                onPrimary = { startConnection() },
                secondaryText = "Close"
            )
        }
    }
    
    // Transfer Listener callbacks
    
    override fun onHotspotTriggered() {
        runOnUiThread {
            appendConnectionStep("Device switched to transfer mode")
            updateConnectionProgress(15)
            updateStatus("Glass Hotspot triggered. Connect to Glass WiFi...")
        }
    }
    
    override fun onConnected(ip: String) {
        runOnUiThread {
            appendConnectionStep("Device connected at $ip")
            updateConnectionProgress(50)
            updateStatus("Connected to Glass at $ip")
        }
    }
    
    override fun onDisconnected() {
        runOnUiThread {
            updateStatus("Disconnected from Glass")
        }
    }
    
    override fun onMediaListReceived(files: List<MediaFileInfo>) {
        // The list is handled from getMediaList()'s return value in loadRemoteListViaSocket().
        updateStatus("${files.size} files found on Glass")
    }
    
    override fun onDownloadProgress(fileName: String, progress: Int) {
        runOnUiThread { socketProgress?.invoke(progress) }
    }
    
    override fun onDownloadComplete(fileName: String, localPath: String) {
        updateStatus("Downloaded: $fileName")
    }
    
    override fun onDownloadError(fileName: String, error: String) {
        // Reported to the user once, in reportDownloadResult().
        Log.w(TAG, "Download failed: $fileName - $error")
        lastSocketError = error
    }
    
    override fun onError(error: String) {
        runOnUiThread {
            appendConnectionStep("Failed: $error")
            if (isConnecting) {
                connectionFailed(
                    "Couldn't connect to your glasses",
                    "Make sure your glasses are turned on and connected in the app, then try again."
                )
            } else {
                Log.w(TAG, "Transfer error: $error")
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        dismissProgressDialog()
        downloadJob?.cancel()
        connectionTimeoutJob?.cancel()
        mainScope.cancel()
        // Give the P2P network back before tearing down, so the phone returns to its
        // normal route and we don't leak the network request.
        releaseP2pNetwork()
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
                // WifiP2pHelper already auto-connects to a Glass device.
                appendConnectionStep("Device found. Connecting...")
                updateConnectionProgress(35)
                updateStatus("Found ${peers.size} devices. Auto-connecting...")
                discoveryRetryJob?.cancel()
                discoveryRetryJob = null
            } else {
                appendConnectionStep("No device found. Retrying...")
                updateConnectionProgress(20)
                updateStatus("No devices found. Retrying...")
                scheduleDiscoveryRetry(3000)
            }
        }
    }
    
    override fun onConnecting(deviceName: String) {
        runOnUiThread {
            appendConnectionStep("Connecting to $deviceName")
            updateConnectionProgress(45)
            updateStatus("🔗 Connecting to Glass device...")
        }
    }

    override fun onGlassConnected(glassIp: String) {
        runOnUiThread {
            discoveryRetryJob?.cancel()
            discoveryRetryJob = null
            appendConnectionStep("Device connected")
            appendConnectionStep("Verifying transfer server")
            updateConnectionProgress(50)
            updateStatus("📡 Glass IP detected: $glassIp")

            // Pin traffic to the Wi-Fi Direct network BEFORE probing. Probing first
            // tested the home-WiFi route, which can never reach the Glass subnet.
            bindToP2pNetworkThen {
                testPortsAndProceed(glassIp)
            }
        }
    }
    
    override fun onP2pDisconnected() {
        runOnUiThread {
            isConnectedToGlass = false
            if (isMediaListLoaded) {
                // Normal after the list is read; the list stays and the header says so.
                updateStatus("P2P disconnected after media list was loaded")
                render()
                return@runOnUiThread
            }
            updateStatus("P2P Disconnected from Glass")
            // While connecting this is often just the old group being torn down or a
            // negotiation retry. Keep looking; the connection timeout reports a real failure.
            if (isConnecting) scheduleDiscoveryRetry(2000)
        }
    }
    
    override fun onP2pError(message: String) {
        // --- CHANGE: If we're already connected (or downloading), ignore transient scanner errors ---
        if (isConnectedToGlass) {
            Log.w(TAG, "Ignored P2P Error because already connected: $message")
            return
        }

        runOnUiThread {
            appendConnectionStep("P2P error: $message")
            updateStatus("P2P Error: $message. Retrying...")
            scheduleDiscoveryRetry(1500)
        }
    }
    
    /**
     * Debounced discovery retry to avoid rapid reconnect loops.
     */
    private fun scheduleDiscoveryRetry(delayMs: Long) {
        if (!isConnecting || isConnectedToGlass || isMediaListLoaded) return
        if (discoveryRetryJob?.isActive == true) return

        discoveryRetryJob = mainScope.launch {
            delay(delayMs)
            if (!isConnectedToGlass) {
                updateStatus("Retrying discovery...")
                wifiP2pHelper.startDiscovery()
            }
        }
    }
    
}
