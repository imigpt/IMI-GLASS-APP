package com.sdk.glassessdksample.ui.swiggy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Developer hook for inspecting Swiggy's live MCP contract from a terminal.
 *
 * Swiggy publishes no catalogue of tool names or required arguments, so the
 * only way to learn the real contract is to ask a live server holding a real
 * token. Driving that through the voice model is unreliable — the model has to
 * decide to call the tool — so this exposes it as a broadcast instead:
 *
 *   adb shell am broadcast -a com.aselea.imiglass.SWIGGY_DUMP_SCHEMAS
 *   adb logcat -s SwiggySchema
 *
 * Debug-only: it is registered in the debug manifest, so it does not exist in
 * a release build. It reads schemas and orders nothing.
 */
class SwiggyDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        Log.i("SwiggySchema", "Broadcast received: ${intent.action}")
        // goAsync would be tidier, but the dump can take several seconds across
        // three servers and the receiver's window is short; the app process is
        // alive anyway when this is used.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SwiggyMcpClient.dumpAllSchemas(app)
            } catch (e: Exception) {
                Log.e("SwiggySchema", "Dump failed", e)
            }
        }
    }
}
