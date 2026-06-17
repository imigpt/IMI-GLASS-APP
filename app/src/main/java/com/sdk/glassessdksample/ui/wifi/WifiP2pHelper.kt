package com.sdk.glassessdksample.ui.wifi

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * WiFi P2P (WiFi Direct) Helper for Glass Connection
 * 
 * This class handles:
 * 1. WiFi P2P Discovery - Find Glass devices
 * 2. WiFi P2P Connection - Connect to Glass
 * 3. Get Glass IP from WifiP2pInfo.groupOwnerAddress
 * 
 * Glass acts as Group Owner (GO) and runs server on port 8888
 */
class WifiP2pHelper(private val context: Context) {
    
    companion object {
        private const val TAG = "WifiP2pHelper"
        private const val MAX_DISCOVERY_RETRIES = 2 // Limit retries to avoid blocking WiFi stack
        private const val GLASS_SERVER_PORT = 80
        private const val CONNECTION_TIMEOUT = 5000
        private val GLASS_SCAN_PORTS = listOf(80, 8888, 8899, 8080)
    }
    
    // WiFi P2P Manager
    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    
    // Broadcast receiver for P2P events
    private var receiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false
    
    // State
    private var isP2pEnabled = false
    private var connectedGlassIp: String? = null
    private var isConnecting = false

    // Retry state - save device for auto-reconnect after Internal Error
    private var pendingDevice: WifiP2pDevice? = null
    private var connectionRetryCount = 0
    private val MAX_CONNECTION_RETRIES = 3
    private var discoveryRetryCount = 0

    // Timeout watchdog: fires if CONNECTION_CHANGED never arrives after connect() succeeds
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectionTimeoutRunnable = Runnable {
        if (isConnecting && connectedGlassIp == null) {
            Log.w(TAG, "⏰ Connection timeout — broadcast never arrived, falling back to subnet scan")
            isConnecting = false
            tryGetClientIpFromGroup()
        }
    }

    // Callback
    private var callback: WifiP2pCallback? = null
    
    /**
     * Callback interface for P2P events
     */
    interface WifiP2pCallback {
        fun onP2pStateChanged(enabled: Boolean)
        fun onPeersDiscovered(peers: List<WifiP2pDevice>)
        fun onConnecting(deviceName: String) {}
        fun onGlassConnected(glassIp: String)
        fun onP2pDisconnected()
        fun onP2pError(message: String)
    }
    
    fun setCallback(cb: WifiP2pCallback) {
        callback = cb
    }
    
    /**
     * Initialize WiFi P2P Manager
     */
    fun initialize() {
        Log.i(TAG, "📱 Initializing WiFi P2P Manager...")
        
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        
        if (wifiP2pManager == null) {
            Log.e(TAG, "❌ WiFi P2P not supported on this device")
            callback?.onP2pError("WiFi Direct not supported")
            return
        }
        
        channel = wifiP2pManager?.initialize(context, context.mainLooper) { 
            Log.w(TAG, "⚠️ Channel disconnected")
        }
        
        if (channel == null) {
            Log.e(TAG, "❌ Failed to initialize channel")
            callback?.onP2pError("Failed to initialize WiFi Direct")
            return
        }
        
        Log.i(TAG, "✅ WiFi P2P Manager initialized")
    }
    
    /**
     * Register broadcast receiver for P2P events
     */
    fun registerReceiver() {
        if (isReceiverRegistered) return
        
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handleBroadcast(intent)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }
        
