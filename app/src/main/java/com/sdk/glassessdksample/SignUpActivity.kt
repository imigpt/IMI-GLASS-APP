package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.databinding.ActivitySignUpBinding

class SignUpActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignUpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignUpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // Back button
        binding.backButton.setOnClickListener {
            finish()
        }

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
        val fullName = binding.fullNameInput.text.toString().trim()
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString().trim()
        val confirmPassword = binding.confirmPasswordInput.text.toString().trim()

        // Validation
        if (fullName.isEmpty()) {
            showError("Please enter your full name")
            return
        }

        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Please enter a valid email address")
            return
        }

        if (password.isEmpty() || password.length < 6) {
            showError("Password must be at least 6 characters")
            return
        }

        if (password != confirmPassword) {
            showError("Passwords do not match")
            return
        }

        // Save user data to SharedPreferences
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            putString("user_name", fullName)
            putString("user_email", email)
            putBoolean("is_logged_in", true)
            apply()
        }

        showSuccess("Account created successfully!")
        
        // Go to home screen after 2 seconds
        binding.root.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2000)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showSuccess(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
