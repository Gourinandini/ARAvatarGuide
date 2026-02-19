package com.example.aravatarguide

import java.util.PriorityQueue

data class PathResult(
    val nodes: List<GraphNode>,
    val totalDistance: Float
)

class ShortestPathFinder(private val graph: FloorGraph) {

    fun findPathToDestination(startPos: List<Float>, destinationName: String): PathResult? {
        val startNode = graph.findNearestNode(startPos) ?: return null
        val destinationNode = graph.getNamedWaypoints().find {
            it.name.equals(destinationName, ignoreCase = true)
        } ?: return null

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
                // Skip restricted areas — never route through them
                if (neighbor.id !in visited && !neighbor.isRestrictedArea) {
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
            return PathResult(path, totalDistance)
        }

        return null
    }
}