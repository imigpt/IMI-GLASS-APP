# Image Upload Feature

## Overview
The app now supports automatic upload of glasses photos to a server using OkHttp multipart upload.

## Components

### 1. ImageUploadService.kt
Handles HTTP multipart image uploads with:
- File upload support
- ByteArray upload support
- Async callbacks (onSuccess/onError)
- Configurable timeout settings

### 2. Upload Settings Dialog
Access via **☁️ Upload Settings** button in MainActivity:
- Enable/disable auto-upload
- Configure server URL
- Settings saved in SharedPreferences

## Configuration

### Enable Upload
1. Open the app
2. Tap **☁️ Upload Settings** button
3. Toggle **Enable Auto Upload**
4. Enter your server URL
5. Tap **Save**

### Default Settings
- **Enabled**: Disabled by default
- **URL**: `http://10.0.2.2:8080/upload` (Android emulator localhost)

## Server Requirements

The upload endpoint should accept multipart/form-data with these fields:
- `image`: Image file (JPEG)
- `timestamp`: Capture timestamp (milliseconds)
- `source`: Always "smart_glasses"

### Example Node.js Server

```javascript
const express = require('express');
const multer = require('multer');
const path = require('path');

const app = express();
const upload = multer({ dest: 'uploads/' });

app.post('/upload', upload.single('image'), (req, res) => {
    console.log('Image received:', req.file);
    console.log('Timestamp:', req.body.timestamp);
    console.log('Source:', req.body.source);
    res.json({ success: true, filename: req.file.filename });
});

app.listen(8080, () => {
    console.log('Server listening on port 8080');
});
```

### Example Python Flask Server

```python
from flask import Flask, request, jsonify
import os

app = Flask(__name__)
UPLOAD_FOLDER = 'uploads'
os.makedirs(UPLOAD_FOLDER, exist_ok=True)

@app.route('/upload', methods=['POST'])
def upload_image():
    if 'image' not in request.files:
        return jsonify({'error': 'No image provided'}), 400
    
    image = request.files['image']
    timestamp = request.form.get('timestamp')
    source = request.form.get('source')
    
    filename = f"glass_{timestamp}.jpg"
    image.save(os.path.join(UPLOAD_FOLDER, filename))
    
    return jsonify({
        'success': True,
        'filename': filename,
        'timestamp': timestamp
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080)
```

## Testing

### With Android Emulator
1. Run server on host machine (port 8080)
2. Configure app URL to `http://10.0.2.2:8080/upload`
3. Enable auto-upload
4. Capture photo from glasses
5. Check server logs for upload confirmation

### With Physical Device
1. Ensure device and server are on same network
2. Configure app URL to `http://<server-ip>:8080/upload`
3. Enable auto-upload
4. Capture photo from glasses

## Logs

Look for these log tags:
- `ImageUploadService`: Upload operations
- `SmartGlassAI`: Photo capture and save operations

### Success Log
```
📤 Uploading image: glass_photo_1234567890.jpg (152341 bytes)
✅ Upload successful: {"success":true,"filename":"uploaded.jpg"}
☁️ Photo uploaded to server
```

### Error Log
```
📤 Uploading image: glass_photo_1234567890.jpg (152341 bytes)
❌ Image upload failed: Connection refused
```

## Integration Points

### Photo Capture Flow
1. Glasses capture photo → BLE transfer
2. `MyDeviceNotifyListener.parseData()` receives photo data
3. `savePhoto()` saves to gallery
4. If auto-upload enabled → `uploadPhotoToServer()` uploads to server
5. Toast notification on success/failure

### VisionChatActivity
Images from VisionChat HTTP server can also be uploaded by calling:
```kotlin
imageUploadService?.uploadImage(file, onSuccess, onError)
```

## Troubleshooting

### "Upload failed: Connection refused"
- Check server is running
- Verify URL is correct (use 10.0.2.2 for emulator)
- Check network connectivity

### "Upload failed with code 413"
- Server file size limit exceeded
- Increase server max upload size

### Photos not uploading
- Check **Enable Auto Upload** is toggled on
- Verify upload URL in settings
- Check app logs for errors
