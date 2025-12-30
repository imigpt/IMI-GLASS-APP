# Quick Start - Vision Chat Feature

## What You Got

✅ **New VisionChatActivity** - A chat page that receives images from glasses and explains them  
✅ **HTTP Image Server** - Built-in server (port 8080) to receive photos via Wi-Fi  
✅ **Gemini Vision API** - Automatic image analysis and explanations  
✅ **Chat UI** - Shows images and AI responses in chat format  
✅ **Glasses Upload Helper** - Ready-to-use code for uploading from glasses  

## Launch the Vision Chat

### From Code
```kotlin
startActivity(Intent(this, VisionChatActivity::class.java))
```

### What You'll See
```
┌──────────────────────────────────────┐
│ 👓 Vision Chat                       │
│ Server: http://192.168.1.100:8080.. │
│ Say: 'What is in front of me'...    │
├──────────────────────────────────────┤
│                                      │
│  📸 Ready to receive images!         │
│  Say "What is in front of me" to... │
│                                  10:30│
│                                      │
│  [Received images appear here]       │
│                                      │
├──────────────────────────────────────┤
│ 📡 Waiting for images from glasses...│
└──────────────────────────────────────┘
```

## How to Use

### Step 1: Launch Vision Chat
Open the app and navigate to Vision Chat (you'll need to add a button/menu item in your UI)

### Step 2: Note the Server IP
The screen shows: `Server: http://192.168.1.100:8080/upload`  
This is where glasses should send images.

### Step 3: Configure Glasses
In your glasses app, when user says **"What is in front of me"**:

```kotlin
// Add this to your voice command handler
if (userCommand.contains("what is in front of me", ignoreCase = true)) {
    CoroutineScope(Dispatchers.IO).launch {
        // 1. Capture image from glasses camera
        val capturedBitmap = captureGlassesPhoto() // Your camera API
        
        // 2. Upload to phone
        val uploader = GlassesImageUploader(context)
        val phoneServerUrl = "http://192.168.1.100:8080/upload" // From settings
        val success = uploader.uploadImage(capturedBitmap, phoneServerUrl)
        
        if (success) {
            Log.d(TAG, "✅ Image sent to phone")
        }
    }
}
```

### Step 4: Watch the Magic
1. User says "What is in front of me"
2. Glasses capture photo
3. Photo uploads to phone via Wi-Fi
4. Phone displays image in chat
5. Gemini analyzes the image
6. AI explanation appears in chat

## Testing Without Glasses

Use curl from another device:

```bash
curl -X POST \
  -F "file=@test_image.jpg" \
  "http://192.168.1.100:8080/upload"
```

Or use Postman:
- URL: `http://<PHONE_IP>:8080/upload`
- Method: POST
- Body: form-data, key=`file`, type=File

## Integration Checklist

- [ ] Add button/menu to launch VisionChatActivity
- [ ] Integrate glasses camera capture API
- [ ] Add voice command detection for "what is in front of me"
- [ ] Store phone server URL in glasses settings
- [ ] Test on same Wi-Fi network
- [ ] Add error handling for network failures
- [ ] Optional: Add QR code for easy server URL setup

## Files You Need

**Phone App:**
- ✅ `VisionChatActivity.kt` - Main activity (already created)
- ✅ `activity_vision_chat.xml` - Layout (already created)
- ✅ `item_vision_chat_message.xml` - Message layout (already created)

**Glasses App:**
- ✅ `GlassesImageUploader.kt` - Upload helper (already created, needs camera integration)
- ⚠️ Voice command handler (add to your existing code)
- ⚠️ Camera capture function (use your SDK's camera API)

## Next Steps

1. **Add UI Button** - Create a way to open Vision Chat from your main screen
2. **Integrate Camera** - Replace placeholder in `GlassesImageUploader.captureImageFromGlasses()`
3. **Voice Commands** - Hook up "what is in front of me" detection
4. **Test Transfer** - Verify images reach phone correctly
5. **Deploy** - Install updated APK and test end-to-end

## Troubleshooting

**Images not received?**
- Check both devices on same Wi-Fi
- Verify server IP is correct
- Look for log: `📡 Image server started on port 8080`

**Gemini errors?**
- Check API key in `BuildConfig.GEMINI_API_KEY`
- Verify internet connection
- Check API quota limits

**Chat not updating?**
- Look for log: `💾 Saved image: ...`
- Check log: `✅ Analysis complete`

## Advanced Options

See [VISION_CHAT_SETUP.md](VISION_CHAT_SETUP.md) for:
- Security (authentication tokens, HTTPS)
- Performance optimization
- Battery management
- QR code setup
- WebSocket streaming alternative

---

**Build Status:** ✅ Successful  
**APK Location:** `app/build/outputs/apk/debug/app-debug.apk`  
**Ready to Test!** 🚀
