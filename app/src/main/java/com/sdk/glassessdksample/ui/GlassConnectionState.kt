package com.sdk.glassessdksample.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * The single answer to "are the glasses connected right now?".
 *
 * Before this existed there were five independent, non-equivalent notions of
 * "connected" scattered across the app (Mark1MainActivity.isGlassConnected,
 * MainActivity.checkBLEConnection, PreferredAudioDeviceResolver, ScoConnectionHelper
 * and MyBluetoothReceiver). None of them gated wake-word detection, so the detector
 * armed and held the microphone — plus a wake lock and a permanent "IMI is
 * listening" notification — with nothing to talk through.
 *
 * The answer here is deliberately the UNION of those sources, never a narrowing.
 * Being stricter than the old checks would make the app go silently deaf on any
 * phone whose state one particular source cannot see, which is a far worse failure
 * than occasionally counting a non-glasses accessory. Routing to the *wrong*
 * device is a separate concern already handled by
 * [PreferredAudioDeviceResolver.hasMultipleBluetoothAudioDevicesConnected].
 *
 * Exception — Mark 1: with Mark 1 selected, only devices named like "F-16" count
 * (see [GlassDeviceFilter]). Every source below is then checked by device name, and
 * the name-less "is anything on this profile?" probe is skipped.
 */
object GlassConnectionState {
    private const val TAG = "GlassConnState"

    /** Why [isConnected] said no, for the gate UI and for logging. */
    enum class Reason {
        CONNECTED,
        BLUETOOTH_OFF,
        NO_DEVICE,
        /** Mark 1: a Bluetooth device is connected, but its name isn't a Mark 1's. */
        WRONG_DEVICE,
    }

    // The bonded-device reflection loop in step 4 is not cheap, and start() /
    // rearmWakeWord() fire in bursts. Serve a very short cache; any connect or
    // disconnect invalidates it immediately so we never act on stale state.
    private const val CACHE_TTL_MS = 1_000L

    @Volatile private var cachedResult: Boolean? = null
    @Volatile private var cachedAtMs = 0L

    private val listeners = mutableListOf<(Boolean) -> Unit>()

    fun addListener(l: (Boolean) -> Unit) = synchronized(listeners) { listeners.add(l); Unit }
    fun removeListener(l: (Boolean) -> Unit) = synchronized(listeners) { listeners.remove(l); Unit }

    /**
     * True when something we can talk through is connected.
     *
     * Resolution order, first positive wins:
     *  1. Bluetooth adapter off              -> false immediately
     *  2. A Bluetooth audio endpoint (SCO/A2DP), preferring the paired glasses
     *  3. The vendor SDK's own GATT link (Mark 2)
     *  4. Classic profile / GATT / bonded-device reflection cascade
     */
    fun isConnected(context: Context): Boolean {
        cachedResult?.let { cached ->
            if (System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS) return cached
        }
        val result = compute(context)
        cachedResult = result
        cachedAtMs = System.currentTimeMillis()
        return result
    }

    /** Distinguishes "Bluetooth is off" from "nothing connected", for the gate UI. */
    fun describe(context: Context): Reason {
        val adapter = adapterOrNull(context)
        if (adapter == null || !adapter.isEnabled) return Reason.BLUETOOTH_OFF
        if (isConnected(context)) return Reason.CONNECTED
        return if (otherConnectedDeviceName(context) != null) Reason.WRONG_DEVICE else Reason.NO_DEVICE
    }

    /**
     * Mark 1 only: the name of a connected Bluetooth device that was rejected
     * because it isn't a Mark 1 (e.g. "boAt Stone 650"), so the gate can say so.
     */
    fun otherConnectedDeviceName(context: Context): String? {
        if (!GlassDeviceFilter.isActive(context)) return null
        val adapter = adapterOrNull(context) ?: return null
        return connectedDeviceNames(context, adapter).firstOrNull { !GlassDeviceFilter.isMark1Name(it) }
    }

    /**
     * Called when the app observes the glasses connecting. Backfills the pairing
     * record so later routing can match on address instead of relying on
     * [PreferredAudioDeviceResolver.findGlasses]'s "only one candidate" fallback.
     */
    fun onConnected(context: Context, device: BluetoothDevice? = null) {
        invalidate()
        if (device != null) {
            val address = try { device.address } catch (e: SecurityException) { null }
            val name = try { device.name } catch (e: SecurityException) { null }
            if (!GlassDeviceFilter.accepts(context, name)) {
                // Earbuds, a speaker… connected. Not the glasses: don't record them
                // as paired, and report whatever the real state is.
                Log.i(TAG, "Ignoring connected device \"$name\" — not a Mark 1 (F-16 style) name")
                notifyListeners(isConnected(context))
                return
            }
            try {
                PreferredAudioDeviceResolver.rememberPairedGlassesIfUnset(context, address, name)
            } catch (e: Exception) {
                Log.w(TAG, "Could not backfill pairing record: ${e.message}")
            }
        }
        notifyListeners(true)
    }

    fun onDisconnected(context: Context, device: BluetoothDevice? = null) {
        invalidate()
        notifyListeners(false)
    }

    /** Drops the cache and re-reads from the system, notifying listeners of the result. */
    fun refreshFromSystem(context: Context) {
        invalidate()
        notifyListeners(isConnected(context))
    }

    fun invalidate() {
        cachedResult = null
        cachedAtMs = 0L
    }

