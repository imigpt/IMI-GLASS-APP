package com.sdk.glassessdksample.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * AI speech goes to the glasses or nowhere — never the phone speaker.
 *
 * Android has no app-wide "never use the speaker" switch: when a Bluetooth route
 * is missing (glasses not connected, SCO still handshaking, the link dropped
 * mid-reply) it quietly plays our audio on the phone instead. Every place that
 * speaks an AI reply checks here first and stays silent (the reply is still
 * shown as text) when the glasses are not an available output.
 *
 * Which device counts as "the glasses" comes from [PreferredAudioDeviceResolver],
 * so Mark 1's "F-16" name filter and Mark 2's pairing record both apply.
 */
object GlassesAudioOutput {
    private const val TAG = "GlassesAudioOutput"

    /** Phone-side outputs an AI reply must never end up on. */
    private val PHONE_OUTPUT_TYPES = buildSet {
        add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        add(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE)
    }

    /**
     * The glasses as an audio output, or null if they aren't available right now.
     * [preferA2dp] picks the high-quality media route when both are present.
     */
    fun find(context: Context, preferA2dp: Boolean = false): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val sco = PreferredAudioDeviceResolver.findGlasses(context, outputs, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            val a2dp = PreferredAudioDeviceResolver.findGlasses(context, outputs, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (preferA2dp) a2dp ?: sco else sco ?: a2dp
        } catch (e: Exception) {
            Log.w(TAG, "Could not list audio outputs: ${e.message}")
            null
        }
    }

    /** True when the glasses can play audio right now. */
    fun isAvailable(context: Context): Boolean = find(context) != null

    /** True when the glasses' media route (A2DP — what STREAM_MUSIC/TTS uses) is up. */
    fun hasMediaRoute(context: Context): Boolean = hasType(context, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)

    /** True when the glasses' call route (SCO — what STREAM_VOICE_CALL uses) is up. */
    fun hasCallRoute(context: Context): Boolean = hasType(context, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

    private fun hasType(context: Context, type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
            PreferredAudioDeviceResolver.findGlasses(context, am.getDevices(AudioManager.GET_DEVICES_OUTPUTS), type) != null
        } catch (e: Exception) {
            false
        }
    }

    /** True when [device] is the phone's own speaker or earpiece. */
    fun isPhoneOutput(device: AudioDeviceInfo?): Boolean =
        device != null && device.type in PHONE_OUTPUT_TYPES
}
