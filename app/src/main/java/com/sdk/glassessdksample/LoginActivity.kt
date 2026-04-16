package com.sdk.glassessdksample

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sdk.glassessdksample.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener {
            performLogin()
        }

        binding.signUpLink.setOnClickListener {
            startActivity(Intent(this, SignUpActivity::class.java))
        }
    }

    private fun performLogin() {
        val userId = binding.etUserId.text.toString().trim()
        val password = binding.etPassword.text.toString().trim()

        if (userId.isEmpty()) {
            Toast.makeText(this, "Please enter your User ID", Toast.LENGTH_SHORT).show()
            return
        }

        if (password.isEmpty()) {
            Toast.makeText(this, "Please enter your password", Toast.LENGTH_SHORT).show()
            return
        }

        getSharedPreferences("IMI_PREFS", MODE_PRIVATE).edit().apply {
            putBoolean("is_logged_in", true)
            putString("user_id", userId)
            apply()
        }

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
