package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.auth.AuthApi
import com.sdk.glassessdksample.auth.AuthFormErrors
import com.sdk.glassessdksample.databinding.ActivitySignUpBinding
import com.sdk.glassessdksample.utils.SystemBarsInsets

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding
    private lateinit var errors: AuthFormErrors
    private val authApi by lazy { AuthApi(this) }
    private var isSubmitting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarsInsets.apply(this)

        setupUI()
    }

    private fun setupUI() {
        errors = AuthFormErrors(
            fields = mapOf(
                binding.fullNameInput to binding.fullNameError,
                binding.emailInput to binding.emailError,
                binding.passwordInput to binding.passwordError,
                binding.confirmPasswordInput to binding.confirmPasswordError
            ),
            formError = binding.formError
        )

        // Sign Up button
        binding.signUpButton.setOnClickListener {
            performSignUp()
        }

        // Login link
        binding.loginLink.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun performSignUp() {
        if (isSubmitting) return
        errors.clearAll()

        val fullName = binding.fullNameInput.text.toString().trim()
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString().trim()
        val confirmPassword = binding.confirmPasswordInput.text.toString().trim()

        // Validate every field so all problems show at once; focus the first.
        val invalid = mutableListOf<EditText>()
        fun fail(input: EditText, message: String) {
            errors.showField(input, message)
            invalid += input
        }

        when {
            fullName.isEmpty() -> fail(binding.fullNameInput, "Please enter your full name")
            fullName.length < 2 -> fail(binding.fullNameInput, "Name must be at least 2 characters")
        }
        when {
            email.isEmpty() -> fail(binding.emailInput, "Please enter your email address")
            !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() ->
                fail(binding.emailInput, "Please enter a valid email address (e.g. name@example.com)")
        }
        when {
            password.isEmpty() -> fail(binding.passwordInput, "Please create a password")
            // Matches the backend rule; it rejects anything shorter.
            password.length < MIN_PASSWORD_LENGTH ->
                fail(binding.passwordInput, "Password must be at least $MIN_PASSWORD_LENGTH characters")
        }
        when {
            confirmPassword.isEmpty() -> fail(binding.confirmPasswordInput, "Please confirm your password")
            password != confirmPassword -> fail(binding.confirmPasswordInput, "Passwords do not match")
        }

        if (invalid.isNotEmpty()) {
            invalid.first().requestFocus()
            return
        }

        setSubmitting(true)
        authApi.register(fullName, email, password, confirmPassword) { result ->
            when (result) {
                is AuthApi.Result.Success -> {
                    // Registration succeeds but returns no token — log in to obtain one.
                    loginAfterRegister(email, password)
                }
                is AuthApi.Result.Error -> {
                    setSubmitting(false)
                    showRegisterError(result)
                }
            }
        }
    }

    private fun showRegisterError(error: AuthApi.Result.Error) {
        if (error.code == AuthApi.CODE_EMAIL_TAKEN) {
            errors.showField(binding.emailInput, "An account with this email already exists")
            errors.showForm("This email is already registered. Tap Log In below to sign in instead.")
            binding.emailInput.requestFocus()
        } else {
            errors.showForm(error.message)
        }
    }

    private fun loginAfterRegister(email: String, password: String) {
        authApi.login(email, password) { result ->
            setSubmitting(false)
            when (result) {
                is AuthApi.Result.Success -> {
                    Toast.makeText(this, "Account created successfully!", Toast.LENGTH_SHORT).show()
                    goToNextScreen()
                }
                is AuthApi.Result.Error -> {
                    // Account exists now; send them to login to finish signing in.
                    Toast.makeText(
                        this,
                        "Account created! Please log in to continue.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(this, LoginActivity::class.java))
                    finish()
                }
            }
        }
    }

    private fun goToNextScreen() {
        // Onboarding screen hidden: go straight to device selection.
        val intent = Intent(this, com.sdk.glassessdksample.ui.DeviceSelectionActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun setSubmitting(submitting: Boolean) {
        isSubmitting = submitting
        binding.signUpButton.isEnabled = !submitting
        binding.signUpButton.text = if (submitting) "Creating account…" else "Create Account"
    }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
    }
}
