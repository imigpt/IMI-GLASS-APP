package com.sdk.glassessdksample.utils

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.oudmon.ble.base.communication.LargeDataHandler

/**
 * Safe BLE Command Helper
 * 
 * Workaround for SDK bug in GlassModelControlResponse.java line 27
 * where it accesses bArr[7] without checking array length.
 * 
 * The SDK crashes with ArrayIndexOutOfBoundsException when glasses
 * send a 6-byte response (bc410000ffff) instead of expected 8+ bytes.
 * 
 * This helper wraps BLE commands with proper error handling.
 */
object SafeBleCommandHelper {
    
    private const val TAG = "SafeBleCommandHelper"
    
    // Camera command codes
    const val CAMERA_TAKE_PHOTO = 0x01.toByte()
    const val CAMERA_RECORD_VIDEO = 0x02.toByte()
    const val CAMERA_STOP_RECORD = 0x03.toByte()
    const val CAMERA_EXIT = 0x00.toByte()
    
    // Response codes
    const val CODE_SUCCESS = 0
    const val CODE_ASYNC_SUCCESS = 65  // SDK returns 65 for async operations
    const val CODE_DEVICE_BUSY = 255
    const val CODE_TRANSFER_FAILED = -1
    
    /**
     * Execute camera command with safe error handling
     * 
     * @param action Camera action (TAKE_PHOTO, RECORD_VIDEO, etc.)
     * @param callback Result callback (code, errorMessage)
     * @param timeoutMs Timeout in milliseconds (default 15 seconds)
     */
    fun executeCameraCommand(
        action: Byte,
        callback: (code: Int, error: String?) -> Unit,
        timeoutMs: Long = 15000
    ) {
        val command = byteArrayOf(0x02, 0x01, action)
        executeCommandSafely(command, callback, timeoutMs)
    }
    
