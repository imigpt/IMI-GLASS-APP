package com.sdk.glassessdksample.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanResult
import android.content.*
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import com.sdk.glassessdksample.R
import com.sdk.glassessdksample.databinding.ActivityDeviceBindBinding
import com.xiasuhuei321.loadingdialog.view.LoadingDialog
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DeviceBindActivity : BaseActivity() {

    private lateinit var binding: ActivityDeviceBindBinding
    private lateinit var adapter: DeviceListAdapter
    private val deviceList = mutableListOf<SmartWatch>()

    private var scanSize = 0
    private var loadingDialog: LoadingDialog? = null
    private var autoConnectDialog: AlertDialog? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var connectedDeviceAddress: String? = null
    private var headsetProxy: BluetoothHeadset? = null
    private var hfpReceiverRegistered = false
    private var bondStateReceiverRegistered = false

    private val scanStopRunnable = Runnable {
        BleScannerHelper.getInstance().stopScan(this)
        binding.startScan.text = "Start Scan"
    }

    private val bleScanCallback = BleCallback()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @SuppressLint("MissingPermission")
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            Log.d("DeviceBindActivity", "Profile proxy connected for profile: $profile")
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = proxy as BluetoothHeadset
                val classicDevice = connectedDeviceAddress?.let { BluetoothAdapter.getDefaultAdapter().getRemoteDevice(it) }
                if (classicDevice != null) {
                    mainHandler.post { attemptHfpConnection(classicDevice) }
                } else {
                    Log.e("DeviceBindActivity", "Cannot attempt HFP connection, connectedDeviceAddress is null.")
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                headsetProxy = null
                Log.w("DeviceBindActivity", "Headset profile proxy disconnected.")
            }
        }
    }

    private val headsetConnectionReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                Log.d("DeviceBindActivity", "HFP connection state changed to $state for device ${device?.address}")

                if (device?.address == connectedDeviceAddress && state == BluetoothProfile.STATE_CONNECTED) {
                    Log.d("DeviceBindActivity", "✅ Headset Profile Connected via BroadcastReceiver")
                    handleHfpConnectionSuccess()
                }
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)

                if (device != null && device.address == connectedDeviceAddress && bondState == BluetoothDevice.BOND_BONDED) {
                    Log.d("DeviceBindActivity", "✅ Device bonded. Now getting HFP proxy.")
                    cleanUpReceivers() // Clean up bond receiver
                    getHeadsetProxyAndConnect(device) // Proceed to connect HFP
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceBindBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    override fun setupViews() {
        adapter = DeviceListAdapter(this, deviceList)
        binding.deviceRcv.layoutManager = LinearLayoutManager(this)
        binding.deviceRcv.adapter = adapter
        binding.titleBar.tvTitle.text = "Scan Smart Glass"
        binding.titleBar.ivNavigateBefore.setOnClickListener { finish() }

        adapter.setOnItemClickListener { _, _, position ->
            mainHandler.removeCallbacks(scanStopRunnable)
            val device = deviceList[position]
            
            // 🆕 Show confirmation dialog before pairing
            showPairConfirmationDialog(device)
        }

        binding.startScan.setOnClickListener { startScanning() }
        
        // 🆕 Check for auto-connect
        mainHandler.postDelayed({ checkAutoConnect() }, 500)
    }

    override fun onResume() {
        super.onResume()
        EventBus.getDefault().register(this)
        requestPermissions()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(scanStopRunnable)
        EventBus.getDefault().unregister(this)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: BluetoothEvent) {
        if (event.type == BluetoothEvent.EventType.CONNECTED) {
            loadingDialog?.close()
            Log.d("DeviceBindActivity", "✅ BLE Connected. Starting classic connection flow...")
            Toast.makeText(this, "✅ Glass Connected (Data)", Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed({ connectedDeviceAddress?.let { startClassicConnectionFlow(it) } }, 500)
        }
    }

    private fun handleHfpConnectionSuccess() {
        mainHandler.removeCallbacksAndMessages(null)
        Log.d("DeviceBindActivity", "🎧 HFP Connected, configuring audio...")
        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
            mode = AudioManager.MODE_IN_CALL
            startBluetoothSco()
            isBluetoothScoOn = true
        }
        Toast.makeText(this, "🎧 Glass mic ready!", Toast.LENGTH_SHORT).show()
        Log.d("DeviceBindActivity", "✅ Both BLE and Audio connections active")
        cleanUpReceivers()
        mainHandler.postDelayed({ finish() }, 500)
    }

    private fun cleanUpReceivers() {
        mainHandler.removeCallbacksAndMessages(null)
        if (hfpReceiverRegistered) {
            try { unregisterReceiver(headsetConnectionReceiver) } catch (e: Exception) {}
            hfpReceiverRegistered = false
        }
        if (bondStateReceiverRegistered) {
            try { unregisterReceiver(bondStateReceiver) } catch (e: Exception) {}
            bondStateReceiverRegistered = false
        }
    }
    
    /**
     * 🆕 Show confirmation dialog before pairing with glasses
     * This ensures pairing ONLY happens through app, not system settings
     */
    private fun showPairConfirmationDialog(device: SmartWatch) {
        AlertDialog.Builder(this)
            .setTitle("Pair with Glasses?")
            .setMessage("Do you want to connect to ${device.deviceName}?\n\nThis will pair the glasses with your phone for:\n• Voice AI Assistant\n• Camera & Media\n• Smart Features")
            .setPositiveButton("Pair") { dialog, _ ->
                dialog.dismiss()
                proceedWithPairing(device)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "Pairing cancelled", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 🆕 Proceed with pairing after user confirmation
     */
    private fun proceedWithPairing(device: SmartWatch) {
        // ✅ Check Bluetooth state first
        if (!isBluetoothEnabled()) {
            Log.w("DeviceBindActivity", "⚠️ Bluetooth is OFF, cannot pair")
            showBluetoothOffDialog(device.deviceAddress, device.deviceName)
            return
        }
        
        connectedDeviceAddress = device.deviceAddress
        
        // Save device info for auto-connect next time
        savePairedDevice(device)
        
        // Start BLE connection
        BleOperateManager.getInstance().connectDirectly(device.deviceAddress)
        loadingDialog = LoadingDialog(this)
        loadingDialog?.setLoadingText(getString(R.string.text_22))?.show()
        
        Log.d("DeviceBindActivity", "✅ User confirmed pairing with ${device.deviceName}")
    }
    
    /**
     * 🆕 Save paired device for auto-connect
     */
    private fun savePairedDevice(device: SmartWatch) {
        val prefs = getSharedPreferences("glass_pairing", MODE_PRIVATE)
        prefs.edit().apply {
            putString("paired_device_name", device.deviceName)
            putString("paired_device_address", device.deviceAddress)
            putLong("paired_timestamp", System.currentTimeMillis())
            apply()
        }
        Log.d("DeviceBindActivity", "💾 Saved device for auto-connect: ${device.deviceName}")
    }
    
    /**
     * 🆕 Check if there's a previously paired device for auto-connect
     */
    private fun checkAutoConnect() {
        val prefs = getSharedPreferences("glass_pairing", MODE_PRIVATE)
        val pairedAddress = prefs.getString("paired_device_address", null)
        val pairedName = prefs.getString("paired_device_name", null)
        
        if (pairedAddress != null && pairedName != null) {
            Log.d("DeviceBindActivity", "📱 Found saved device: $pairedName")
            
            if (isFinishing || isDestroyed) return

            // Try auto-connect
            autoConnectDialog = AlertDialog.Builder(this)
                .setTitle("Auto-Connect?")
                .setMessage("Connect to previously paired glasses:\n$pairedName")
                .setPositiveButton("Connect") { dialog, _ ->
                    dialog.dismiss()
                    attemptAutoConnect(pairedAddress, pairedName)
                }
                .setNegativeButton("Scan New") { dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        }
    }
    
    /**
     * 🆕 Attempt auto-connect to saved device
     */
    private fun attemptAutoConnect(address: String, name: String) {
        // ✅ Check if activity is still valid
        if (isFinishing || isDestroyed) return
        
        // ✅ Check Bluetooth state first
        if (!isBluetoothEnabled()) {
            Log.w("DeviceBindActivity", "⚠️ Bluetooth is OFF, cannot connect")
            showBluetoothOffDialog(address, name)
            return
        }
        
        connectedDeviceAddress = address
        BleOperateManager.getInstance().connectDirectly(address)
        loadingDialog = LoadingDialog(this)
        loadingDialog?.setLoadingText("Connecting to $name...")?.show()
        
        // ⏱️ Set timeout for connection (increased from 10s to 30s for slower devices)
        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                loadingDialog?.close()
                showConnectionTimeoutDialog(address, name)
            }
        }, 30000) // 30 second timeout (previously 10s - too quick)
        
        Log.d("DeviceBindActivity", "🔄 Auto-connecting to: $name")
    }

    @SuppressLint("MissingPermission")
    private fun startClassicConnectionFlow(deviceMac: String) {
        val classicDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceMac)
        Log.d("DeviceBindActivity", "Starting classic flow. Bond state: ${classicDevice.bondState}")
        when (classicDevice.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Log.d("DeviceBindActivity", "Device already bonded.")
                getHeadsetProxyAndConnect(classicDevice)
            }
            BluetoothDevice.BOND_NONE -> {
                Log.d("DeviceBindActivity", "Device not bonded. Starting pairing...")
                Toast.makeText(this, "Pairing with glasses... Please confirm.", Toast.LENGTH_LONG).show()
                registerBondStateReceiver()
                if (!classicDevice.createBond()) {
                    Log.e("DeviceBindActivity", "createBond() failed.")
                    cleanUpReceivers()
                }
            }
            BluetoothDevice.BOND_BONDING -> {
                Log.d("DeviceBindActivity", "Device is bonding. Waiting for result...")
                registerBondStateReceiver()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getHeadsetProxyAndConnect(classicDevice: BluetoothDevice) {
        Log.d("DeviceBindActivity", "Getting Headset profile proxy for ${classicDevice.address}")
        BluetoothAdapter.getDefaultAdapter().getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
    }

    @SuppressLint("MissingPermission")
    private fun attemptHfpConnection(classicDevice: BluetoothDevice) {
        val headset = headsetProxy ?: run {
            Log.e("DeviceBindActivity", "Headset proxy is null, cannot attempt connection.")
            return
        }
        val state = headset.getConnectionState(classicDevice)
        Log.d("DeviceBindActivity", "Attempting HFP connection. Current state: $state")
        when (state) {
            BluetoothProfile.STATE_CONNECTED -> handleHfpConnectionSuccess()
            BluetoothProfile.STATE_CONNECTING -> {
                Log.d("DeviceBindActivity", "HFP is connecting. Waiting...")
                registerHfpReceiverAndSetTimeout()
            }
            else -> {
                registerHfpReceiverAndSetTimeout()
                try {
                    val connectMethod = headset.javaClass.getMethod("connect", BluetoothDevice::class.java)
                    val success = connectMethod.invoke(headset, classicDevice) as? Boolean ?: false
                    if (success) Log.d("DeviceBindActivity", "HFP connect command issued successfully.")
                    else {
                        Log.e("DeviceBindActivity", "HFP connect command failed immediately.")
                        cleanUpReceivers()
                        finish()
                    }
                } catch (e: Exception) {
                    Log.e("DeviceBindActivity", "Error invoking HFP connect method", e)
                    cleanUpReceivers()
                    finish()
                }
            }
        }
    }

    private fun registerHfpReceiverAndSetTimeout() {
        if (!hfpReceiverRegistered) {
            registerReceiver(headsetConnectionReceiver, IntentFilter(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED))
            hfpReceiverRegistered = true
        }
        mainHandler.postDelayed({
            if (hfpReceiverRegistered) {
                Log.w("DeviceBindActivity", "HFP connection timed out.")
                cleanUpReceivers()
                finish()
            }
        }, 15000)
    }

    private fun registerBondStateReceiver() {
        if (!bondStateReceiverRegistered) {
            registerReceiver(bondStateReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            bondStateReceiverRegistered = true
        }
    }

    private fun playNotificationSound() {
        try {
            RingtoneManager.getRingtone(applicationContext, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        XXPermissions.with(this).permission(perms).request(object : OnPermissionCallback {
            override fun onGranted(list: MutableList<String>, all: Boolean) { if (all) setupViews() }
            override fun onDenied(list: MutableList<String>, never: Boolean) {
                if (never) XXPermissions.startPermissionActivity(this@DeviceBindActivity, list)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!isBluetoothEnabled()) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        deviceList.clear()
        adapter.notifyDataSetChanged()
        scanSize = 0
        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(this, null, bleScanCallback)
        binding.startScan.text = "Stop Scan"
        mainHandler.postDelayed(scanStopRunnable, 15000)
    }
    
    private fun isBluetoothEnabled(): Boolean = BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
    
    /**
     * 🆕 Show dialog when Bluetooth is OFF
     */
    private fun showBluetoothOffDialog(address: String, name: String) {
        // Check if activity is finishing to prevent window leak
        if (isFinishing || isDestroyed) return
        
        AlertDialog.Builder(this)
            .setTitle("Bluetooth is OFF")
            .setMessage("Please turn ON Bluetooth to connect to $name")
            .setPositiveButton("Turn ON") { dialog, _ ->
                dialog.dismiss()
                // Open Bluetooth settings
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                
                // Retry connection after delay
                mainHandler.postDelayed({
                    // Check if activity is still valid before attempting connection
                    if (!isFinishing && !isDestroyed && isBluetoothEnabled()) {
                        attemptAutoConnect(address, name)
                    } else if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, "Please enable Bluetooth manually", Toast.LENGTH_LONG).show()
                    }
                }, 2000)
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 🆕 Show dialog when connection times out
     */
    private fun showConnectionTimeoutDialog(address: String, name: String) {
        if (isFinishing || isDestroyed) return
        
        AlertDialog.Builder(this)
            .setTitle("Connection Timeout")
            .setMessage("Could not connect to $name.\n\nMake sure glasses are:\n• Powered ON\n• In range\n• Not connected to another device")
            .setPositiveButton("Retry") { dialog, _ ->
                dialog.dismiss()
                attemptAutoConnect(address, name)
            }
            .setNegativeButton("Scan New") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    inner class BleCallback : ScanWrapperCallback {
        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (device == null || device.name.isNullOrEmpty()) return
            val newDevice = SmartWatch(device.name, device.address, rssi = rssi)
            if (deviceList.any { it.deviceAddress == newDevice.deviceAddress }) return
            deviceList.add(newDevice)
            deviceList.sortByDescending { it.rssi }
            adapter.notifyDataSetChanged()
            if (++scanSize > 30) BleScannerHelper.getInstance().stopScan(this@DeviceBindActivity)
        }
        override fun onStart() {}
        override fun onStop() {}
        override fun onScanFailed(errorCode: Int) {}
        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {}
        override fun onBatchScanResults(results: MutableList<ScanResult>?) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dismiss loading dialog to prevent window leak
        loadingDialog?.close()
        loadingDialog = null
        if (autoConnectDialog?.isShowing == true) {
            autoConnectDialog?.dismiss()
        }
        autoConnectDialog = null

        cleanUpReceivers()
        EventBus.getDefault().unregister(this)
        headsetProxy?.let { BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.HEADSET, it) }
    }
}
