package com.example.aravatarguide

import com.google.firebase.database.Exclude
import com.google.firebase.database.IgnoreExtraProperties
import kotlin.math.sqrt

@IgnoreExtraProperties
data class GraphNode(
    var id: String = "",
    var name: String = "",
    var position: List<Double> = emptyList(),
    var isNamedWaypoint: Boolean = false,
    var isEmergencyExit: Boolean = false,
    var isRestrictedArea: Boolean = false
) {
    @Exclude
    fun toFloatArray(): FloatArray {
        return floatArrayOf(
            position.getOrNull(0)?.toFloat() ?: 0f,
            position.getOrNull(1)?.toFloat() ?: 0f,
            position.getOrNull(2)?.toFloat() ?: 0f
        )
    }
}

@IgnoreExtraProperties
data class GraphEdge(
    var from: String = "",
    var to: String = "",
    var distance: Double = 0.0
)

@IgnoreExtraProperties
class FloorGraph() {
    var nodes: MutableMap<String, GraphNode> = mutableMapOf()
    var adjacencyList: MutableMap<String, MutableList<GraphEdge>> = mutableMapOf()

    fun addNode(node: GraphNode) {
        nodes[node.id] = node
        if (!adjacencyList.containsKey(node.id)) {
            adjacencyList[node.id] = mutableListOf()
        }
    }

    fun addEdge(fromId: String, toId: String, distance: Float) {
        adjacencyList.getOrPut(fromId) { mutableListOf() }
            .add(GraphEdge(fromId, toId, distance.toDouble()))
        adjacencyList.getOrPut(toId) { mutableListOf() }
            .add(GraphEdge(toId, fromId, distance.toDouble()))
    }

    @Exclude
    fun getNeighborsOf(node: GraphNode): List<GraphNode> {
        return adjacencyList[node.id]?.mapNotNull { nodes[it.to] } ?: emptyList()
    }

    @Exclude
    fun getAllNodes(): List<GraphNode> {
        return nodes.values.toList()
    }

    @Exclude
    fun getNamedWaypoints(): List<GraphNode> {
        return nodes.values.filter { it.isNamedWaypoint }
    }

    @Exclude
    fun getEmergencyExits(): List<GraphNode> {
        return nodes.values.filter { it.isEmergencyExit }
    }

    @Exclude
    fun getRestrictedAreas(): List<GraphNode> {
        return nodes.values.filter { it.isRestrictedArea }
    }

    // Main distance calculation for Double positions
    fun calculateDistance(pos1: List<Double>, pos2: List<Double>): Float {
        if (pos1.size < 3 || pos2.size < 3) return Float.MAX_VALUE
        val dx = pos1[0] - pos2[0]
        val dy = pos1[1] - pos2[1]
        val dz = pos1[2] - pos2[2]
        return sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }

    // Helper for Float to Double comparison
    fun calculateDistanceFromFloat(pos1: List<Float>, pos2: List<Double>): Float {
        if (pos1.size < 3 || pos2.size < 3) return Float.MAX_VALUE
        val dx = pos1[0].toDouble() - pos2[0]
        val dy = pos1[1].toDouble() - pos2[1]
        val dz = pos1[2].toDouble() - pos2[2]
        return sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }

    fun findNearestNode(position: List<Float>): GraphNode? {
        if (nodes.isEmpty()) return null
        return nodes.values.minByOrNull { calculateDistanceFromFloat(position, it.position) }
    }

    fun findNearestNamedWaypoint(position: List<Float>): GraphNode? {
        val namedWaypoints = getNamedWaypoints()
        if (namedWaypoints.isEmpty()) return null
        return namedWaypoints.minByOrNull { calculateDistanceFromFloat(position, it.position) }
    }

    @Exclude
    fun isEmpty(): Boolean {
        return nodes.isEmpty()
    }

    @Exclude
    fun getNodeCount(): Int {
        return nodes.size
    }

    @Exclude
    fun getNamedWaypointCount(): Int {
        return getNamedWaypoints().size
    }

    fun clear() {
        nodes.clear()
        adjacencyList.clear()
    }
}