        isReceiverRegistered = true
        Log.i(TAG, "✅ Broadcast receiver registered")
    }
    
    /**
     * Handle P2P broadcast events
     */
    private fun handleBroadcast(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                isP2pEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                Log.i(TAG, "📶 P2P State: ${if (isP2pEnabled) "ENABLED" else "DISABLED"}")
                callback?.onP2pStateChanged(isP2pEnabled)
            }
            
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                Log.d(TAG, "👥 Peers changed")
                requestPeers()
            }
            
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                Log.d(TAG, "🔗 Connection changed")
                
                val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo
                }
                
                if (networkInfo?.isConnected == true) {
                    Log.i(TAG, "✅ P2P Connected!")
                    mainHandler.removeCallbacks(connectionTimeoutRunnable)
                    requestConnectionInfo()
                } else {
                    Log.w(TAG, "⚠️ P2P Disconnected")
                    connectedGlassIp = null
                    isConnecting = false
                    callback?.onP2pDisconnected()
                }
            }
            
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) as? WifiP2pDevice
                }
                Log.d(TAG, "📱 This device: ${device?.deviceName}")
            }
        }
    }
    
    /**
     * Start peer discovery to find Glass devices
     * CRITICAL FIX: Stop discovery first to clear "Internal error" states
     */
    fun startDiscovery() {
        if (!checkPermissions()) {
            callback?.onP2pError("Missing location permission for WiFi Direct")
            return
        }
        
        discoveryRetryCount = 0 // Reset retry counter for fresh start
        Log.i(TAG, "🔍 Starting peer discovery...")
        
        // CRITICAL: Stop any existing discovery first to avoid "Internal error"
        wifiP2pManager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "✅ Previous discovery stopped, starting fresh...")
                // Now start fresh discovery
                performDiscovery()
            }
            
            override fun onFailure(reason: Int) {
                Log.w(TAG, "⚠️ Could not stop previous discovery ($reason), trying anyway...")
                // Try starting anyway
                performDiscovery()
            }
        })
    }
    
    /**
     * Perform actual peer discovery with limited retries
     */
    private fun performDiscovery() {
        try {
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Discovery started (attempt ${discoveryRetryCount + 1})")
                    discoveryRetryCount = 0 // Reset on success
                }
                
                override fun onFailure(reason: Int) {
                    val reasonStr = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                        WifiP2pManager.BUSY -> "Framework busy"
                        WifiP2pManager.ERROR -> "Internal error"
                        else -> "Unknown ($reason)"
                    }
                    Log.w(TAG, "⚠️ Discovery failed: $reasonStr (attempt ${discoveryRetryCount + 1}/$MAX_DISCOVERY_RETRIES)")
                    
                    // ✅ Limited retry with backoff to avoid blocking WiFi framework
                    if (discoveryRetryCount < MAX_DISCOVERY_RETRIES) {
                        discoveryRetryCount++
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            Log.i(TAG, "🔄 Retrying discovery...")
                            performDiscovery()
                        }, 1500) // 1.5 second backoff
                    } else {
                        Log.w(TAG, "❌ P2P discovery exhausted, falling back to hotspot mode")
                        discoveryRetryCount = 0 // Reset for next attempt
                        callback?.onP2pError("P2P skipped after $MAX_DISCOVERY_RETRIES attempts: $reasonStr")
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
            callback?.onP2pError("Permission denied for discovery")
        }
    }
    
    /**
     * Stop peer discovery
     * Public method to allow explicit stop from Activity
     */
    fun stopDiscovery() {
        try {
            wifiP2pManager?.stopPeerDiscovery(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "✅ Discovery stopped")
                }
                
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "⚠️ Stop discovery failed: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception stopping discovery: ${e.message}")
        }
    }
    
    /**
     * Request discovered peers
     */
    private fun requestPeers() {
        try {
            wifiP2pManager?.requestPeers(channel) { peers: WifiP2pDeviceList? ->
                val deviceList = peers?.deviceList?.toList() ?: emptyList()
                Log.i(TAG, "👥 Found ${deviceList.size} peers:")
                
                // Filter Glass devices
                val glassDevices = deviceList.filter { device ->
                    val name = device.deviceName.lowercase()
                    val isGlass = name.contains("m01") ||
                                  name.contains("glass") ||
                                  name.contains("heycyan") ||
                                  name.contains("cyan") ||
                                  name.contains("cy01") ||
                                  name.contains("bond") ||
                                  name.contains("sanvnet") ||  // SANVNET GS4 MAX device
                                  name.contains("gs4") ||      // GS4 variant
                                  name.contains("android")     // Generic P2P fallback
                    Log.d(TAG, "  - ${device.deviceName} (${device.deviceAddress}) ${if (isGlass) "👓 GLASS" else ""}")
                    isGlass
                }

                // Pick the best candidate: matched Glass device first, else first peer
                val targetDevice = glassDevices.firstOrNull() ?: deviceList.firstOrNull()

                callback?.onPeersDiscovered(if (glassDevices.isNotEmpty()) glassDevices else deviceList)

                // ✅ AUTO-CONNECT: only if not already connecting
                if (targetDevice != null && !isConnecting) {
                    Log.i(TAG, "🔗 AUTO-CONNECT: Connecting to ${targetDevice.deviceName}...")
                    connectToDevice(targetDevice)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
        }
    }
    
    /**
     * Connect to a specific Glass device
     * CRITICAL FIX: Auto-retry on Internal Error with saved device
     */
    fun connectToDevice(device: WifiP2pDevice) {
        if (isConnecting) {
            Log.w(TAG, "⚠️ Already connecting, skipping...")
            return
        }
        
        isConnecting = true
        pendingDevice = device  // Save for retry on Internal Error
        if (connectionRetryCount == 0) {
            connectionRetryCount = 0  // Reset only on first attempt
        }
        Log.i(TAG, "🔗 Connecting to: ${device.deviceName} (${device.deviceAddress})")
        
        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            // Let Glass be the Group Owner
            groupOwnerIntent = 0  // 0 = don't want to be GO, 15 = want to be GO
            
            // WPS Push Button Config (simplest, no PIN)
            wps.setup = android.net.wifi.WpsInfo.PBC
        }
        
        // --- FIX: Connect directly without stopping discovery ---
        // Stopping discovery clears the peer list and causes "Internal error"
        // Android handles discovery internally during connect
        performConnect(config, device)
    }
    
    /**
     * Actual connection logic with retry on Framework Busy
     */
    private fun performConnect(config: WifiP2pConfig, device: WifiP2pDevice) {
        // 500ms delay to let framework breathe
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Log.i(TAG, "✅ Connection request sent")
                        callback?.onConnecting(device.deviceName)
                        // Watchdog: if broadcast never fires in 15s, fall back to scan
                        mainHandler.removeCallbacks(connectionTimeoutRunnable)
                        mainHandler.postDelayed(connectionTimeoutRunnable, 15_000)
                    }
                    
                    override fun onFailure(reason: Int) {
                        isConnecting = false
                        val reasonStr = when (reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                            WifiP2pManager.BUSY -> "Framework busy"
                            WifiP2pManager.ERROR -> "Internal error"
                            else -> "Unknown ($reason)"
                        }
                        Log.e(TAG, "❌ Connection failed: $reasonStr")
                        
                        // Retry on Framework Busy OR Internal Error (Android sometimes needs retries)
                        if ((reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR) && connectionRetryCount < MAX_CONNECTION_RETRIES) {
                            connectionRetryCount++
                            Log.w(TAG, "⚠️ $reasonStr. Retry $connectionRetryCount/$MAX_CONNECTION_RETRIES in 2 sec...")
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                // Retry directly with saved device instead of re-discovering
                                pendingDevice?.let { savedDevice ->
                                    isConnecting = false  // Reset so connectToDevice allows retry
                                    connectToDevice(savedDevice)
                                } ?: run {
                                    callback?.onP2pError("Connection failed: No device to retry")
                                }
                            }, 2000)
                        } else {
                            mainHandler.removeCallbacks(connectionTimeoutRunnable)
                            pendingDevice = null  // Clear on final failure
                            callback?.onP2pError("Connection failed: $reasonStr (after $connectionRetryCount retries)")
                        }
                    }
                })
            } catch (e: SecurityException) {
                isConnecting = false
                mainHandler.removeCallbacks(connectionTimeoutRunnable)
                Log.e(TAG, "Security exception: ${e.message}")
                callback?.onP2pError("Permission denied for connection")
            }
        }, 500) // 500ms wait for framework
    }
    
    /**
     * Request connection info to get Group Owner IP
     */
    private fun requestConnectionInfo() {
        wifiP2pManager?.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
            if (info == null) {
                Log.w(TAG, "No connection info")
                return@requestConnectionInfo
            }
            
            Log.i(TAG, "📋 Connection Info:")
            Log.i(TAG, "  - groupFormed: ${info.groupFormed}")
            Log.i(TAG, "  - isGroupOwner: ${info.isGroupOwner}")
            Log.i(TAG, "  - groupOwnerAddress: ${info.groupOwnerAddress?.hostAddress}")
            
            if (info.groupFormed) {
                isConnecting = false
                pendingDevice = null  // Clear retry state on success
                connectionRetryCount = 0
                
                if (info.isGroupOwner) {
                    // Phone is GO - try to get client IP from group info
                    Log.w(TAG, "⚠️ Phone is Group Owner - Glass is client")
                    
                    // Try to get client IP from P2P group directly
                    tryGetClientIpFromGroup()
                } else {
                    // Glass is GO - use groupOwnerAddress
                    val glassIp = info.groupOwnerAddress?.hostAddress
                    Log.i(TAG, "✅ Glass is Group Owner at: $glassIp")
                    
                    if (glassIp != null) {
                        connectedGlassIp = glassIp
                        callback?.onGlassConnected(glassIp)
                    } else {
                        callback?.onP2pError("Could not get Glass IP")
                    }
                }
            }
        }
    }
    
    /**
     * Try to get client IP when phone is Group Owner
     * First checks ARP table, then falls back to scanning
     */
    private fun tryGetClientIpFromGroup() {
        Log.i(TAG, "🔍 Trying to detect Glass IP from current connection...")
        android.os.Handler(context.mainLooper).post {
            callback?.onConnecting("Glass (scanning subnet)")
        }
        // Start scanning (will check ARP first, then scan)
        scanForGlassAsClient()
    }
    
    /**
     * When phone is Group Owner, scan for Glass as P2P client
     * FULL PARALLEL SCAN: 1-254 (ARP blocked on Android 10+)
     * Fast scan using coroutines - completes in ~1 second
     */
    private fun scanForGlassAsClient() {
        Log.i(TAG, "🔍 Starting FULL parallel scan (192.168.49.1-254)...")
        
        Thread {
            // --- Try ARP first (will fail on Android 10+ but worth trying) ---
            val arpIp = getIpFromArpTable()
            if (arpIp != null && checkGlassServer(arpIp)) {
                Log.i(TAG, "🎯 Found via ARP: $arpIp")
                connectedGlassIp = arpIp
                android.os.Handler(context.mainLooper).post {
                    callback?.onGlassConnected(arpIp)
                }
                return@Thread
            }
            
            // --- FULL SUBNET SCAN (1-254) in parallel ---
            // Glass can get ANY IP like .226, .99, etc.
            val subnet = "192.168.49"
            val foundIp = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val latch = java.util.concurrent.CountDownLatch(254)
            
            // Launch 254 threads to scan ALL possible IPs at once (tries multiple ports)
            for (i in 1..254) {
                Thread {
                    if (foundIp.get() == null) {  // Stop if already found
                        val ip = "$subnet.$i"
                        for (port in GLASS_SCAN_PORTS) {
                            if (foundIp.get() != null) break
                            try {
                                val socket = Socket()
                                socket.soTimeout = 300  // 300ms timeout (fast!)
                                socket.connect(InetSocketAddress(ip, port), 300)
                                socket.close()
                                if (foundIp.compareAndSet(null, ip)) {
                                    Log.i(TAG, "✅ FOUND GLASS AT: $ip:$port")
                                }
                                break
                            } catch (e: Exception) {
                                // try next port
                            }
                        }
                    }
                    latch.countDown()
                }.start()
            }
            
            // Wait for all scans (max 2 seconds)
            try {
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Scan timeout")
            }
            
            val glassIp = foundIp.get()
            if (glassIp != null) {
                connectedGlassIp = glassIp
                android.os.Handler(context.mainLooper).post {
                    callback?.onGlassConnected(glassIp)
                }
            } else {
                Log.w(TAG, "❌ Glass not found in subnet (1-254 scan complete)")
                android.os.Handler(context.mainLooper).post {
                    callback?.onP2pError("Glass not found. Ensure Glass P2P is active.")
                }
            }
        }.start()
    }
    
    /**
     * Check if Glass server is running on given IP (tries multiple ports)
     */
    private fun checkGlassServer(ip: String): Boolean {
        for (port in GLASS_SCAN_PORTS) {
            try {
                val socket = Socket()
                socket.soTimeout = 500
                socket.connect(InetSocketAddress(ip, port), 500)
                socket.close()
                return true
            } catch (e: Exception) {
                // try next port
            }
        }
        return false
    }
    
    /**
     * Read ARP table to find connected P2P device IP instantly
     */
    private fun getIpFromArpTable(): String? {
        try {
            val br = java.io.BufferedReader(java.io.FileReader("/proc/net/arp"))
            var line: String?
            while (br.readLine().also { line = it } != null) {
                val split = line!!.split("\\s+".toRegex())
                if (split.size >= 6) {
                    val ip = split[0]
                    val device = split[5]
                    // Look for P2P interface entries
                    if (device.contains("p2p") && ip.startsWith("192.168.49")) {
                        Log.d(TAG, "ARP entry: $ip on $device")
                        br.close()
                        return ip
                    }
                }
            }
            br.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ARP: ${e.message}")
        }
        return null
    }
    
    /**
     * Get currently connected Glass IP
     */
    fun getGlassIp(): String? = connectedGlassIp
    
    /**
     * Remove any existing/stale P2P group before a fresh connection attempt.
     *
     * Glass keeps the previous group alive when removeGroup() fails with BUSY,
     * which blocks the next connection from forming. Calling this first clears
     * a leftover group so discovery → connect can succeed on repeat captures.
     */
    fun removeExistingGroup() {
        try {
            wifiP2pManager?.requestGroupInfo(channel) { group ->
                if (group != null) {
                    Log.i(TAG, "🧹 Removing stale P2P group before reconnect: ${group.networkName}")
                    wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            Log.i(TAG, "✅ Stale group removed")
                            connectedGlassIp = null
                        }
                        override fun onFailure(reason: Int) {
                            Log.w(TAG, "⚠️ Could not remove stale group: $reason")
                        }
                    })
                } else {
                    Log.d(TAG, "No existing P2P group to remove")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "removeExistingGroup security exception: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "removeExistingGroup failed: ${e.message}")
        }
    }

    /**
     * Disconnect from current P2P group
     */
    fun disconnect() {
        try {
            wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.i(TAG, "✅ Disconnected from group")
                    connectedGlassIp = null
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "⚠️ Failed to disconnect: $reason")
                }
            })
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception: ${e.message}")
        }
    }
    
    /**
     * Unregister broadcast receiver
     */
    fun unregisterReceiver() {
        if (isReceiverRegistered && receiver != null) {
            try {
                context.unregisterReceiver(receiver)
                isReceiverRegistered = false
                Log.i(TAG, "✅ Broadcast receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
    }
    
    /**
     * Clean up resources
     */
    fun cleanup() {
        mainHandler.removeCallbacks(connectionTimeoutRunnable)
        disconnect()
        unregisterReceiver()
        wifiP2pManager = null
        channel = null
    }
    
    /**
     * Check required permissions
     */
    private fun checkPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        
        val nearbyDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        
        return fineLocation || nearbyDevices
    }
    
    /**
     * Check if P2P is enabled
     */
    fun isP2pEnabled(): Boolean = isP2pEnabled
    
    /**
     * Check if connected to Glass
     */
    fun isConnected(): Boolean = connectedGlassIp != null
    
    /**
     * Try to get Glass IP from current WiFi connection (for manual connection mode)
     * Use this when user manually connects to Glass WiFi/P2P
     */
    fun tryGetGlassIpFromCurrentConnection(): String? {
        Log.i(TAG, "🔍 Trying to detect Glass IP from current connection...")
        
        // First check if we're on P2P network
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.name.contains("p2p") || iface.name == "wlan0") {
                    val addresses = iface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val addr = addresses.nextElement()
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            val phoneIp = addr.hostAddress ?: continue
                            Log.d(TAG, "  Interface ${iface.name}: $phoneIp")
                            
                            if (phoneIp.startsWith("192.168.49.")) {
                                // We're on P2P network
                                if (phoneIp == "192.168.49.1") {
                                    // Phone is GO, scan for Glass
                                    Log.i(TAG, "📱 Phone is GO, scanning for Glass...")
                                    return scanGlassIpSync()
                                } else {
                                    // Phone is client, Glass is GO at 192.168.49.1
                                    Log.i(TAG, "📱 Phone is client, Glass at 192.168.49.1")
                                    return "192.168.49.1"
                                }
                            } else if (phoneIp.startsWith("192.168.43.") || 
                                       phoneIp.startsWith("192.168.42.")) {
                                // Hotspot mode - Glass is at x.x.x.1
                                val glassIp = phoneIp.substring(0, phoneIp.lastIndexOf('.')) + ".1"
                                Log.i(TAG, "📱 Hotspot mode, Glass at $glassIp")
                                return glassIp
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting IP: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Synchronously scan for Glass IP when phone is GO
     */
    private fun scanGlassIpSync(): String? {
        val ipsToScan = listOf("192.168.49.1") + (2..20).map { "192.168.49.$it" }

        for (ip in ipsToScan) {
            for (port in GLASS_SCAN_PORTS) {
                try {
                    val socket = Socket()
                    socket.soTimeout = 1500
                    socket.connect(InetSocketAddress(ip, port), 1500)
                    socket.close()
                    Log.i(TAG, "✅ Found Glass server at $ip:$port")
                    connectedGlassIp = ip
                    return ip
                } catch (e: Exception) {
                    // try next port
                }
            }
        }

        Log.w(TAG, "❌ Glass server not found")
        return null
    }
}

