package com.sdk.glassessdksample.auth

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.sdk.glassessdksample.R

/**
 * Inline error display shared by the login and sign-up screens: a red line
 * under the offending field (with a red outline on the field itself), plus a
 * form-level banner for errors that aren't about one field.
 */
class AuthFormErrors(
    private val fields: Map<EditText, TextView>,
    private val formError: TextView
) {
    init {
        // Typing into a field clears its error and the form banner.
        fields.forEach { (input, _) ->
            input.doAfterTextChanged {
                clearField(input)
                formError.visibility = View.GONE
            }
        }
    }

    fun showField(input: EditText, message: String) {
        val label = fields[input] ?: return
        label.text = message
        label.visibility = View.VISIBLE
        input.setBackgroundResource(R.drawable.bg_auth_input_error)
    }

    fun showForm(message: String) {
        formError.text = message
        formError.visibility = View.VISIBLE
    }

    fun clearAll() {
        fields.keys.forEach(::clearField)
        formError.visibility = View.GONE
    }

    private fun clearField(input: EditText) {
        val label = fields[input] ?: return
        if (label.visibility == View.GONE) return
        label.visibility = View.GONE
        input.setBackgroundResource(R.drawable.bg_auth_input)
    }
}
