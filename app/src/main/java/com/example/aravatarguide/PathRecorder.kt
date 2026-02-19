package com.example.aravatarguide

import com.google.ar.core.Pose
import java.util.UUID
import kotlin.math.sqrt

class PathRecorder {

    private var floorGraph = FloorGraph()
    private var isRecording = false
    private var lastRecordedPosition: List<Double>? = null
    private var lastNodeId: String? = null
    private var startPointName: String = ""

    companion object {
        private const val MIN_DISTANCE_BETWEEN_POINTS = 0.3f // 30cm
    }

    fun startRecording(startName: String) {
        isRecording = true
        startPointName = startName
        lastRecordedPosition = null
        lastNodeId = null
        floorGraph.clear()
    }

    fun updatePosition(pose: Pose): Boolean {
        if (!isRecording) return false

        val currentPos = listOf(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble())

        if (lastRecordedPosition == null) {
            val nodeId = UUID.randomUUID().toString()
            val node = GraphNode(
                id = nodeId,
                name = startPointName,
                position = currentPos,
                isNamedWaypoint = true
            )
            floorGraph.addNode(node)
            lastRecordedPosition = currentPos
            lastNodeId = nodeId
            return true
        }

        if (shouldCreateWaypoint(currentPos)) {
            val nodeId = UUID.randomUUID().toString()
            val node = GraphNode(
                id = nodeId,
                name = "",
                position = currentPos,
                isNamedWaypoint = false
            )
            floorGraph.addNode(node)

            lastNodeId?.let {
                val distance = calculateDistance(lastRecordedPosition!!, currentPos)
                floorGraph.addEdge(it, nodeId, distance)
            }

            lastRecordedPosition = currentPos
            lastNodeId = nodeId
            return true
        }

        return false
    }

    fun markNamedWaypoint(pose: Pose, name: String, isEmergencyExit: Boolean, isRestrictedArea: Boolean = false): Boolean {
        if (name.isBlank()) return false

        if (floorGraph.getNamedWaypoints().any { it.name.equals(name, ignoreCase = true) }) {
            return false // Duplicate name
        }

        val position = listOf(pose.tx().toDouble(), pose.ty().toDouble(), pose.tz().toDouble())
        val nodeId = UUID.randomUUID().toString()

        val node = GraphNode(
            id = nodeId,
            name = name,
            position = position,
            isNamedWaypoint = true,
            isEmergencyExit = isEmergencyExit,
            isRestrictedArea = isRestrictedArea
        )

        floorGraph.addNode(node)

        lastNodeId?.let {
            val distance = calculateDistance(lastRecordedPosition ?: position, position)
            floorGraph.addEdge(it, nodeId, distance)
        }

        lastRecordedPosition = position
        lastNodeId = nodeId

        return true
    }

    private fun shouldCreateWaypoint(currentPos: List<Double>): Boolean {
        val lastPos = lastRecordedPosition ?: return true
        val distance = calculateDistance(lastPos, currentPos)
        return distance >= MIN_DISTANCE_BETWEEN_POINTS
    }

    private fun calculateDistance(pos1: List<Double>, pos2: List<Double>): Float {
        val dx = pos1[0] - pos2[0]
        val dy = pos1[1] - pos2[1]
        val dz = pos1[2] - pos2[2]
        return sqrt(dx * dx + dy * dy + dz * dz).toFloat()
    }

    fun stopRecording(): FloorGraph {
        isRecording = false
        return floorGraph
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    fun getWaypointCount(): Int = floorGraph.getNodeCount()

    fun getNamedWaypointCount(): Int = floorGraph.getNamedWaypointCount()

    fun getRecordedPoints(): List<GraphNode> = floorGraph.getAllNodes()
}