package com.sdk.glassessdksample.ui;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

public class BluetoothUtils {

    private static final String TAG = "BluetoothUtils";

    /**
     * FULL BLUETOOTH CHECK:
     * ✔ Device supports Bluetooth
     * ✔ Device supports BLE
     * ✔ Android 12+ Bluetooth permissions granted
     * ✔ Bluetooth turned ON
     */
    @SuppressLint("MissingPermission")
    public static boolean isBluetoothReady(Context context) {

        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        // Device does NOT support Bluetooth
        if (adapter == null) {
            Log.e(TAG, "❌ Device does NOT support Bluetooth");
            return false;
        }

        // Device does NOT support BLE
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.e(TAG, "❌ Device does NOT support BLE");
            return false;
        }

        // Android 12+ → needs runtime permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            boolean hasConnect =
                    context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                            == PackageManager.PERMISSION_GRANTED;

            boolean hasScan =
                    context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                            == PackageManager.PERMISSION_GRANTED;

            if (!hasConnect || !hasScan) {
                Log.e(TAG, "❌ Bluetooth permissions NOT granted (Android 12+)");
                return false;
            }
        }

        // Bluetooth is OFF
        if (!adapter.isEnabled()) {
            Log.w(TAG, "⚠️ Bluetooth is OFF");
            return false;
        }

        return true;
    }

    /**
     * SIMPLE BLUETOOTH ENABLED CHECK (Backward compatibility)
     */
    public static boolean isEnabledBluetooth(Context context) {
        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);

        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        return adapter != null && adapter.isEnabled();
    }

    public static boolean hasLollipop() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }
}
