package com.sdk.glassessdksample.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.fragment.app.Fragment
import java.io.Serializable
import kotlin.Pair

// -------------------------------------------------------------
// FUNCTION: Start Activity from Activity
// -------------------------------------------------------------
inline fun <reified T : Activity> Activity.startKtxActivity(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = createPairList(value, values)
    startActivity(getIntent<T>(flags, extra, list))
}

// -------------------------------------------------------------
// FUNCTION: Start Activity from Fragment
// -------------------------------------------------------------
inline fun <reified T : Activity> Fragment.startKtxActivity(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) = activity?.let {
    val list = createPairList(value, values)
    startActivity(it.getIntent<T>(flags, extra, list))
}

// -------------------------------------------------------------
// FUNCTION: Start Activity from ANY Context (including Services)
// -------------------------------------------------------------
inline fun <reified T : Activity> Context.startKtxActivity(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = createPairList(value, values)
    startActivity(getIntent<T>(flags, extra, list))
}

// -------------------------------------------------------------
// FUNCTION: Start Activity For Result
// -------------------------------------------------------------
inline fun <reified T : Activity> Activity.startKtxActivityForResult(
    requestCode: Int,
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = createPairList(value, values)
    startActivityForResult(getIntent<T>(flags, extra, list), requestCode)
}

// -------------------------------------------------------------
// Fragment version of startActivityForResult
// -------------------------------------------------------------
inline fun <reified T : Activity> Fragment.startKtxActivityForResult(
    requestCode: Int,
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) = activity?.let {
    val list = createPairList(value, values)
    startActivityForResult(it.getIntent<T>(flags, extra, list), requestCode)
}

// -------------------------------------------------------------
// CORE: Build Intent with Extra Values (SAFE + Updated)
// -------------------------------------------------------------
inline fun <reified T : Context> Context.getIntent(
    flags: Int? = null,
    extra: Bundle? = null,
    pairs: List<Pair<String, Any>?>? = null
): Intent {

    val intent = Intent(this, T::class.java)

    // 🔥 IMPORTANT: If starting from background service → avoid crash
    if (this !is Activity) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    flags?.let { intent.addFlags(it) }
    extra?.let { intent.putExtras(it) }

    pairs?.forEach { pair ->
        pair?.let {
            val key = it.first
            val value = it.second
            when (value) {
                is Int -> intent.putExtra(key, value)
                is Byte -> intent.putExtra(key, value)
                is Char -> intent.putExtra(key, value)
                is Short -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
                is CharSequence -> intent.putExtra(key, value)
                is Parcelable -> intent.putExtra(key, value)
                is Serializable -> intent.putExtra(key, value)
                is IntArray -> intent.putExtra(key, value)
                is BooleanArray -> intent.putExtra(key, value)
                is FloatArray -> intent.putExtra(key, value)
                is DoubleArray -> intent.putExtra(key, value)
                is LongArray -> intent.putExtra(key, value)
                is Bundle -> intent.putExtra(key, value)
                is ArrayList<*> -> intent.putExtra(key, value)
                is Array<*> -> intent.putExtra(key, value)
                else -> { /* unsupported type */ }
            }
        }
    }

    return intent
}

// -------------------------------------------------------------
// FUNCTION: Wake Up Activity (Bring to foreground + wake screen)
// -------------------------------------------------------------
inline fun <reified T : Activity> Context.wakeUpActivity(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = createPairList(value, values)
    val wakeFlags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
    
    val combinedFlags = flags?.let { it or wakeFlags } ?: wakeFlags
    startActivity(getIntent<T>(combinedFlags, extra, list))
}

// -------------------------------------------------------------
// FUNCTION: Wake Up Activity from Activity
// -------------------------------------------------------------
inline fun <reified T : Activity> Activity.wakeUpActivity(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = createPairList(value, values)
    val wakeFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
    
    val combinedFlags = flags?.let { it or wakeFlags } ?: wakeFlags
    startActivity(getIntent<T>(combinedFlags, extra, list))
}

// -------------------------------------------------------------
// FUNCTION: Wake Up Activity from Fragment
// -------------------------------------------------------------
inline fun <reified T : Activity> Fragment.wakeUpActivity(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) = activity?.let {
    val list = createPairList(value, values)
    val wakeFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
    
    val combinedFlags = flags?.let { f -> f or wakeFlags } ?: wakeFlags
    startActivity(it.getIntent<T>(combinedFlags, extra, list))
}

// -------------------------------------------------------------
// CUSTOM WAKE UP CALL: "hi imi"
// -------------------------------------------------------------
inline fun <reified T : Activity> Context.hiImi(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = createPairList(value, values)
    val wakeFlags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
    
    val combinedFlags = flags?.let { it or wakeFlags } ?: wakeFlags
    startActivity(getIntent<T>(combinedFlags, extra, list))
}

// -------------------------------------------------------------
// CUSTOM WAKE UP CALL: "hi imi" from Activity
// -------------------------------------------------------------
inline fun <reified T : Activity> Activity.hiImi(
    flags: Int? = null,
    extra: Bundle? = null,
    value: Pair<String, Any>? = null,
    values: Collection<Pair<String, Any>?>? = null
) {
    val list = createPairList(value, values)
    val wakeFlags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
    
    val combinedFlags = flags?.let { it or wakeFlags } ?: wakeFlags
    startActivity(getIntent<T>(combinedFlags, extra, list))
}

// -------------------------------------------------------------
// CUSTOM WAKE UP CALL: "hi imi" from Fragment
// -------------------------------------------------------------


// -------------------------------------------------------------
// Helper: Combine optional pairs into list
// -------------------------------------------------------------
@PublishedApi
internal fun createPairList(
    value: Pair<String, Any>?,
    values: Collection<Pair<String, Any>?>?
): ArrayList<Pair<String, Any>?> {
    val list = ArrayList<Pair<String, Any>?>()
    value?.let { list.add(it) }
    values?.let { list.addAll(it) }
    return list
}
