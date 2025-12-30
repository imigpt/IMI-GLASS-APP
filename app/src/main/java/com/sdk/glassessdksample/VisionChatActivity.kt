package com.sdk.glassessdksample

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

/**
 * Vision Chat Activity
 * Receives images from glasses via Wi-Fi and uses Gemini Vision API to analyze them
 */
class VisionChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var chatAdapter: VisionChatAdapter
    private val messages = mutableListOf<VisionChatMessage>()
    
    private var imageServerThread: Thread? = null
    private var isServerRunning = false
    private var isWaitingForCapture = false
    private val SERVER_PORT = 8080
    
    private lateinit var generativeModel: GenerativeModel
    private lateinit var ipAddressText: TextView
    
    companion object {
        private const val TAG = "VisionChatActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vision_chat)
        
        // Initialize Gemini Vision model
        val apiKey = BuildConfig.GEMINI_API_KEY
        generativeModel = GenerativeModel(
            modelName = "gemini-2.0-flash-exp",
            apiKey = apiKey
        )
        
        setupUI()
        startImageServer()
    }
    
    private fun setupUI() {
        recyclerView = findViewById(R.id.visionChatRecyclerView)
        ipAddressText = findViewById(R.id.ipAddressText)
        val captureButton = findViewById<Button>(R.id.btnCaptureFromGlasses)
        
        chatAdapter = VisionChatAdapter(messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = chatAdapter
        
        // Display server IP address
        val ipAddress = getLocalIpAddress()
        ipAddressText.text = "Server: http://$ipAddress:$SERVER_PORT/upload"
        
        // Add welcome message
        addMessage(VisionChatMessage(
            text = "👓 Ready to receive images from glasses!\n\nTap 'Capture from Glasses' button or say 'What is in front of me' to capture and analyze.",
            isUser = false,
            timestamp = System.currentTimeMillis()
        ))
        
        // Capture button click handler
        captureButton.setOnClickListener {
            captureImageFromGlasses()
        }
    }
    
    private fun captureImageFromGlasses() {
        try {
            Log.d(TAG, "📸 Initiating image capture from glasses")
            
            addMessage(VisionChatMessage(
                text = "📸 Capturing image from glasses...",
                isUser = true,
                timestamp = System.currentTimeMillis()
            ))
            
            // Mark that we're waiting for a photo to be sent to the HTTP server
            isWaitingForCapture = true
            
            // Trigger glasses camera (command: 0x02, 0x01, 0x01)
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01)) { code, error ->
                Log.d(TAG, "📸 Camera trigger response: code=$code, error=$error")
                
                if (code != 0 && code != 65) {
                    // Failed to trigger camera
                    Log.e(TAG, "❌ Camera trigger failed: code=$code")
                    runOnUiThread {
                        isWaitingForCapture = false
                        addMessage(VisionChatMessage(
                            text = "❌ Failed to trigger camera (code: $code)",
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        ))
                        Toast.makeText(this, "Camera trigger failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Camera triggered successfully - photo will arrive via HTTP server
                    Log.d(TAG, "✅ Camera triggered, waiting for image via HTTP...")
                    runOnUiThread {
                        addMessage(VisionChatMessage(
                            text = "⏳ Camera triggered, waiting for image...",
                            isUser = false,
                            timestamp = System.currentTimeMillis()
                        ))
                        
                        // Set timeout for waiting
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(10000) // 10 second timeout
                            if (isWaitingForCapture) {
                                isWaitingForCapture = false
                                addMessage(VisionChatMessage(
                                    text = "⚠️ Timeout waiting for image. Please try again.",
                                    isUser = false,
                                    timestamp = System.currentTimeMillis()
                                ))
                            }
                        }
                    }
                }
            }
            
            Toast.makeText(this, "📸 Capturing from glasses...", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating capture", e)
            isWaitingForCapture = false
            addMessage(VisionChatMessage(
                text = "❌ Error: ${e.message}",
                isUser = false,
                timestamp = System.currentTimeMillis()
            ))
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address.hostAddress.indexOf(':') < 0) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP", e)
        }
        return "unknown"
    }
    
    private fun startImageServer() {
        isServerRunning = true
        imageServerThread = Thread {
            try {
                val serverSocket = ServerSocket(SERVER_PORT)
                Log.d(TAG, "📡 Image server started on port $SERVER_PORT")
                
                while (isServerRunning) {
                    try {
                        val clientSocket = serverSocket.accept()
                        handleImageUpload(clientSocket)
                    } catch (e: Exception) {
                        if (isServerRunning) {
                            Log.e(TAG, "Error accepting connection", e)
                        }
                    }
                }
                serverSocket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Server error", e)
            }
        }
        imageServerThread?.start()
    }
    
    private fun handleImageUpload(socket: Socket) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                
                // Read HTTP request
                val requestBuilder = StringBuilder()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var contentLength = 0
                var headerEnd = false
                var totalRead = 0
                
                // Read headers
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    totalRead += bytesRead
                    requestBuilder.append(String(buffer, 0, bytesRead))
                    
                    val request = requestBuilder.toString()
                    if (request.contains("\r\n\r\n")) {
                        headerEnd = true
                        val headers = request.substring(0, request.indexOf("\r\n\r\n"))
                        
                        // Extract Content-Length
                        headers.lines().forEach { line ->
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(":").trim().toInt()
                            }
                        }
                        break
                    }
                }
                
                if (!headerEnd || contentLength == 0) {
                    output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                    socket.close()
                    return@launch
                }
                
                // Read image data
                val bodyStartIndex = requestBuilder.toString().indexOf("\r\n\r\n") + 4
                var bodyData = requestBuilder.toString().substring(bodyStartIndex).toByteArray(Charsets.ISO_8859_1)
                
                // Read remaining data
                while (bodyData.size < contentLength) {
                    bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    bodyData += buffer.copyOf(bytesRead)
                }
                
                // Extract image from multipart (simplified - expects boundary)
                val imageBytes = extractImageBytes(bodyData)
                
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    // Save image
                    val imageFile = saveImage(imageBytes)
                    
                    // Send success response
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"status\":\"success\"}".toByteArray())
                    
                    // Process image
                    withContext(Dispatchers.Main) {
                        isWaitingForCapture = false
                        addMessage(VisionChatMessage(
                            text = "📸 Image received from glasses",
                            isUser = false,
                            timestamp = System.currentTimeMillis(),
                            imagePath = imageFile.absolutePath
                        ))
                    }
                    
                    // Analyze with Gemini
                    analyzeImage(imageFile)
                } else {
                    output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                }
                
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling upload", e)
            }
        }
    }
    
    private fun extractImageBytes(bodyData: ByteArray): ByteArray? {
        try {
            // Look for JPEG or PNG headers
            val jpegStart = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
            val pngStart = byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())
            
            // Find JPEG
            for (i in 0 until bodyData.size - 1) {
                if (bodyData[i] == jpegStart[0] && bodyData[i + 1] == jpegStart[1]) {
                    // Find JPEG end marker
                    for (j in i + 2 until bodyData.size - 1) {
                        if (bodyData[j] == 0xFF.toByte() && bodyData[j + 1] == 0xD9.toByte()) {
                            return bodyData.copyOfRange(i, j + 2)
                        }
                    }
                }
            }
            
            // Find PNG
            for (i in 0 until bodyData.size - 3) {
                if (bodyData[i] == pngStart[0] && bodyData[i + 1] == pngStart[1] && 
                    bodyData[i + 2] == pngStart[2] && bodyData[i + 3] == pngStart[3]) {
                    // PNG end: IEND chunk
                    for (j in i + 4 until bodyData.size - 7) {
                        if (bodyData[j] == 0x49.toByte() && bodyData[j + 1] == 0x45.toByte() &&
                            bodyData[j + 2] == 0x4E.toByte() && bodyData[j + 3] == 0x44.toByte()) {
                            return bodyData.copyOfRange(i, j + 8)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting image", e)
        }
        return null
    }
    
    private fun saveImage(imageBytes: ByteArray): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val imageFile = File(filesDir, "vision_$timestamp.jpg")
        FileOutputStream(imageFile).use { it.write(imageBytes) }
        Log.d(TAG, "💾 Saved image: ${imageFile.absolutePath}")
        return imageFile
    }
    
    private fun analyzeImage(imageFile: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    addMessage(VisionChatMessage(
                        text = "🔍 Analyzing image...",
                        isUser = false,
                        timestamp = System.currentTimeMillis()
                    ))
                }
                
                // Load bitmap
                val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
                
                // Send to Gemini Vision API
                val response = generativeModel.generateContent(
                    content {
                        image(bitmap)
                        text("What is in this image? Describe what you see in detail.")
                    }
                )
                
                val explanation = response.text ?: "Unable to analyze image"
                
                withContext(Dispatchers.Main) {
                    addMessage(VisionChatMessage(
                        text = explanation,
                        isUser = false,
                        timestamp = System.currentTimeMillis()
                    ))
                }
                
                Log.d(TAG, "✅ Analysis complete: $explanation")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing image", e)
                withContext(Dispatchers.Main) {
                    addMessage(VisionChatMessage(
                        text = "❌ Error analyzing image: ${e.message}",
                        isUser = false,
                        timestamp = System.currentTimeMillis()
                    ))
                }
            }
        }
    }
    
    private fun addMessage(message: VisionChatMessage) {
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        recyclerView.scrollToPosition(messages.size - 1)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isServerRunning = false
        imageServerThread?.interrupt()
    }
}

// Data class for chat messages
data class VisionChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val imagePath: String? = null
)

// RecyclerView Adapter for vision chat
class VisionChatAdapter(private val messages: List<VisionChatMessage>) :
    RecyclerView.Adapter<VisionChatAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timestampText: TextView = view.findViewById(R.id.timestampText)
        val messageImage: android.widget.ImageView = view.findViewById(R.id.messageImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vision_chat_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        holder.messageText.text = message.text
        
        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        holder.timestampText.text = timeFormat.format(Date(message.timestamp))
        
        // Show image if available
        if (message.imagePath != null) {
            holder.messageImage.visibility = View.VISIBLE
            val bitmap = BitmapFactory.decodeFile(message.imagePath)
            holder.messageImage.setImageBitmap(bitmap)
        } else {
            holder.messageImage.visibility = View.GONE
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) 0 else 1
    }

    override fun getItemCount() = messages.size
}
