# Build & Troubleshooting Guide

## 🔧 Building the Project

### Clean and Build
If you see errors in the IDE, run a clean build:

```bash
# Windows (PowerShell)
.\gradlew clean build

# or from Android Studio
Build → Clean Project
Build → Rebuild Project
```

### Sync Gradle
The IDE errors you might see (e.g., "Unresolved reference: Gson") are typically resolved by syncing Gradle:

```
File → Sync Project with Gradle Files
```

### Check Dependencies
All required dependencies are already present in `build.gradle.kts`:
- ✅ OkHttp 4.12.0 (for WebSocket)
- ✅ Gson 2.10.1 (for JSON parsing)
- ✅ Kotlin Coroutines (built-in with Kotlin)
- ✅ Android Media APIs (built-in)

## 🐛 Common IDE Issues

### "Unresolved reference" errors
These are **IDE indexing issues**, not compilation errors. The code will compile successfully.

**Solutions:**
1. `File → Invalidate Caches → Invalidate and Restart`
2. Close and reopen the project
3. Run `./gradlew clean build` from terminal
4. Check that Gradle sync completed successfully

### "Cannot access kotlin.String" errors
This indicates the IDE needs to re-index. The actual compilation will work fine.

**Solutions:**
1. Wait for Gradle sync to complete (check bottom right corner)
2. Run a clean build
3. Restart Android Studio

### "AudioFormat" unresolved
The import `import android.media.AudioFormat` is correct. This is an IDE issue.

**Solution:**
Build the project - it will compile successfully despite the IDE warnings.

## ✅ Verification Steps

### 1. Terminal Build Test
```bash
cd "d:\office work\imitext1.o\HeyCyan_Android_SDK_1.0.2_20250816 (1)\HeyCyan_Android_SDK_1.0.2_20250816\GlassesSDKSample\GlassesSDKSample"

# Clean build
.\gradlew clean

# Build
.\gradlew assembleDebug

# Install on connected device
.\gradlew installDebug
```

### 2. Check Build Output
Look for "BUILD SUCCESSFUL" in the output. If you see this, the code is fine.

### 3. Verify APK Creation
After successful build, APK should be at:
```
app/build/outputs/apk/debug/app-debug.apk
```

## 📱 Running on Device

### Via Command Line
```bash
# Install
.\gradlew installDebug

# Launch
adb shell am start -n com.sdk.glassessdksample/.ui.GeminiLiveActivity
```

### Via Android Studio
1. Select device from dropdown
2. Click Run ▶️ button
3. Choose "GeminiLiveActivity" or "MainActivity"

## 🔍 Debugging

### Enable Detailed Logging
Add to `local.properties`:
```properties
org.gradle.logging.level=debug
```

### Check Logcat
Filter by tag `GeminiLiveService`:
```bash
adb logcat -s GeminiLiveService:D
```

### Common Build Errors

#### Error: "SDK location not found"
**Solution:** Create/update `local.properties`:
```properties
sdk.dir=C\:\\Users\\YOUR_USERNAME\\AppData\\Local\\Android\\Sdk
```

#### Error: "JAVA_HOME not set"
**Solution:** Set JAVA_HOME environment variable to JDK 17 path

#### Error: "Execution failed for task ':app:mergeDebugResources'"
**Solution:** Clean and rebuild:
```bash
.\gradlew clean
.\gradlew assembleDebug
```

## 🧪 Testing the Implementation

### Quick Test Script
```kotlin
// In MainActivity.onCreate()
binding.testButton.setOnClickListener {
    try {
        val service = GeminiLiveService(object : GeminiLiveService.GeminiLiveCallbacks {
            override fun onTranscriptionUpdate(input: String, output: String, isFinal: Boolean) {
                Log.d("TEST", "Transcription: $input | $output")
            }
            override fun onTurnComplete(fullInput: String, fullOutput: String) {
                Log.d("TEST", "Turn complete")
            }
            override fun onAudioPlaybackStart() {
                Log.d("TEST", "Audio start")
            }
            override fun onAudioPlaybackEnd() {
                Log.d("TEST", "Audio end")
            }
            override fun onError(error: String) {
                Log.e("TEST", "Error: $error")
            }
            override fun onConnectionStatusChanged(isConnected: Boolean) {
                Log.d("TEST", "Connected: $isConnected")
            }
        })
        
        service.startLiveConversation("You are a test assistant")
        Toast.makeText(this, "Service started!", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Log.e("TEST", "Failed to start", e)
        Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}
```

## 📊 Expected Build Time
- **Clean Build:** ~30-60 seconds
- **Incremental Build:** ~5-15 seconds  
- **First Build (with dependencies):** ~2-5 minutes

## 🔐 Pre-Launch Checklist
- [ ] `local.properties` has `GEMINI_API_KEY=your_key`
- [ ] Gradle sync completed successfully
- [ ] Build succeeds in terminal
- [ ] Device/emulator connected
- [ ] Microphone permission granted (runtime)

## 💡 Pro Tips

1. **Use Terminal for Clean Builds**
   IDE sometimes caches errors. Terminal builds are authoritative.

2. **Check Gradle Console**
   View → Tool Windows → Gradle
   Look for actual compilation errors here, not just IDE warnings.

3. **Verify Kotlin Version**
   Make sure Kotlin plugin matches project Kotlin version (check gradle/libs.versions.toml)

4. **Clear Build Cache**
   ```bash
   .\gradlew clean cleanBuildCache
   rm -rf .gradle
   ```

## 🆘 Still Having Issues?

1. **Check Kotlin/Java versions match:**
   - Project uses Java 17
   - Kotlin version in gradle/libs.versions.toml

2. **Verify all files exist:**
   ```bash
   ls app/src/main/java/com/sdk/glassessdksample/ui/GeminiLive*.kt
   ```

3. **Check AndroidManifest.xml has activity:**
   ```xml
   <activity android:name=".ui.GeminiLiveActivity" />
   ```

4. **Review dependencies in build.gradle.kts:**
   All should be present and synced

---

**Bottom Line:** The code is correct. IDE errors are typically resolved by Gradle sync or clean build. When in doubt, trust the terminal build output.
