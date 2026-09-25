package com.sdk.glassessdksample.ui

import android.content.Context

/**
 * Which Bluetooth devices count as IMI glasses.
 *
 * Mark 1 is paired in the phone's own Bluetooth settings, so the app never scans
 * for it — it only sees "something is connected". Without a filter, earbuds, a
 * speaker or a car kit all counted as the glasses: the gate opened, the wake word
 * armed on the wrong microphone, and the first such device got saved as "the paired
 * glasses". Mark 1 units advertise names like "F-16", so for Mark 1 only a device
 * whose name matches [MARK1_NAME] is accepted.
 *
 * Mark 2 is found by the app's own scan in DeviceBindActivity and is not filtered here.
 */
object GlassDeviceFilter {

    /** "F-16", "F16", "F-17 xxxx"… — an F, an optional dash, then digits. */
    private val MARK1_NAME = Regex("^F-?\\d+", RegexOption.IGNORE_CASE)

    /** True when [name] looks like a Mark 1 (e.g. "F-16"). */
    fun isMark1Name(name: String?): Boolean =
        !name.isNullOrBlank() && MARK1_NAME.containsMatchIn(name.trim())

    /** The name filter applies only while the user has Mark 1 selected. */
    fun isActive(context: Context): Boolean =
        DevicePreferenceManager.getDeviceType(context) == DeviceType.MARK1

    /** Whether a device called [name] may be treated as the glasses right now. */
    fun accepts(context: Context, name: String?): Boolean =
        !isActive(context) || isMark1Name(name)
}
