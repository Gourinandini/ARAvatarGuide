package com.example.aravatarguide

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.aravatarguide.databinding.ActivityBuildingBinding

class BuildingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBuildingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBuildingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val mode = intent.getStringExtra("mode") ?: "visitor"

        binding.btnMGeorge.setOnClickListener {
            val intent = Intent(this, FloorSelectionActivity::class.java)
            intent.putExtra("building", "M George Block")
            intent.putExtra("mode", mode)
            startActivity(intent)
        }

        binding.btnRamanujan.setOnClickListener {
            val intent = Intent(this, FloorSelectionActivity::class.java)
            intent.putExtra("building", "Ramanujan Block")
            intent.putExtra("mode", mode)
            startActivity(intent)
        }
    }
}