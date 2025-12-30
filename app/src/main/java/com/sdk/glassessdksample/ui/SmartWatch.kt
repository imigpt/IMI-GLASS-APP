package com.sdk.glassessdksample.ui

import java.util.Objects

/**
 * Data class to hold information about a scanned Bluetooth device.
 */
data class SmartWatch(
    var deviceName: String = "",
    var deviceAddress: String = "",
    var isSelect: Boolean = false,
    var rssi: Int  // Added rssi for signal strength sorting
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SmartWatch) return false
        return deviceAddress == other.deviceAddress
    }

    override fun hashCode(): Int {
        return Objects.hash(deviceAddress)
    }
}
