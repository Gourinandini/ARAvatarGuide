package com.example.aravatarguide

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aravatarguide.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnLogin.setOnClickListener {
            val password = binding.etPassword.text.toString()
            if (password == "2004") {
                val intent = Intent(this, BuildingActivity::class.java)
                intent.putExtra("mode", "host")
                startActivity(intent)
                finish()
            } else {
                Toast.makeText(this, "Incorrect password", Toast.LENGTH_SHORT).show()
            }
        }
    }
}