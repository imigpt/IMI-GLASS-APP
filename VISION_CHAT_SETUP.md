# Vision Chat - Glasses Image Transfer & Analysis

## Overview
A new **Vision Chat** page that:
1. Receives images from glasses via Wi-Fi HTTP upload
2. Displays them in a chat-like interface
3. Uses Gemini Vision API to analyze and explain the images
4. Triggered by voice command: **"What is in front of me"**

## Architecture

### Components Created

#### 1. **VisionChatActivity.kt**
- Main activity with chat RecyclerView
- Built-in HTTP server (port 8080) to receive images
- Gemini Vision API integration
- Real-time image analysis and chat display

#### 2. **GlassesImageUploader.kt**
- Helper class for glasses-side image capture
- HTTP multipart upload to phone app
- Bitmap compression and optimization

#### 3. **Layout Files**
- `activity_vision_chat.xml` - Main chat screen
- `item_vision_chat_message.xml` - Chat message bubbles with image preview

## How It Works

### Flow Diagram
```
User says "What is in front of me"
        ↓
Glasses capture photo
        ↓
Upload to phone via Wi-Fi HTTP POST
        ↓
Phone receives image → saves locally
        ↓
Display in chat with timestamp
        ↓
Send to Gemini Vision API
        ↓
Gemini analyzes → returns description
        ↓
Show AI response in chat
```

### Network Communication

**Phone App (Server)**
- Starts HTTP server on port 8080
- Listens for POST requests to `/upload`
- Accepts multipart/form-data with image file
- Extracts JPEG/PNG from HTTP body
- Saves to app's files directory

**Glasses (Client)**
- Captures image via camera SDK
- Compresses to JPEG (quality 80%)
- Creates multipart HTTP POST request
- Uploads to: `http://<PHONE_IP>:8080/upload`

### Server URL Discovery

The phone displays its local IP address at the top of the Vision Chat screen:
```
Server: http://192.168.1.100:8080/upload
```

**Options for glasses to find phone:**
1. **Manual configuration**: Enter IP in glasses settings
2. **QR code**: Display QR code on phone, scan with glasses
3. **mDNS/Bonjour**: Advertise service name (requires network support)
4. **Hardcoded**: If both devices always on same network

## Usage

### On Phone App

1. **Launch Vision Chat**
   ```kotlin
   // From MainActivity or any activity
   startActivity(Intent(this, VisionChatActivity::class.java))
   ```

2. **Note the server URL** displayed at the top (e.g., `http://192.168.1.100:8080/upload`)

3. **Wait for images** - the server runs automatically

### On Glasses

1. **Voice command**: Say "What is in front of me"

2. **Glasses code** (integrate in voice command handler):
   ```kotlin
   // In your voice command handler
   if (userSaid.contains("what is in front of me", ignoreCase = true)) {
       CoroutineScope(Dispatchers.IO).launch {
           // Capture image
           val imageUploader = GlassesImageUploader(context)
           val capturedImage = imageUploader.captureImageFromGlasses()
           
           if (capturedImage != null) {
               // Upload to phone
               val phoneServerUrl = "http://192.168.1.100:8080/upload" // Get from settings
               val success = imageUploader.uploadImage(capturedImage, phoneServerUrl)
               
               if (success) {
                   Log.d(TAG, "✅ Image uploaded successfully")
               }
           }
       }
   }
   ```

3. **Camera capture integration** (replace placeholder in `GlassesImageUploader.kt`):
   ```kotlin
   suspend fun captureImageFromGlasses(): Bitmap? {
       return withContext(Dispatchers.IO) {
           // TODO: Replace with actual glasses SDK camera API
           // Example for HeyCyan SDK:
           val imageData = LargeDataHandler.getInstance().capturePhoto()
           BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
       }
   }
   ```

## Testing Without Glasses

You can test the image upload from any device using curl:

```bash
# From another phone, computer, or terminal
curl -X POST \
  -F "file=@/path/to/test_image.jpg" \
  "http://192.168.1.100:8080/upload"
```

Or use Postman:
- Method: POST
- URL: `http://<PHONE_IP>:8080/upload`
- Body: form-data
- Key: `file` (type: File)
- Value: Select any image file

## Integration Steps

### Step 1: Add Button to MainActivity Layout

Add to `activity_main.xml`:
```xml
<Button
    android:id="@+id/btnVisionChat"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="👓 Vision Chat"
    android:backgroundTint="#4CAF50" />
```

### Step 2: Integrate Voice Command Detection

In your existing voice command handler (e.g., `handleVoiceCommand` in MainActivity):

```kotlin
private fun handleVoiceCommand(command: String) {
    when {
        command.contains("what is in front of me", ignoreCase = true) -> {
            triggerGlassesImageCapture()
        }
        // ... other commands
    }
}

private fun triggerGlassesImageCapture() {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            // Send command to glasses to capture photo
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x01) // Camera capture command
            ) { success, data ->
                if (success) {
                    Log.d(TAG, "📸 Glasses capture triggered")
                    // Image will be automatically uploaded via Wi-Fi
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering capture", e)
        }
    }
}
```

### Step 3: Configure Glasses Upload URL

Add to glasses-side configuration or settings:

```kotlin
// Store phone server URL in preferences
val prefs = getSharedPreferences("vision_prefs", MODE_PRIVATE)
prefs.edit().putString("phone_server_url", "http://192.168.1.100:8080/upload").apply()

// Retrieve when uploading
val serverUrl = prefs.getString("phone_server_url", "")
```

Or use QR code for easy setup:

