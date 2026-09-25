package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.auth.AuthApi
import com.sdk.glassessdksample.auth.AuthFormErrors
import com.sdk.glassessdksample.databinding.ActivityLoginBinding
import com.sdk.glassessdksample.utils.SystemBarsInsets

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var errors: AuthFormErrors
    private val authApi by lazy { AuthApi(this) }
    private var isSubmitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        errors = AuthFormErrors(
            fields = mapOf(
                binding.etUserId to binding.tvEmailError,
                binding.etPassword to binding.tvPasswordError
            ),
            formError = binding.tvFormError
        )

        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        binding.signUpLink.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
    }

    private fun performLogin() {
        if (isSubmitting) return
        errors.clearAll()

        val email = binding.etUserId.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        var firstInvalid: android.widget.EditText? = null
        when {
            email.isEmpty() -> {
                errors.showField(binding.etUserId, "Please enter your email address")
                firstInvalid = binding.etUserId
            }
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() -> {
                errors.showField(binding.etUserId, "Please enter a valid email address (e.g. name@example.com)")
                firstInvalid = binding.etUserId
            }
        }
        if (password.isEmpty()) {
            errors.showField(binding.etPassword, "Please enter your password")
            if (firstInvalid == null) firstInvalid = binding.etPassword
        }
        if (firstInvalid != null) {
            firstInvalid.requestFocus()
            return
        }

        setSubmitting(true)
        authApi.login(email, password) { result ->
            setSubmitting(false)
            when (result) {
                is AuthApi.Result.Success -> goToNextScreen()
                is AuthApi.Result.Error -> showLoginError(result)
            }
        }
    }

    private fun showLoginError(error: AuthApi.Result.Error) {
        if (error.code == AuthApi.CODE_INVALID_CREDENTIALS) {
            // The backend deliberately returns the same error for "wrong password"
            // and "no such account", so cover both in the message. Clear the
            // password first: its text watcher would otherwise hide the banner.
            binding.etPassword.text?.clear()
            binding.etPassword.requestFocus()
            errors.showForm(
                "Incorrect email or password.\n" +
                    "Check your password and try again. If you don't have an account yet, tap Sign Up below."
            )
        } else {
            errors.showForm(error.message)
        }
    }

    private fun goToNextScreen() {
        // A ChatGPT/Claude profile imported before signing in has nowhere to go
        // until now. No-op when there is nothing pending or it is already up.
        com.sdk.glassessdksample.ui.sync.ImportedProfileSync.syncPending(applicationContext)

        // Onboarding screen hidden. A returning user already has a device saved,
        // so send them to it rather than making them pick again; only a first-time
        // login (nothing saved) sees the selector.
        val target = when (com.sdk.glassessdksample.ui.DevicePreferenceManager.getDeviceType(this)) {
            com.sdk.glassessdksample.ui.DeviceType.MARK1 ->
                com.sdk.glassessdksample.ui.Mark1MainActivity::class.java
            com.sdk.glassessdksample.ui.DeviceType.MARK2 ->
                MainActivity::class.java
            null -> com.sdk.glassessdksample.ui.DeviceSelectionActivity::class.java
        }
        val intent = Intent(this, target)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setSubmitting(submitting: Boolean) {
        isSubmitting = submitting
        binding.btnLogin.isEnabled = !submitting
        binding.btnLogin.text = if (submitting) "Logging in…" else "Login"
    }
}