    /**
     * Execute any BLE command with exception handling
     * 
     * This catches the ArrayIndexOutOfBoundsException that occurs when
     * GlassModelControlResponse.acceptData() accesses index 7 of a
     * 6-byte array.
     */
    fun executeCommandSafely(
        command: ByteArray,
        callback: (code: Int, error: String?) -> Unit,
        timeoutMs: Long = 10000
    ) {
        var callbackInvoked = false
        val handler = Handler(Looper.getMainLooper())
        
        // Timeout runnable
        val timeoutRunnable = Runnable {
            if (!callbackInvoked) {
                callbackInvoked = true
                Log.w(TAG, "⏰ Command timeout after ${timeoutMs}ms")
                callback(CODE_TRANSFER_FAILED, "Command timeout")
            }
        }
        
        // Start timeout timer
        handler.postDelayed(timeoutRunnable, timeoutMs)
        
        try {
            Log.d(TAG, "📡 Executing BLE command: ${command.toHexString()}")
            
            LargeDataHandler.getInstance().glassesControl(command) { code, errorResponse ->
                if (!callbackInvoked) {
                    callbackInvoked = true
                    handler.removeCallbacks(timeoutRunnable)
                    
                    // Convert error response to string (errorResponse may be GlassModelControlResponse or other type)
                    val errorMessage = errorResponse?.toString()
                    Log.d(TAG, "📨 BLE response: code=$code, error=$errorMessage")
                    
                    // Handle known error codes
                    when (code) {
                        CODE_SUCCESS, CODE_ASYNC_SUCCESS -> {
                            callback(code, null)
                        }
                        CODE_DEVICE_BUSY -> {
                            Log.w(TAG, "⚠️ Device busy (code 255)")
                            callback(code, "Device busy, please wait")
                        }
                        else -> {
                            // Any non-negative code could be success for async ops
                            if (code >= 0) {
                                Log.d(TAG, "📨 Treating code $code as success")
                                callback(code, null)
                            } else {
                                callback(code, errorMessage)
                            }
                        }
                    }
                }
            }
        } catch (e: ArrayIndexOutOfBoundsException) {
            // This catches the SDK bug in GlassModelControlResponse.acceptData()
            Log.e(TAG, "🐛 SDK ArrayIndexOutOfBoundsException caught (known bug): ${e.message}")
            
            if (!callbackInvoked) {
                callbackInvoked = true
                handler.removeCallbacks(timeoutRunnable)
                
                // The command was likely sent successfully, the parsing just failed
                // We treat this as a success and wait for the image via HTTP/WiFi
                callback(CODE_ASYNC_SUCCESS, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Unexpected error executing command", e)
            
            if (!callbackInvoked) {
                callbackInvoked = true
                handler.removeCallbacks(timeoutRunnable)
                callback(CODE_TRANSFER_FAILED, e.message)
            }
        }
    }
    
    /**
     * Take photo from glasses camera (with detailed callbacks)
     */
    fun takePhoto(
        onSuccess: ((Int) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        timeoutMs: Long = 15000
    ) {
        executeCameraCommand(CAMERA_TAKE_PHOTO, { code, error ->
            val success = code == CODE_SUCCESS || code == CODE_ASYNC_SUCCESS || code >= 0
            if (success) {
                onSuccess?.invoke(code)
            } else {
                onError?.invoke(error ?: "Unknown error (code: $code)")
            }
        }, timeoutMs)
    }
    
    /**
     * Take photo from glasses camera (legacy callback style)
     */
    fun takePhoto(callback: (success: Boolean, error: String?) -> Unit) {
        executeCameraCommand(CAMERA_TAKE_PHOTO, { code, error ->
            val success = code == CODE_SUCCESS || code == CODE_ASYNC_SUCCESS || code >= 0
            callback(success, error)
        }, 15000)
    }
    
    /**
     * Start video recording (with detailed callbacks)
     */
    fun startRecording(
        onSuccess: ((Int) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        timeoutMs: Long = 15000
    ) {
        executeCameraCommand(CAMERA_RECORD_VIDEO, { code, error ->
            val success = code == CODE_SUCCESS || code == CODE_ASYNC_SUCCESS || code >= 0
            if (success) {
                onSuccess?.invoke(code)
            } else {
                onError?.invoke(error ?: "Unknown error (code: $code)")
            }
        }, timeoutMs)
    }
    
    /**
     * Start video recording (legacy callback style)
     */
    fun startRecording(callback: (success: Boolean, error: String?) -> Unit) {
        executeCameraCommand(CAMERA_RECORD_VIDEO, { code, error ->
            val success = code == CODE_SUCCESS || code == CODE_ASYNC_SUCCESS || code >= 0
            callback(success, error)
        }, 15000)
    }
    
    /**
     * Stop video recording (with detailed callbacks)
     */
    fun stopRecording(
        onSuccess: ((Int) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        timeoutMs: Long = 15000
    ) {
        executeCameraCommand(CAMERA_STOP_RECORD, { code, error ->
            val success = code == CODE_SUCCESS || code == CODE_ASYNC_SUCCESS || code >= 0
            if (success) {
                onSuccess?.invoke(code)
            } else {
                onError?.invoke(error ?: "Unknown error (code: $code)")
            }
        }, timeoutMs)
    }
    
    /**
     * Stop video recording (legacy callback style)
     */
    fun stopRecording(callback: (success: Boolean, error: String?) -> Unit) {
        executeCameraCommand(CAMERA_STOP_RECORD, { code, error ->
            val success = code == CODE_SUCCESS || code == CODE_ASYNC_SUCCESS || code >= 0
            callback(success, error)
        }, 15000)
    }
    
    /**
     * Exit camera mode (with detailed callbacks)
     */
    fun exitCamera(
        onSuccess: ((Int) -> Unit)? = null,
        onError: ((String) -> Unit)? = null,
        timeoutMs: Long = 15000
    ) {
        executeCameraCommand(CAMERA_EXIT, { code, error ->
            val success = code == CODE_SUCCESS || code == CODE_ASYNC_SUCCESS || code >= 0
            if (success) {
                onSuccess?.invoke(code)
            } else {
                onError?.invoke(error ?: "Unknown error (code: $code)")
            }
        }, timeoutMs)
    }
    
    /**
     * Exit camera mode (legacy callback style)
     */
    fun exitCamera(callback: (success: Boolean, error: String?) -> Unit) {
        executeCameraCommand(CAMERA_EXIT, { code, error ->
            val success = code == CODE_SUCCESS || code == CODE_ASYNC_SUCCESS || code >= 0
            callback(success, error)
        }, 15000)
    }
    
    // Extension function for hex string
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
