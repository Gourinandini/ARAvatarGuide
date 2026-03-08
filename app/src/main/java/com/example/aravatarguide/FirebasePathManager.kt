package com.example.aravatarguide

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class FirebasePathManager {

    private val database = FirebaseDatabase.getInstance()
    private val floorMapsRef = database.getReference("floorMaps")

    companion object {
        private const val TAG = "FirebasePathManager"

        /** Sanitise a string so it is safe to use as a Firebase Realtime Database key.
         *  Firebase keys cannot contain '.', '$', '#', '[', ']', or '/'. */
        fun sanitizeKey(raw: String): String =
            raw.replace(Regex("[.\\$#\\[\\]/]"), "_").trim()
    }

    init {
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
        } catch (e: Exception) {
            Log.w(TAG, "Firebase persistence already enabled or not available")
        }
    }

    /** Returns the Firebase reference for a specific building + floor.
     *  Path: floorMaps/{building}/{floor} */
    private fun getFloorRef(building: String, floor: String) =
        floorMapsRef.child(sanitizeKey(building)).child(sanitizeKey(floor))

    // ─────────────────────── SAVE ───────────────────────

    fun saveFloorGraph(building: String, floor: String, floorGraph: FloorGraph, onComplete: (Boolean) -> Unit) {
        Log.d(TAG, "Saving floor graph for $building / $floor with ${floorGraph.getNodeCount()} nodes")

        val graphData = hashMapOf(
            "nodes" to floorGraph.nodes,
            "adjacencyList" to floorGraph.adjacencyList,
            "timestamp" to System.currentTimeMillis()
        )

        getFloorRef(building, floor).setValue(graphData)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Floor graph saved successfully for $building / $floor!")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to save floor graph: ${e.message}", e)
                onComplete(false)
            }
    }

    // ─────────────────────── LOAD ───────────────────────

    fun loadFloorGraph(building: String, floor: String, onComplete: (FloorGraph?) -> Unit) {
        Log.d(TAG, "Loading floor graph for $building / $floor from Firebase...")

        getFloorRef(building, floor).addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    if (!snapshot.exists()) {
                        Log.w(TAG, "⚠️ No data exists for $building / $floor")
                        onComplete(null)
                        return
                    }

                    Log.d(TAG, "Snapshot exists for $building / $floor")

                    val floorGraph = FloorGraph()

                    val nodesSnapshot = snapshot.child("nodes")
                    if (nodesSnapshot.exists()) {
                        for (nodeSnapshot in nodesSnapshot.children) {
                            try {
                                val node = nodeSnapshot.getValue(GraphNode::class.java)
                                if (node != null) {
                                    floorGraph.nodes[node.id] = node
                                    Log.d(TAG, "Loaded node: ${node.name} at ${node.position}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error deserializing node: ${e.message}")
                            }
                        }
                    }

                    val adjSnapshot = snapshot.child("adjacencyList")
                    if (adjSnapshot.exists()) {
                        for (nodeEdgesSnapshot in adjSnapshot.children) {
                            val nodeId = nodeEdgesSnapshot.key ?: continue
                            val edges = mutableListOf<GraphEdge>()

                            for (edgeSnapshot in nodeEdgesSnapshot.children) {
                                try {
                                    val edge = edgeSnapshot.getValue(GraphEdge::class.java)
                                    if (edge != null) {
                                        edges.add(edge)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error deserializing edge: ${e.message}")
                                }
                            }

                            if (edges.isNotEmpty()) {
                                floorGraph.adjacencyList[nodeId] = edges
                            }
                        }
                    }

                    if (floorGraph.isEmpty()) {
                        Log.w(TAG, "⚠️ Loaded graph is empty for $building / $floor")
                        onComplete(null)
                    } else {
                        Log.d(TAG, "✅ Loaded graph for $building / $floor: ${floorGraph.getNodeCount()} nodes, ${floorGraph.getNamedWaypointCount()} named")
                        onComplete(floorGraph)
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error loading floor graph: ${e.message}", e)
                    e.printStackTrace()
                    onComplete(null)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "❌ Firebase database error: ${error.message}")
                onComplete(null)
            }
        })
    }

    // ─────────────────────── DELETE ───────────────────────

    fun deleteFloorGraph(building: String, floor: String, onComplete: (Boolean) -> Unit) {
        Log.d(TAG, "Deleting floor graph for $building / $floor...")
        getFloorRef(building, floor).removeValue()
            .addOnSuccessListener {
                Log.d(TAG, "✅ Floor graph deleted for $building / $floor")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to delete floor graph: ${e.message}")
                onComplete(false)
            }
    }
}