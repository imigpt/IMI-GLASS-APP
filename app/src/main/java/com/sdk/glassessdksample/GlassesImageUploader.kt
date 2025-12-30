package com.sdk.glassessdksample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Glasses Image Uploader
 * Captures images from glasses camera and uploads to phone app via Wi-Fi
 */
class GlassesImageUploader(private val context: Context) {

    companion object {
        private const val TAG = "GlassesImageUploader"
        private const val UPLOAD_TIMEOUT = 10000 // 10 seconds
    }

    /**
     * Upload image to phone app
     * @param imageBitmap The captured image
     * @param serverUrl The phone app's server URL (e.g., "http://192.168.1.100:8080/upload")
     * @return true if successful
     */
    suspend fun uploadImage(imageBitmap: Bitmap, serverUrl: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "📤 Uploading image to $serverUrl")
                
                // Compress bitmap to JPEG
                val imageOutputStream = ByteArrayOutputStream()
                imageBitmap.compress(Bitmap.CompressFormat.JPEG, 80, imageOutputStream)
                val imageBytes = imageOutputStream.toByteArray()
                
                Log.d(TAG, "📦 Image size: ${imageBytes.size / 1024} KB")
                
                // Create multipart boundary
                val boundary = "----WebKitFormBoundary" + System.currentTimeMillis()
                
                // Build multipart body
                val multipartBody = buildMultipartBody(imageBytes, boundary)
                
                // Create HTTP connection
                val url = URL(serverUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = UPLOAD_TIMEOUT
                connection.readTimeout = UPLOAD_TIMEOUT
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                connection.setRequestProperty("Content-Length", multipartBody.size.toString())
                
                // Send request
                val networkOutputStream = DataOutputStream(connection.outputStream)
                networkOutputStream.write(multipartBody)
                networkOutputStream.flush()
                networkOutputStream.close()
                
                // Get response
                val responseCode = connection.responseCode
                val success = responseCode == HttpURLConnection.HTTP_OK
                
                if (success) {
                    Log.d(TAG, "✅ Upload successful")
                } else {
                    Log.e(TAG, "❌ Upload failed with code: $responseCode")
                }
                
                connection.disconnect()
                success
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Upload error", e)
                false
            }
        }
    }
    
    /**
     * Build multipart form data body
     */
    private fun buildMultipartBody(imageBytes: ByteArray, boundary: String): ByteArray {
        val output = ByteArrayOutputStream()
        val writer = output.writer()
        
        // Write file part
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"file\"; filename=\"glasses_capture.jpg\"\r\n")
        writer.write("Content-Type: image/jpeg\r\n\r\n")
        writer.flush()
        
        output.write(imageBytes)
        
        writer.write("\r\n--$boundary--\r\n")
        writer.flush()
        
        return output.toByteArray()
    }
    
    /**
     * Capture image from glasses camera
     * This is a placeholder - integrate with actual glasses SDK camera API
     */
    suspend fun captureImageFromGlasses(): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                // TODO: Replace with actual glasses SDK camera capture
                // Example: glassesSDK.camera.captureImage()
                
                // For testing: load a sample image or create a blank one
                Log.d(TAG, "📸 Capturing image from glasses...")
                
                // This would be replaced with actual camera capture:
                // val imageData = GlassesCameraManager.capturePhoto()
                // BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
                
                null // Return null until integrated with real camera
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Capture error", e)
                null
            }
        }
    }
}
