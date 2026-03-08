package com.example.aravatarguide

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.aravatarguide.databinding.ActivityPlacesBinding

class PlacesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlacesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlacesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val building = intent.getStringExtra("building") ?: ""
        val floor = intent.getStringExtra("floor") ?: ""
        val places = intent.getStringArrayListExtra("places") ?: arrayListOf()

        binding.tvBuildingName.text = "$building — $floor"

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, places)
        binding.lvPlaces.adapter = adapter

        binding.lvPlaces.setOnItemClickListener { _, _, position, _ ->
            val destination = places[position]
            val intent = Intent(this, VisitorActivity::class.java)
            intent.putExtra("destination", destination)
            intent.putExtra("building", building)
            intent.putExtra("floor", floor)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        }
    }
}