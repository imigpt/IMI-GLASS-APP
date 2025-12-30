package com.sdk.glassessdksample

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

/**
 * Service for uploading images to a server using OkHttp multipart upload
 */
class ImageUploadService(private val uploadUrl: String) {
    
    private val TAG = "ImageUploadService"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    /**
     * Upload image file to server
     * @param imageFile The image file to upload
     * @param onSuccess Callback when upload succeeds with server response
     * @param onError Callback when upload fails with error message
     */
    fun uploadImage(
        imageFile: File,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        if (!imageFile.exists()) {
            onError("Image file does not exist: ${imageFile.absolutePath}")
            return
        }
        
        Log.d(TAG, "📤 Uploading image: ${imageFile.name} (${imageFile.length()} bytes)")
        
        // Create multipart request body
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                imageFile.name,
                imageFile.asRequestBody("image/*".toMediaTypeOrNull())
            )
            .addFormDataPart("timestamp", System.currentTimeMillis().toString())
            .addFormDataPart("source", "smart_glasses")
            .build()
        
        // Build request
        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .build()
        
        // Execute async upload
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val errorMsg = "Upload failed: ${e.message}"
                Log.e(TAG, errorMsg, e)
                onError(errorMsg)
            }
            
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        Log.d(TAG, "✅ Upload successful: $responseBody")
                        onSuccess(responseBody)
                    } else {
                        val errorMsg = "Upload failed with code ${response.code}: ${response.message}"
                        Log.e(TAG, errorMsg)
                        onError(errorMsg)
                    }
                }
            }
        })
    }
    
    /**
     * Upload image from ByteArray (for direct glasses photo data)
     * @param imageData The raw image bytes
     * @param fileName Name for the uploaded file
     * @param onSuccess Callback when upload succeeds
     * @param onError Callback when upload fails
     */
    fun uploadImageData(
        imageData: ByteArray,
        fileName: String = "glasses_photo_${System.currentTimeMillis()}.jpg",
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "📤 Uploading image data: $fileName (${imageData.size} bytes)")
        
        // Create multipart request body from ByteArray
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image",
                fileName,
                RequestBody.create("image/jpeg".toMediaTypeOrNull(), imageData)
            )
            .addFormDataPart("timestamp", System.currentTimeMillis().toString())
            .addFormDataPart("source", "smart_glasses")
            .build()
        
        // Build request
        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .build()
        
        // Execute async upload
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val errorMsg = "Upload failed: ${e.message}"
                Log.e(TAG, errorMsg, e)
                onError(errorMsg)
            }
            
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (response.isSuccessful) {
                        val responseBody = response.body?.string() ?: ""
                        Log.d(TAG, "✅ Upload successful: $responseBody")
                        onSuccess(responseBody)
                    } else {
                        val errorMsg = "Upload failed with code ${response.code}: ${response.message}"
                        Log.e(TAG, errorMsg)
                        onError(errorMsg)
                    }
                }
            }
        })
    }
    
    /**
     * Cancel all pending uploads
     */
    fun cancelAll() {
        client.dispatcher.cancelAll()
        Log.d(TAG, "❌ All uploads cancelled")
    }
}
