package com.sdk.glassessdksample.ui

/**
 * A custom event class for use with EventBus to pass Bluetooth-related events.
 */
data class BluetoothEvent(
    val type: EventType,
    val data: Any? = null
) {
    enum class EventType {
        CONNECTED,
        DISCONNECTED,
        PHOTO_CAPTURED,
        VIDEO_RECORDING_START,
        VIDEO_RECORDING_STOP,
        BATTERY_LEVEL,
        VOICE_TEXT,
        REQUEST_MIC_PERMISSION,
        STREAMING_ENABLED,
        STREAMING_MANUAL_REQUIRED,
        FIRMWARE_WAKE

    }
}
