package com.example.aravatarguide

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.aravatarguide.databinding.ActivityPlacesBinding

class PlacesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlacesBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlacesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val building = intent.getStringExtra("building") ?: "Unknown"
        binding.tvBuildingName.text = "$building Block"

        val floorData = getFloorData(building)
        populateFloors(floorData)
    }

    private fun getFloorData(building: String): LinkedHashMap<String, List<String>> {
        return when (building) {
            "M George" -> linkedMapOf(
                "Floor 1" to listOf("Albert Einstein", "Library", "Reception", "MCA Dept"),
                "Floor 2" to listOf("Principal Office", "Office", "Civil Dept", "Visvesvaraya Hall"),
                "Floor 3" to listOf("EC Dept", "Mech Dept"),
                "Floor 4" to listOf("APJ Hall", "BSH Dept")
            )
            "Ramanujan" -> linkedMapOf(
                "Ground Floor" to listOf("Store", "Cafeteria"),
                "Floor 1" to listOf("EEE Dept", "Placement Cell", "CCF Lab"),
                "Floor 2" to listOf("Turning Lab", "Micheal Faraday Hall", "Steve Jobs Hall"),
                "Floor 3" to listOf("Foss Lab", "CSE Dept"),
                "Floor 4" to listOf("AIDS Dept", "Hinton Lab", "Van Rossum Lab", "JML Lab"),
                "Floor 5" to listOf("Experiential Lab", "Staff Room AIDS"),
                "Floor 6" to listOf("Cyber Dept", "EXCEL Lab")
            )
            else -> linkedMapOf()
        }
    }

    private fun populateFloors(floorData: LinkedHashMap<String, List<String>>) {
        val container = binding.floorContainer

        for ((floorName, rooms) in floorData) {
            // Floor header
            val floorHeader = TextView(this).apply {
                text = "\uD83C\uDFE2  $floorName"
                textSize = 18f
                setTextColor(ContextCompat.getColor(context, R.color.accent_gold))
                setPadding(0, dpToPx(20), 0, dpToPx(10))
                setTypeface(null, Typeface.BOLD)
            }
            container.addView(floorHeader)

            // Room items
            val roomsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(16), 0, 0, dpToPx(8))
            }

            for (room in rooms) {
                val roomView = TextView(this).apply {
                    text = "  \uD83D\uDCCD  $room"
                    textSize = 15f
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    setPadding(0, dpToPx(6), 0, dpToPx(6))
                }
                roomsContainer.addView(roomView)
            }

            container.addView(roomsContainer)

            // Divider
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)
                ).apply {
                    setMargins(0, dpToPx(4), 0, dpToPx(4))
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.text_hint))
                alpha = 0.3f
            }
            container.addView(divider)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}