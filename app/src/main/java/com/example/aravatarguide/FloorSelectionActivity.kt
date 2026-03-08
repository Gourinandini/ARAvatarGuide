package com.example.aravatarguide

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.example.aravatarguide.databinding.ActivityFloorSelectionBinding

class FloorSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFloorSelectionBinding

    private val mGeorgeFloors = linkedMapOf(
        "Floor 1" to listOf("Albert Einstein Hall", "Library", "Reception", "MCA Dept"),
        "Floor 2" to listOf("Principal Office", "Office", "Civil Dept", "Visvesvaraya Hall"),
        "Floor 3" to listOf("EC Dept", "Mech Dept"),
        "Floor 4" to listOf("APJ Hall", "BSH Dept")
    )

    private val ramanujanFloors = linkedMapOf(
        "Ground Floor" to listOf("Store", "Cafeteria"),
        "Floor 1" to listOf("EEE Dept", "Placement Cell", "CCF Lab"),
        "Floor 2" to listOf("Turning Lab", "Micheal Faraday Hall", "Steve Jobs Hall"),
        "Floor 3" to listOf("Foss Lab", "CSE Dept"),
        "Floor 4" to listOf("AIDS Dept", "Hinton Lab", "Van Rossum Lab", "JML Lab"),
        "Floor 5" to listOf("Experiential Lab", "Staff Room AIDS"),
        "Floor 6" to listOf("Cyber Dept", "EXCEL Lab")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFloorSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val building = intent.getStringExtra("building") ?: "M George Block"

        val floors = when (building) {
            "M George Block" -> mGeorgeFloors
            "Ramanujan Block" -> ramanujanFloors
            else -> emptyMap()
        }

        binding.tvBuildingTitle.text = building
        binding.tvSubtitle.text = "Select a floor to view places"

        val floorNames = floors.keys.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, floorNames)
        binding.lvFloors.adapter = adapter

        val mode = intent.getStringExtra("mode") ?: "visitor"

        binding.lvFloors.setOnItemClickListener { _, _, position, _ ->
            val floorName = floorNames[position]
            if (mode == "host") {
                // Host mode: go to ARActivity to map this floor
                val intent = Intent(this, ARActivity::class.java)
                intent.putExtra("building", building)
                intent.putExtra("floor", floorName)
                startActivity(intent)
            } else {
                // Visitor mode: show places in this floor
                val places = floors[floorName] ?: emptyList()
                val intent = Intent(this, PlacesActivity::class.java)
                intent.putExtra("building", building)
                intent.putExtra("floor", floorName)
                intent.putStringArrayListExtra("places", ArrayList(places))
                startActivity(intent)
            }
        }
    }
}
