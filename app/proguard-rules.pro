# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Picovoice Porcupine classes for wake-word detection
-keep class ai.picovoice.porcupine.** { *; }
-dontwarn ai.picovoice.porcupine.**

# Keep EventBus classes
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# Oudmon/Smart Glasses SDK ke liye rules - keep reflection targets
-keep class com.oudmon.ble.** { *; }
-keepclassmembers class com.oudmon.ble.** { *; }

# Specifically keep the class that the SDK reflects into
-keep class com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp { *; }