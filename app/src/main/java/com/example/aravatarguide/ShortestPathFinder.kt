package com.example.aravatarguide

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2

data class PathResult(
    val nodes: List<GraphNode>,
    val totalDistance: Float
)

class ShortestPathFinder(private val graph: FloorGraph) {

    /**
     * Find path starting from the exact node ID (used when OCR identifies the start location).
     */
    fun findPathFromNode(startNodeId: String, destinationName: String): PathResult? {
        val startNode = graph.nodes[startNodeId] ?: return null
        val destinationNode = findDestinationNode(destinationName) ?: return null
        return computePath(startNode, destinationNode)
    }

    fun findPathToDestination(startPos: List<Float>, destinationName: String): PathResult? {
        val startNode = graph.findNearestNode(startPos) ?: return null
        val destinationNode = findDestinationNode(destinationName) ?: return null
        return computePath(startNode, destinationNode)
    }

    private fun computePath(startNode: GraphNode, destinationNode: GraphNode): PathResult? {
        val distances = mutableMapOf<String, Float>()
        val previousNodes = mutableMapOf<String, GraphNode?>()
        val visited = mutableSetOf<String>()
        val priorityQueue = PriorityQueue<Pair<GraphNode, Float>>(compareBy { it.second })

        graph.getAllNodes().forEach { node ->
            distances[node.id] = Float.MAX_VALUE
            previousNodes[node.id] = null
        }

        distances[startNode.id] = 0f
        priorityQueue.add(Pair(startNode, 0f))

        while (priorityQueue.isNotEmpty()) {
            val (currentNode, currentDist) = priorityQueue.poll()

            if (currentNode.id in visited) continue
            visited.add(currentNode.id)

            if (currentNode.id == destinationNode.id) {
                break
            }

            graph.getNeighborsOf(currentNode).forEach { neighbor ->
                if (neighbor.id !in visited) {
                    // Restricted areas are treated as normal nodes for pathfinding —
                    // the warning is handled separately in VisitorActivity.
                    val edgeWeight = graph.calculateDistance(currentNode.position, neighbor.position)
                    val newDist = currentDist + edgeWeight

                    if (newDist < distances.getOrDefault(neighbor.id, Float.MAX_VALUE)) {
                        distances[neighbor.id] = newDist
                        previousNodes[neighbor.id] = currentNode
                        priorityQueue.add(Pair(neighbor, newDist))
                    }
                }
            }
        }

        // Reconstruct path
        val path = mutableListOf<GraphNode>()
        var currentNode: GraphNode? = destinationNode

        while (currentNode != null) {
            path.add(0, currentNode)
            currentNode = previousNodes[currentNode.id]
        }

        if (path.firstOrNull()?.id == startNode.id) {
            val totalDistance = distances[destinationNode.id] ?: Float.MAX_VALUE
            // Smart navigation: simplify path to straight-line segments between turn points
            val simplified = simplifyPath(path)
            return PathResult(simplified, totalDistance)
        }

        return null
    }

    /**
     * Simplify path by removing intermediate nodes that don't represent
     * significant direction changes. Creates straight-line arrow segments
     * between key turning points — smart navigation like Google Maps.
     *
     * Instead of following every 0.3m recorded waypoint, arrows go in
     * straight lines and only change direction at real turn points.
     */
    private fun simplifyPath(path: List<GraphNode>, angleThresholdDeg: Float = 15f): List<GraphNode> {
        if (path.size <= 2) return path

        val result = mutableListOf<GraphNode>()
        result.add(path.first()) // Always keep start node

        var lastKeptIdx = 0

        for (i in 1 until path.size - 1) {
            val prev = path[lastKeptIdx]
            val curr = path[i]
            val next = path[i + 1]

            // Direction vector from last kept point to current
            val dx1 = curr.position[0] - prev.position[0]
            val dz1 = curr.position[2] - prev.position[2]
            // Direction vector from current to next
            val dx2 = next.position[0] - curr.position[0]
            val dz2 = next.position[2] - curr.position[2]

            val angle1 = Math.toDegrees(atan2(dx1, dz1))
            val angle2 = Math.toDegrees(atan2(dx2, dz2))

            var angleDiff = abs(angle2 - angle1)
            if (angleDiff > 180.0) angleDiff = 360.0 - angleDiff

            // Keep node only if it represents a significant direction change (turn point)
            if (angleDiff > angleThresholdDeg) {
                result.add(curr)
                lastKeptIdx = i
            }
        }

        result.add(path.last()) // Always keep destination
        return result
    }

    /**
     * Flexible destination matching: exact -> contains -> partial word match
     */
    private fun findDestinationNode(name: String): GraphNode? {
        val namedWaypoints = graph.getNamedWaypoints()
        val cleanName = name.trim().lowercase()

        // 1. Exact match (case-insensitive)
        namedWaypoints.find { it.name.trim().equals(cleanName, ignoreCase = true) }?.let { return it }

        // 2. Input contains waypoint name
        namedWaypoints.find { cleanName.contains(it.name.trim().lowercase()) }?.let { return it }

        // 3. Waypoint name contains input
        namedWaypoints.find { it.name.trim().lowercase().contains(cleanName) }?.let { return it }

        // 4. Word-level match
        val inputWords = cleanName.split("\\s+".toRegex()).filter { it.length >= 3 }
        for (wp in namedWaypoints) {
            val wpWords = wp.name.trim().lowercase().split("\\s+".toRegex())
            if (inputWords.any { word -> wpWords.any { it == word } }) {
                return wp
            }
        }

        return null
    }
}