    private fun notifyListeners(connected: Boolean) {
        val snapshot = synchronized(listeners) { listeners.toList() }
        snapshot.forEach {
            try { it(connected) } catch (e: Exception) {
                Log.w(TAG, "Connection listener threw: ${e.message}")
            }
        }
    }

    private fun compute(context: Context): Boolean {
        val adapter = adapterOrNull(context)
        if (adapter == null || !adapter.isEnabled) return false

        if (GlassDeviceFilter.isActive(context)) return computeMark1(context, adapter)

        // 2. A Bluetooth audio endpoint. connectedBluetoothAudioDevices() already
        //    de-duplicates SCO/A2DP pairs and — importantly on OnePlus builds —
        //    filters out the phantom entry that is really the phone itself.
        try {
            val audioDevices = PreferredAudioDeviceResolver.connectedBluetoothAudioDevices(context)
            if (audioDevices.isNotEmpty()) {
                val glasses = PreferredAudioDeviceResolver.findGlasses(
                    context, audioDevices,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                )
                // A non-null match is the glasses. A null match with devices present
                // means a second accessory is connected and we cannot tell which is
                // which — still "something is connected", which is all this asks.
                return glasses != null || audioDevices.isNotEmpty()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio-device check failed: ${e.message}")
        }

        // 3. The vendor SDK's GATT link. It can throw or report stale data when the
        //    SDK was never initialised, so a throw means "unknown" and falls through
        //    to step 4 — never "false", which would make the app deaf.
        try {
            if (com.oudmon.ble.base.bluetooth.BleOperateManager.getInstance().isConnected) {
                return true
            }
        } catch (e: Throwable) {
            Log.d(TAG, "BleOperateManager check unavailable: ${e.message}")
        }

        // 4. Classic profiles, then system-reported GATT devices, then a bonded-device
        //    reflection probe. Moved verbatim from Mark1MainActivity.isGlassConnected()
        //    so the existing BLE gate keeps behaving exactly as it did.
        return legacyProfileCascade(context, adapter)
    }

    /**
     * Mark 1: connected only if a device NAMED like a Mark 1 is connected. The same
     * sources as [compute], but each one is checked by device name.
     */
    private fun computeMark1(context: Context, adapter: BluetoothAdapter): Boolean {
        val names = connectedDeviceNames(context, adapter)
        val match = names.firstOrNull { GlassDeviceFilter.isMark1Name(it) }
        if (match == null && names.isNotEmpty()) {
            Log.d(TAG, "Mark 1 not connected — other device(s) connected: $names")
        }
        return match != null
    }

    /**
     * Names of every Bluetooth device the phone reports as connected: audio
     * endpoints, GATT links, and bonded devices whose link is up.
     */
    private fun connectedDeviceNames(context: Context, adapter: BluetoothAdapter): List<String> {
        val names = LinkedHashSet<String>()
        try {
            PreferredAudioDeviceResolver.connectedBluetoothAudioDevices(context)
                .mapNotNullTo(names) { it.productName?.toString()?.takeIf(String::isNotBlank) }
        } catch (e: Exception) {
            Log.w(TAG, "Audio-device name check failed: ${e.message}")
        }
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            (bm?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() +
                bm?.getConnectedDevices(BluetoothProfile.GATT_SERVER).orEmpty())
                .mapNotNullTo(names) { it.name?.takeIf(String::isNotBlank) }
        } catch (e: SecurityException) {
            Log.w(TAG, "No BLUETOOTH_CONNECT permission for GATT names: ${e.message}")
        }
        try {
            for (device in adapter.bondedDevices.orEmpty()) {
                val connected = try {
                    device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
                } catch (e: Exception) {
                    false
                }
                if (connected) device.name?.takeIf(String::isNotBlank)?.let(names::add)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission to read bonded devices: ${e.message}")
        }
        return names.toList()
    }

    private fun legacyProfileCascade(context: Context, adapter: BluetoothAdapter): Boolean {
        try {
            for (p in intArrayOf(BluetoothProfile.HEADSET, BluetoothProfile.A2DP, BluetoothProfile.GATT)) {
                val state = try {
                    adapter.getProfileConnectionState(p)
                } catch (e: Exception) {
                    BluetoothProfile.STATE_DISCONNECTED
                }
                if (state == BluetoothProfile.STATE_CONNECTED) return true
            }

            try {
                val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val gattConnected = bm?.getConnectedDevices(BluetoothProfile.GATT).orEmpty() +
                    bm?.getConnectedDevices(BluetoothProfile.GATT_SERVER).orEmpty()
                if (gattConnected.isNotEmpty()) return true
            } catch (e: SecurityException) {
                Log.w(TAG, "No BLUETOOTH_CONNECT permission for GATT check: ${e.message}")
            }

            // BluetoothDevice.isConnected() is hidden API, hence reflection. Catches
            // classic audio headsets the profile proxy momentarily reports as down.
            try {
                for (device in adapter.bondedDevices.orEmpty()) {
                    val connected = try {
                        device.javaClass.getMethod("isConnected").invoke(device) as? Boolean ?: false
                    } catch (e: Exception) {
                        false
                    }
                    if (connected) return true
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission to read bonded devices: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Profile cascade error: ${e.message}")
        }
        return false
    }

    private fun adapterOrNull(context: Context): BluetoothAdapter? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Without the permission the profile/bonded probes below would throw;
            // the adapter itself is still readable for the enabled check.
            BluetoothAdapter.getDefaultAdapter()
        } else {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: BluetoothAdapter.getDefaultAdapter()
        }
    } catch (e: Exception) {
        null
    }
}
