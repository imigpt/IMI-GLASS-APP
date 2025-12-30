package com.sdk.glassessdksample.ui

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions

/**
 * Updated Permission Utilities for Smart Glass AI Assistant (Phase 2)
 */

// -----------------------------------------------------------------------------
// BASIC CHECKS
// -----------------------------------------------------------------------------
fun hasCameraPermission(context: Context): Boolean {
    return XXPermissions.isGranted(context, Permission.CAMERA)
}

fun hasMicrophonePermission(context: Context): Boolean {
    return XXPermissions.isGranted(context, Permission.RECORD_AUDIO)
}

fun hasBluetoothPermission(context: Context): Boolean {
    return XXPermissions.isGranted(context, Permission.BLUETOOTH_SCAN) &&
            XXPermissions.isGranted(context, Permission.BLUETOOTH_CONNECT)
}

fun hasNotificationPermission(context: Context): Boolean {
    return XXPermissions.isGranted(context, Permission.POST_NOTIFICATIONS)
}

// -----------------------------------------------------------------------------
// REQUEST CAMERA
// -----------------------------------------------------------------------------
fun requestCameraPermission(
    activity: FragmentActivity,
    callback: OnPermissionCallback
) {
    XXPermissions.with(activity)
        .permission(Permission.CAMERA)
        .request(callback)
}

// -----------------------------------------------------------------------------
// REQUEST MICROPHONE (For Voice Assistant)
// -----------------------------------------------------------------------------
fun requestMicrophonePermission(
    activity: FragmentActivity,
    callback: OnPermissionCallback
) {
    XXPermissions.with(activity)
        .permission(Permission.RECORD_AUDIO)
        // Removed FOREGROUND_SERVICE_MICROPHONE as it's not a runtime permission requestable by XXPermissions directly usually, or handled differently.
        // If it is needed for manifest, it is already there. Runtime check might fail if not careful.
        .request(callback)
}

// -----------------------------------------------------------------------------
// REQUEST BLUETOOTH (Scan + Connect + Advertise)
// -----------------------------------------------------------------------------
fun requestBluetoothPermission(
    activity: FragmentActivity,
    callback: OnPermissionCallback
) {
    XXPermissions.with(activity)
        .permission(Permission.BLUETOOTH_SCAN)
        .permission(Permission.BLUETOOTH_CONNECT)
        .permission(Permission.BLUETOOTH_ADVERTISE)
        .request(callback)
}

// -----------------------------------------------------------------------------
// REQUEST STORAGE / MEDIA ACCESS
// -----------------------------------------------------------------------------
fun requestMediaPermission(
    activity: FragmentActivity,
    callback: OnPermissionCallback
) {
    XXPermissions.with(activity)
        .permission(Permission.READ_MEDIA_IMAGES)
        .permission(Permission.READ_MEDIA_VIDEO)
        .permission(Permission.READ_MEDIA_AUDIO)
        .request(callback)
}

// -----------------------------------------------------------------------------
// REQUEST NOTIFICATION PERMISSION
// -----------------------------------------------------------------------------
fun requestNotificationPermission(
    activity: FragmentActivity,
    callback: OnPermissionCallback
) {
    XXPermissions.with(activity)
        .permission(Permission.POST_NOTIFICATIONS)
        .request(callback)
}

// -----------------------------------------------------------------------------
// FLOATING WINDOW PERMISSION (Future: Floating Assistant bubble)
// -----------------------------------------------------------------------------
fun requestOverlayPermission(activity: FragmentActivity) {
    XXPermissions.with(activity)
        .permission(Permission.SYSTEM_ALERT_WINDOW)
        .request(null)
}

// -----------------------------------------------------------------------------
// REQUEST ALL PERMISSIONS (Phase 2 Complete Setup)
// -----------------------------------------------------------------------------
fun requestAllSmartGlassPermissions(
    activity: FragmentActivity,
    callback: OnPermissionCallback
) {
    XXPermissions.with(activity)
        .permission(Permission.CAMERA)
        .permission(Permission.RECORD_AUDIO)
        .permission(Permission.BLUETOOTH_SCAN)
        .permission(Permission.BLUETOOTH_CONNECT)
        .permission(Permission.BLUETOOTH_ADVERTISE)
        .permission(Permission.READ_MEDIA_IMAGES)
        .permission(Permission.READ_MEDIA_VIDEO)
        .permission(Permission.READ_MEDIA_AUDIO)
        .permission(Permission.POST_NOTIFICATIONS)
        .request(callback)
}