```kotlin
// Generate QR code with server URL
val qrCodeData = serverUrl
// Display QR code on phone
// Scan with glasses to auto-configure
```

## Security Considerations

### Current Implementation
- ⚠️ **No authentication** - any device can upload images
- ⚠️ **HTTP (not HTTPS)** - unencrypted transmission
- ⚠️ **Open port 8080** - accessible on local network

### Recommended Enhancements

1. **Add Token Authentication**
   ```kotlin
   // Server side (VisionChatActivity)
   val authToken = UUID.randomUUID().toString()
   
   // Check token in handleImageUpload
   if (!request.contains("Authorization: Bearer $authToken")) {
       output.write("HTTP/1.1 401 Unauthorized\r\n\r\n".toByteArray())
       return
   }
   
   // Client side (GlassesImageUploader)
   connection.setRequestProperty("Authorization", "Bearer $authToken")
   ```

2. **Use HTTPS (TLS)**
   - Generate self-signed certificate
   - Use SSLServerSocket instead of ServerSocket
   - Accept certificate on glasses

3. **Limit Upload Size**
   ```kotlin
   if (contentLength > 10 * 1024 * 1024) { // 10MB limit
       output.write("HTTP/1.1 413 Payload Too Large\r\n\r\n".toByteArray())
       return
   }
   ```

## Performance Optimization

### Image Compression
- **Glasses side**: Compress to 80% quality JPEG before upload
- **Resize**: Scale to max 1024x1024 to reduce bandwidth
- **Format**: Use JPEG (not PNG) for photos

### Network Optimization
- **Batch uploads**: Queue multiple images if needed
- **Retry logic**: Implement exponential backoff on failure
- **Connection pooling**: Reuse HTTP connections

### Battery Optimization
- **Trigger-based**: Only capture when voice command detected
- **Background limits**: Limit upload rate (max 1 per 5 seconds)
- **Wi-Fi only**: Disable cellular uploads

## Troubleshooting

### Images Not Received

1. **Check network connectivity**
   - Phone and glasses on same Wi-Fi network?
   - Ping phone IP from glasses terminal

2. **Verify server is running**
   - Look for log: `📡 Image server started on port 8080`
   - Check if port 8080 is open

3. **Firewall blocking**
   - Windows: Allow app through firewall
   - Router: Check port forwarding rules

4. **Wrong IP address**
   - Phone IP may change (DHCP)
   - Use static IP or update settings

### Gemini API Errors

1. **API Key not set**
   - Check `BuildConfig.GEMINI_API_KEY` is configured
   - Verify key is valid in Google AI Studio

2. **Rate limiting**
   - Free tier: 60 requests/minute
   - Wait and retry or upgrade plan

3. **Image too large**
   - Max size: ~4MB per image
   - Compress more aggressively

### Chat Not Updating

1. **Check RecyclerView adapter**
   - Look for log: `💾 Saved image: ...`
   - Verify `addMessage()` is called

2. **UI thread errors**
   - All UI updates must be on main thread
   - Use `runOnUiThread` or `withContext(Dispatchers.Main)`

## Future Enhancements

### Real-Time Streaming
- Use WebSocket instead of HTTP POST
- Stream video frames for live analysis
- Lower latency (~100ms vs 1-2s)

### Offline Mode
- Cache images locally on glasses
- Upload in batch when connected
- Local AI model for basic analysis

### Multi-Device Support
- Support multiple phones/glasses
- Room-based chat groups
- Shared vision analysis sessions

### Advanced Features
- **Object tracking**: Identify and track objects across frames
- **OCR**: Extract text from images
- **Face recognition**: Identify people (with privacy controls)
- **AR overlays**: Send analysis back to glasses display

## API Reference

### VisionChatActivity

**Public Methods:**
- `onCreate()` - Initialize chat UI and start HTTP server
- `onDestroy()` - Stop server and cleanup

**Server Endpoints:**
- `POST /upload` - Accept multipart image upload
  - Content-Type: `multipart/form-data`
  - Field name: `file`
  - Returns: `{"status":"success"}` (HTTP 200)

### GlassesImageUploader

**Public Methods:**
```kotlin
suspend fun uploadImage(imageBitmap: Bitmap, serverUrl: String): Boolean
suspend fun captureImageFromGlasses(): Bitmap?
```

**Parameters:**
- `imageBitmap`: Captured image to upload
- `serverUrl`: Phone server URL (e.g., "http://192.168.1.100:8080/upload")

**Returns:**
- `true` if upload successful
- `false` on error

## Dependencies

Already included in project:
- ✅ Gemini AI SDK (`com.google.ai.client.generativeai`)
- ✅ OkHttp (for HTTP client)
- ✅ Kotlin Coroutines
- ✅ RecyclerView / Material Design

No additional dependencies required!

## Build & Deploy

1. **Build the project**
   ```bash
   cd "d:\office work\imitext1.o\HeyCyan_Android_SDK_1.0.2_20250816 (1)\HeyCyan_Android_SDK_1.0.2_20250816\GlassesSDKSample\GlassesSDKSample"
   .\gradlew assembleDebug
   ```

2. **Install on phone**
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Launch Vision Chat**
   - Open app → Tap "Vision Chat" button
   - Note the server URL displayed

4. **Configure glasses**
   - Set phone IP address in glasses settings
   - Enable Wi-Fi transfer

5. **Test**
   - Say "What is in front of me"
   - Watch for image in phone chat
   - Read Gemini's analysis

---

**Status**: ✅ Implementation Complete  
**Next**: Integrate with actual glasses camera API and test end-to-end  
**Date**: 2025-12-20
