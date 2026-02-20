package com.example.aravatarguide

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.aravatarguide.databinding.ActivityVisitorBinding
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class VisitorActivity : AppCompatActivity(), GLSurfaceView.Renderer, TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityVisitorBinding

    private var arSession: Session? = null
    private var installRequested = false

    private var backgroundRenderer: BackgroundRenderer? = null
    private var renderer: SimpleRenderer? = null
    private var avatarRenderer: AvatarRenderer? = null
    private var arrowModel: ModelLoader? = null
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private var floorGraph: FloorGraph? = null
    private var pathFinder: ShortestPathFinder? = null
    private var currentPath: PathResult? = null
    private var currentWaypointIndex = 0

    private var isTtsReady = false
    private var isListening = false
    private var hasAskedInitialQuestion = false
    private var isPositionRecognized = false
    private var isNavigating = false

    private var userCurrentPosition: List<Float>? = null
    private var pendingDestination: String? = null

    private val destinationColor = floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f)
    private val arrowColor = floatArrayOf(0.0f, 0.5f, 1.0f, 1.0f) // Blue arrows
    private val restrictedColor = floatArrayOf(0.6f, 0.0f, 0.8f, 1.0f) // Purple for restricted zones

    private lateinit var database: FirebaseDatabase
    private lateinit var firebasePathManager: FirebasePathManager

    // Restricted area detection
    private var lastRestrictedAlertTime = 0L
    private val RESTRICTED_ALERT_COOLDOWN_MS = 8000L // Don't spam alerts
    private var lastAlertedRestrictedNodeId: String? = null

    // OCR components
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastOcrTimestamp = 0L
    private val OCR_INTERVAL_MS = 2000L // Run OCR every 2 seconds
    private var isOcrProcessing = false

    // Coordinate Calibration: Translation + Rotation mapping between AR spaces
    private var calibrationLocalPos: FloatArray? = null  // Visitor's AR position at calibration point
    private var calibrationMapPos: FloatArray? = null     // Host's map position at calibration point
    private var calibrationNodeId: String? = null          // Graph node matched at calibration point
    private var yawOffset: Float = 0f                      // Rotation offset (radians): local to map
    private var isCalibrated = false                       // Full calibration with rotation complete

    // Groq AI (Free Llama 3.3 70B)
    private lateinit var chatHelper: GroqChatHelper

    companion object {
        private const val PERMISSION_CODE = 100
        private const val WAYPOINT_REACHED_DISTANCE = 0.8f
        private const val CALIBRATION_MIN_DISTANCE = 0.7f // Min walk distance for rotation calibration
        private const val ARROW_SPACING = 0.6f // Distance between arrows (meters)
        private const val ARROW_HEIGHT_OFFSET = 0.1f // Height above ground
        private const val MAX_VISIBLE_ARROWS = 50 // Show enough arrows for continuous path
        private const val MAX_ARROW_DISTANCE = 50.0f // Show arrows along the full path
        private const val RESTRICTED_AREA_ALERT_DISTANCE = 2.0f // Alert when within 2m of restricted area
        private const val TAG = "VisitorActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVisitorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = FirebaseDatabase.getInstance()
        firebasePathManager = FirebasePathManager()

        // Initialize Groq AI (Free - Llama 3.3 70B)
        val apiKey = BuildConfig.GROQ_API_KEY
        if (apiKey.isBlank()) {
            Toast.makeText(this, "⚠️ Groq API Key is missing in local.properties", Toast.LENGTH_LONG).show()
        } else {
            Log.d(TAG, "Groq API Key loaded (length: ${apiKey.length})")
        }
        chatHelper = GroqChatHelper(apiKey)

        binding.surfaceView.preserveEGLContextOnPause = true
        binding.surfaceView.setEGLContextClientVersion(2)
        binding.surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        binding.surfaceView.setRenderer(this)
        binding.surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        binding.btnMap.setOnClickListener { startActivity(Intent(this, BuildingActivity::class.java)) }
        binding.btnMicrophone.setOnClickListener { toggleSpeechRecognition() }
        binding.btnMicrophone.isEnabled = false

        textToSpeech = TextToSpeech(this, this)
        loadFloorGraphFromFirebase()
        checkPermissions()

        intent.getStringExtra("destination")?.let { startNavigation(it) }
    }

    private fun loadFloorGraphFromFirebase() {
        runOnUiThread {
            binding.tvStatus.text = "Downloading map..."
            binding.tvAvailableLocations.text = "Loading map from cloud..."
        }

        firebasePathManager.loadFloorGraph { graph ->
            Log.d(TAG, "Received graph: ${graph?.getNodeCount() ?: 0} nodes")

            if (graph != null && !graph.isEmpty()) {
                floorGraph = graph
                pathFinder = ShortestPathFinder(floorGraph!!)
                val destinations = floorGraph!!.getNamedWaypoints().map { it.name }
                chatHelper.setSystemPrompt(destinations)

                runOnUiThread {
                    binding.initialStateContainer.visibility = View.VISIBLE
                    binding.tvAvailableLocations.text = "Available Locations:\n${destinations.joinToString("\n")}"
                    binding.tvStatus.text = "Map loaded! Looking for your position..."
                    binding.btnMicrophone.isEnabled = true
                    Toast.makeText(this, "✅ Map loaded: ${destinations.size} locations", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Available locations: $destinations")
                }
                listenForEmergency()
            } else {
                runOnUiThread {
                    binding.initialStateContainer.visibility = View.VISIBLE
                    binding.tvAvailableLocations.text = "⚠️ No map found in cloud."
                    binding.tvSpeechInput.text = "Please create a map in Host Mode first."
                    binding.btnMicrophone.isEnabled = false
                    binding.tvStatus.text = "No map available"
                    Toast.makeText(this, "❌ No map available. Use Host Mode to create one.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (permissions.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_CODE)
        } else {
            setupSpeechRecognition()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            setupSpeechRecognition()
        }
    }

    private fun setupSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition not available")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    runOnUiThread { binding.tvSpeechInput.text = "🎤 Listening..." }
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    runOnUiThread { binding.tvSpeechInput.text = "Tap 🎤 to speak" }
                }
                override fun onError(error: Int) {
                    isListening = false
                    Log.e(TAG, "Speech recognition error: $error")
                    runOnUiThread { binding.tvSpeechInput.text = "Tap 🎤 to speak" }
                }
                override fun onResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        processVoiceCommand(it)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun toggleSpeechRecognition() {
        if (floorGraph == null || !isPositionRecognized) {
            Toast.makeText(this, "Please wait for position to be recognized.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isListening) {
            speechRecognizer?.stopListening()
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            speechRecognizer?.startListening(intent)
        }
    }

    private fun processVoiceCommand(command: String) {
        runOnUiThread { binding.tvSpeechInput.text = "You said: \"$command\"" }
        Log.d(TAG, "Processing voice command: $command")

        // 1. Try exact name match with navigation keywords
        val namedWaypoints = floorGraph?.getNamedWaypoints() ?: emptyList()
        val matchedWaypoint = namedWaypoints.find {
            command.contains(it.name, ignoreCase = true) &&
            (command.contains("go to", ignoreCase = true) ||
             command.contains("navigate to", ignoreCase = true) ||
             command.contains("take me to", ignoreCase = true) ||
             command.contains("where is", ignoreCase = true) ||
             command.contains("find", ignoreCase = true) ||
             command.contains("show me", ignoreCase = true) ||
             command.contains("direction", ignoreCase = true) ||
             command.contains("want to go", ignoreCase = true) ||
             command.contains("want to visit", ignoreCase = true))
        }

        if (matchedWaypoint != null) {
            Log.d(TAG, "Matched waypoint (keyword + name): ${matchedWaypoint.name}")
            startNavigation(matchedWaypoint.name)
            return
        }

        // 2. Try matching just the location name (user might just say the name)
        val directMatch = findBestMatchingWaypoint(command, namedWaypoints)
        if (directMatch != null) {
            Log.d(TAG, "Matched waypoint (direct name match): ${directMatch.name}")
            startNavigation(directMatch.name)
            return
        }

        // 3. If not a direct navigation command, use AI for conversational processing
        processConversationalCommand(command)
    }

    /**
     * Flexible waypoint matching: tries exact, contains, and partial matching.
     */
    private fun findBestMatchingWaypoint(input: String, waypoints: List<GraphNode>): GraphNode? {
        val cleanInput = input.trim().lowercase()
        if (cleanInput.length < 2) return null

        // Exact match (case-insensitive)
        waypoints.find { it.name.trim().lowercase() == cleanInput }?.let { return it }

        // Input contains a waypoint name
        waypoints.find { cleanInput.contains(it.name.trim().lowercase()) }?.let { return it }

        // Waypoint name contains input (e.g., user says "library", waypoint is "Main Library")
        waypoints.find { it.name.trim().lowercase().contains(cleanInput) }?.let { return it }

        // Word-level match: any word in input matches a waypoint name word
        val inputWords = cleanInput.split("\\s+".toRegex()).filter { it.length >= 3 }
        for (wp in waypoints) {
            val wpWords = wp.name.trim().lowercase().split("\\s+".toRegex())
            if (inputWords.any { word -> wpWords.any { it == word } }) {
                return wp
            }
        }

        return null
    }

    private fun processConversationalCommand(command: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val result = chatHelper.chat(command)

            when (result) {
                is GroqChatHelper.ChatResult.Message -> {
                    speak(result.text)
                }
                is GroqChatHelper.ChatResult.Navigation -> {
                    val destination = result.destination
                    // Verify destination exists (flexible matching)
                    val namedWaypoints = floorGraph?.getNamedWaypoints() ?: emptyList()
                    val validDest = findBestMatchingWaypoint(destination, namedWaypoints)

                    if (validDest != null) {
                        if (result.preText.isNotBlank()) {
                            speak(result.preText)
                        }
                        runOnUiThread {
                            startNavigation(validDest.name)
                        }
                    } else {
                        // Last resort: try the original destination name directly
                        val exactDest = namedWaypoints.find {
                            it.name.equals(destination, ignoreCase = true)
                        }
                        if (exactDest != null) {
                            if (result.preText.isNotBlank()) {
                                speak(result.preText)
                            }
                            runOnUiThread {
                                startNavigation(exactDest.name)
                            }
                        } else {
                            val availableNames = namedWaypoints.joinToString(", ") { it.name }
                            speak("I couldn't find $destination on the map. Available locations are: $availableNames")
                        }
                    }
                }
                is GroqChatHelper.ChatResult.Error -> {
                    Log.e(TAG, "Groq AI Error: ${result.message}")
                    runOnUiThread {
                        binding.tvStatus.text = "AI Error"
                    }
                    speak(result.message)
                }
            }
        }
    }

    private fun startNavigation(destinationName: String) {
        Log.d(TAG, "Starting navigation to: $destinationName")
        pendingDestination = destinationName
        isNavigating = true
        runOnUiThread {
            binding.initialStateContainer.visibility = View.GONE
            binding.tvStatus.text = "Finding path to $destinationName..."
        }
    }

    private fun processPendingNavigation() {
        val destName = pendingDestination ?: return
        val currentPos = userCurrentPosition ?: return
        val graph = floorGraph ?: return
        val finder = pathFinder ?: return

        try {
            Log.d(TAG, "🗺️ Finding path to $destName")
            Log.d(TAG, "   User Position (Map Coords): [${currentPos[0]}, ${currentPos[1]}, ${currentPos[2]}]")

            val pathResult = finder.findPathToDestination(currentPos, destName)

            if (pathResult != null) {
                currentPath = pathResult
                currentWaypointIndex = 0

                // Log complete path for debugging
                Log.d(TAG, "✅ Path found with ${pathResult.nodes.size} waypoints, total distance: ${pathResult.totalDistance}m")
                pathResult.nodes.forEachIndexed { index, node ->
                    val marker = when {
                        index == 0 -> "START"
                        index == pathResult.nodes.size - 1 -> "END"
                        node.isNamedWaypoint -> "NAMED"
                        else -> "waypoint"
                    }
                    Log.d(TAG, "   [$index] $marker: ${node.name.ifEmpty { "(unnamed)" }} at [${node.position[0]}, ${node.position[1]}, ${node.position[2]}]")
                }

                runOnUiThread {
                    binding.tvDestination.text = "→ $destName"
                    binding.tvDestination.visibility = View.VISIBLE
                    binding.tvDirection.visibility = View.VISIBLE
                    binding.tvStatus.text = "Navigate to $destName"
                }
                speak("Starting navigation to $destName. Follow the arrows.")
            } else {
                Log.w(TAG, "❌ No path found to $destName")
                Log.w(TAG, "   Check if destination exists and is connected to other waypoints")
                speak("Sorry, I couldn't find a path to $destName")
                isNavigating = false

                runOnUiThread {
                    binding.tvStatus.text = "No path to $destName"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting navigation", e)
            e.printStackTrace()
            speak("Error starting navigation")
            isNavigating = false
        }
        pendingDestination = null
    }

    // Position recognition via raw local coordinates is unreliable (different AR origins).
    // Only OCR-based recognition is used.
    private fun recognizeUserPosition(currentPosition: List<Float>) {
        // Disabled: local coords don't match map coords without calibration
        // Position is recognized via OCR in runAutoOcr() instead
    }

    private fun completePositionRecognition(locationName: String, mapPosition: List<Float>, localPosition: List<Float>, nodeId: String? = null) {
        // Store calibration reference points for rotation computation
        calibrationLocalPos = floatArrayOf(localPosition[0], localPosition[1], localPosition[2])
        calibrationMapPos = floatArrayOf(mapPosition[0], mapPosition[1], mapPosition[2])
        calibrationNodeId = nodeId
        yawOffset = 0f
        isCalibrated = false  // Rotation calibration completes after visitor walks a bit

        isPositionRecognized = true
        userCurrentPosition = mapPosition

        Log.d(TAG, "✅ Position recognized: $locationName (node: $nodeId)")
        Log.d(TAG, "   Local Position: [${localPosition[0]}, ${localPosition[1]}, ${localPosition[2]}]")
        Log.d(TAG, "   Map Position: [${mapPosition[0]}, ${mapPosition[1]}, ${mapPosition[2]}]")
        Log.d(TAG, "   ⏳ Rotation calibration pending — walk to complete")

        runOnUiThread {
            binding.tvStatus.text = "Position: $locationName"
            binding.tvOcrStatus.text = "Recognized: $locationName ✓"
            binding.btnMicrophone.isEnabled = true
            Toast.makeText(this, "✅ Position: $locationName", Toast.LENGTH_SHORT).show()
        }

        if (isTtsReady && !hasAskedInitialQuestion && pendingDestination == null) {
            hasAskedInitialQuestion = true
            speak("Hello! You are near $locationName. Where would you like to go?")
        }
    }

    /**
     * Convert local AR position to map position using calibration data.
     * Uses rotation + translation when calibrated, translation-only before that.
     */
    private fun localToMap(localPos: List<Float>): List<Float> {
        val refLocal = calibrationLocalPos ?: return localPos
        val refMap = calibrationMapPos ?: return localPos

        val dx = localPos[0] - refLocal[0]
        val dy = localPos[1] - refLocal[1]
        val dz = localPos[2] - refLocal[2]

        val cosY = cos(yawOffset.toDouble()).toFloat()
        val sinY = sin(yawOffset.toDouble()).toFloat()

        return listOf(
            refMap[0] + dx * cosY + dz * sinY,
            refMap[1] + dy,
            refMap[2] - dx * sinY + dz * cosY
        )
    }

    /**
     * Convert map position to local AR position for rendering.
     * Inverse of localToMap.
     */
    private fun mapToLocal(mapX: Float, mapY: Float, mapZ: Float): FloatArray {
        val refLocal = calibrationLocalPos ?: return floatArrayOf(mapX, mapY, mapZ)
        val refMap = calibrationMapPos ?: return floatArrayOf(mapX, mapY, mapZ)

        val dx = mapX - refMap[0]
        val dy = mapY - refMap[1]
        val dz = mapZ - refMap[2]

        val cosY = cos(yawOffset.toDouble()).toFloat()
        val sinY = sin(yawOffset.toDouble()).toFloat()

        // Inverse rotation (negate yaw)
        return floatArrayOf(
            refLocal[0] + dx * cosY - dz * sinY,
            refLocal[1] + dy,
            refLocal[2] + dx * sinY + dz * cosY
        )
    }

    /**
     * After OCR position lock, wait for the visitor to walk ~0.7m, then compute
     * the yaw rotation offset by matching their movement direction against
     * graph edge directions from the calibration node.
     */
    private fun tryCompleteCalibration(currentLocalPos: List<Float>) {
        val refLocal = calibrationLocalPos ?: return
        val refMap = calibrationMapPos ?: return
        val nodeId = calibrationNodeId ?: return
        val graph = floorGraph ?: return

        // Compute horizontal displacement from calibration point
        val dxLocal = currentLocalPos[0] - refLocal[0]
        val dzLocal = currentLocalPos[2] - refLocal[2]
        val distMoved = sqrt(dxLocal * dxLocal + dzLocal * dzLocal)

        if (distMoved < CALIBRATION_MIN_DISTANCE) return // Haven't walked far enough

        val localAngle = atan2(dxLocal.toDouble(), dzLocal.toDouble())

        // Get all edges from the calibration node
        val edges = graph.adjacencyList[nodeId]
        if (edges.isNullOrEmpty()) {
            // No edges — fallback to zero rotation
            isCalibrated = true
            Log.w(TAG, "⚠️ No edges from calibration node, using zero rotation")
            return
        }

        var bestYaw = 0f
        var bestScore = Float.MAX_VALUE

        for (edge in edges) {
            val neighbor = graph.nodes[edge.to] ?: continue
            val dxMap = (neighbor.position[0] - refMap[0]).toFloat()
            val dzMap = (neighbor.position[2] - refMap[2]).toFloat()
            val edgeDist = sqrt(dxMap * dxMap + dzMap * dzMap)
            if (edgeDist < 0.1f) continue // Skip very short edges

            val mapAngle = atan2(dxMap.toDouble(), dzMap.toDouble())
            val candidateYaw = (mapAngle - localAngle).toFloat()

            // Test: apply this yaw to map current local position to map space
            val cosA = cos(candidateYaw.toDouble()).toFloat()
            val sinA = sin(candidateYaw.toDouble()).toFloat()
            val mappedX = refMap[0] + dxLocal * cosA + dzLocal * sinA
            val mappedZ = refMap[2] - dxLocal * sinA + dzLocal * cosA
            val mappedPos = listOf(mappedX, currentLocalPos[1], mappedZ)

            // Check how close this mapped position is to any graph node
            val nearestNode = graph.findNearestNode(mappedPos)
            val distToGraph = if (nearestNode != null) {
                graph.calculateDistanceFromFloat(mappedPos, nearestNode.position)
            } else Float.MAX_VALUE

            if (distToGraph < bestScore) {
                bestScore = distToGraph
                bestYaw = candidateYaw
            }
        }

        yawOffset = bestYaw
        isCalibrated = true

        Log.d(TAG, "✅ Rotation calibrated! Yaw offset: ${Math.toDegrees(yawOffset.toDouble())}°")
        Log.d(TAG, "   Local movement: (${String.format("%.2f", dxLocal)}, ${String.format("%.2f", dzLocal)})")
        Log.d(TAG, "   Distance moved: ${String.format("%.2f", distMoved)}m")
        Log.d(TAG, "   Best match score: ${String.format("%.3f", bestScore)}m from graph")

        runOnUiThread {
            binding.tvOcrStatus.text = "Calibrated ✓ (${String.format("%.0f", Math.toDegrees(yawOffset.toDouble()))}°)"
        }
    }

    private fun runAutoOcr(frame: Frame) {
        val currentTime = System.currentTimeMillis()
        if (isOcrProcessing || currentTime - lastOcrTimestamp < OCR_INTERVAL_MS || isPositionRecognized) return

        val cameraPose = frame.camera.pose

        try {
            val image = frame.acquireCameraImage()
            isOcrProcessing = true
            val inputImage = InputImage.fromMediaImage(image, 90)

            textRecognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    image.close()
                    lastOcrTimestamp = currentTime
                    isOcrProcessing = false

                    val detectedTexts = visionText.textBlocks.map { it.text.uppercase() }
                    checkOcrMatch(detectedTexts, cameraPose)
                }
                .addOnFailureListener {
                    image.close()
                    isOcrProcessing = false
                }
        } catch (e: Exception) {
            isOcrProcessing = false
        }
    }

    private fun checkOcrMatch(detectedTexts: List<String>, cameraPose: Pose) {
        val graph = floorGraph ?: return
        val namedWaypoints = graph.getNamedWaypoints()

        Log.d(TAG, "🔍 OCR detected ${detectedTexts.size} text blocks: ${detectedTexts.joinToString(", ")}")

        for (text in detectedTexts) {
            // Clean up the detected text
            val cleanText = text.trim().uppercase()
            if (cleanText.length < 3) continue // Ignore very short matches (noise)

            // Try exact match first
            var match = namedWaypoints.find { it.name.uppercase() == cleanText }

            // If no exact match, try partial match (but only if text is reasonably long)
            if (match == null && cleanText.length >= 5) {
                match = namedWaypoints.find { waypoint ->
                    val waypointName = waypoint.name.uppercase()
                    waypointName.contains(cleanText) || cleanText.contains(waypointName)
                }
            }

            if (match != null) {
                Log.d(TAG, "✅ OCR Match found: '${match.name}' from detected text: '$cleanText'")
                runOnUiThread {
                    binding.tvOcrStatus.text = "OCR Match: ${match.name}"
                }
                val localPos = listOf(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
                val mapPos = match.position.map { it.toFloat() }
                completePositionRecognition(match.name, mapPos, localPos, match.id)
                break
            }
        }
    }

    private fun updateNavigation(mappedPosition: List<Float>) {
        val path = currentPath ?: return
        if (!isNavigating || currentWaypointIndex >= path.nodes.size) return

        val targetNode = path.nodes[currentWaypointIndex]
        val distance = floorGraph!!.calculateDistanceFromFloat(mappedPosition, targetNode.position)

        runOnUiThread {
            binding.tvDirection.text = String.format("%.1f m to next point", distance)
        }

        if (distance < WAYPOINT_REACHED_DISTANCE) {
            currentWaypointIndex++
            if (currentWaypointIndex >= path.nodes.size) {
                isNavigating = false
                currentPath = null
                speak("You have reached your destination")
                runOnUiThread {
                    binding.tvDirection.text = "✅ You have arrived!"
                    binding.tvStatus.text = "Destination reached"
                }
            } else {
                val nextNode = path.nodes[currentWaypointIndex]
                if (nextNode.isNamedWaypoint) {
                    speak("Approaching ${nextNode.name}")
                }
            }
        }
    }

    // Generate continuous arrow positions along the full path from user to destination (Map Coordinates)
    private fun generateContinuousArrows(path: List<GraphNode>, userPosition: List<Float>): List<ArrowPosition> {
        if (path.isEmpty()) return emptyList()

        val arrows = mutableListOf<ArrowPosition>()
        var arrowCount = 0

        // Build the complete polyline: user position -> first waypoint -> ... -> destination
        data class PathPoint(val x: Float, val y: Float, val z: Float)

        val polyline = mutableListOf<PathPoint>()
        // Start from user's current position for a truly continuous trail
        polyline.add(PathPoint(userPosition[0], userPosition[1], userPosition[2]))
        for (node in path) {
            polyline.add(PathPoint(
                node.position[0].toFloat(),
                node.position[1].toFloat(),
                node.position[2].toFloat()
            ))
        }

        // Generate arrows along each segment of the polyline
        // Start a small offset from user so the first arrow isn't right under their feet
        var isFirstSegment = true

        for (i in 0 until polyline.size - 1) {
            if (arrowCount >= MAX_VISIBLE_ARROWS) break

            val start = polyline[i]
            val end = polyline[i + 1]

            val dx = end.x - start.x
            val dy = end.y - start.y
            val dz = end.z - start.z

            val segmentLength = sqrt(dx * dx + dy * dy + dz * dz)
            if (segmentLength < 0.1f) continue

            val angleY = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()

            // For first segment (user -> first waypoint), skip the first 0.5m so arrows
            // don't appear directly under the user's feet
            var distance = if (isFirstSegment) 0.5f else 0f
            isFirstSegment = false

            while (distance < segmentLength && arrowCount < MAX_VISIBLE_ARROWS) {
                val t = distance / segmentLength

                val arrowX = start.x + dx * t
                val arrowY = start.y + dy * t + ARROW_HEIGHT_OFFSET
                val arrowZ = start.z + dz * t

                arrows.add(ArrowPosition(arrowX, arrowY, arrowZ, angleY))
                arrowCount++

                distance += ARROW_SPACING
            }
        }

        if (arrows.isNotEmpty()) {
            Log.v(TAG, "📍 Showing ${arrows.size} continuous arrows to destination")
        }

        return arrows
    }

    /**
     * Check if visitor is approaching a restricted area.
     * Alerts the visitor via TTS and notifies the host via Firebase.
     */
    private fun checkRestrictedAreaProximity(mappedPosition: List<Float>) {
        val graph = floorGraph ?: return
        val restrictedAreas = graph.getRestrictedAreas()
        if (restrictedAreas.isEmpty()) return

        val currentTime = System.currentTimeMillis()

        for (area in restrictedAreas) {
            val distance = graph.calculateDistanceFromFloat(mappedPosition, area.position)

            if (distance < RESTRICTED_AREA_ALERT_DISTANCE) {
                // Check cooldown to avoid spamming
                val isSameArea = lastAlertedRestrictedNodeId == area.id
                val cooldownPassed = currentTime - lastRestrictedAlertTime > RESTRICTED_ALERT_COOLDOWN_MS

                if (!isSameArea || cooldownPassed) {
                    lastRestrictedAlertTime = currentTime
                    lastAlertedRestrictedNodeId = area.id

                    Log.w(TAG, "⛔ RESTRICTED AREA ALERT: Visitor near '${area.name}' (${String.format("%.1f", distance)}m)")

                    // Alert the visitor
                    speak("Warning! You are entering a restricted area near ${area.name}. This area is restricted. Please turn back.")

                    runOnUiThread {
                        binding.tvStatus.text = "⛔ RESTRICTED: ${area.name}"
                        Toast.makeText(this, "⛔ Restricted Area: ${area.name}", Toast.LENGTH_LONG).show()
                    }

                    // Stop navigation if currently navigating towards restricted area
                    if (isNavigating) {
                        isNavigating = false
                        currentPath = null
                        currentWaypointIndex = 0
                        runOnUiThread {
                            binding.tvDirection.text = "⛔ Navigation stopped - Restricted area"
                        }
                    }

                    // Send alert to host via Firebase
                    sendRestrictedAreaAlert(area.name, distance)
                }
                return // Only alert for the closest restricted area
            }
        }

        // Clear the alert state if we moved away from all restricted areas
        if (lastAlertedRestrictedNodeId != null) {
            val lastArea = restrictedAreas.find { it.id == lastAlertedRestrictedNodeId }
            if (lastArea != null) {
                val distToLast = graph.calculateDistanceFromFloat(mappedPosition, lastArea.position)
                if (distToLast > RESTRICTED_AREA_ALERT_DISTANCE * 1.5f) {
                    lastAlertedRestrictedNodeId = null
                    Log.d(TAG, "✅ Visitor moved away from restricted area")
                }
            }
        }
    }

    /**
     * Send a Firebase alert when a visitor enters a restricted area.
     * The host can listen to this in ARActivity or a monitoring dashboard.
     */
    private fun sendRestrictedAreaAlert(areaName: String, distance: Float) {
        val alertData = hashMapOf(
            "areaName" to areaName,
            "distance" to distance.toDouble(),
            "timestamp" to System.currentTimeMillis(),
            "message" to "Visitor detected near restricted area: $areaName"
        )

        database.getReference("restrictedAreaAlerts")
            .push()
            .setValue(alertData)
            .addOnSuccessListener {
                Log.d(TAG, "📤 Restricted area alert sent to host for: $areaName")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send restricted area alert", e)
            }

        // Also set a live flag so host gets instant notification
        database.getReference("restrictedAreaBreach").setValue(
            hashMapOf(
                "active" to true,
                "areaName" to areaName,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    private fun listenForEmergency() {
        database.getReference("emergency").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.getValue(Boolean::class.java) == true) {
                    triggerEmergencyEvacuation()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Emergency listener cancelled: ${error.message}")
            }
        })
    }

    private fun triggerEmergencyEvacuation() {
        if (floorGraph == null || userCurrentPosition == null) return

        speak("EMERGENCY! EVACUATE IMMEDIATELY!")
        Log.d(TAG, "Emergency triggered, finding nearest exit")

        val closestExit = floorGraph!!.getEmergencyExits().minByOrNull {
            floorGraph!!.calculateDistanceFromFloat(userCurrentPosition!!, it.position)
        }

        if (closestExit != null) {
            startNavigation(closestExit.name)
        } else {
            speak("No emergency exits have been defined!")
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            avatarRenderer?.isSpeaking = true
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            
            // Stop speaking animation after estimated duration (rough estimate: 100ms per char)
            val duration = (text.length * 100).toLong()
            binding.surfaceView.postDelayed({
                avatarRenderer?.isSpeaking = false
            }, duration)
        }
        Log.d(TAG, "TTS: $text")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isTtsReady = true
            Log.d(TAG, "TTS initialized successfully")
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        if (!hasCameraPermission()) return
        if (arSession == null) {
            try {
                if (ArCoreApk.getInstance().requestInstall(this, !installRequested) == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                    installRequested = true
                    return
                }
                arSession = Session(this)
                arSession?.configure(Config(arSession).apply {
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
                return
            }
        }
        try {
            arSession?.resume()
            binding.surfaceView.onResume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            arSession = null
        }
    }

    override fun onPause() {
        super.onPause()
        binding.surfaceView.onPause()
        arSession?.pause()
        speechRecognizer?.stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer = BackgroundRenderer().apply { createOnGlThread(this@VisitorActivity) }
        renderer = SimpleRenderer().apply { createOnGlThread() }
        avatarRenderer = AvatarRenderer().apply { createOnGlThread() }
        arrowModel = ModelLoader(this).apply {
            loadModel("arrow.glb")
            createOnGlThread()
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        arSession?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = arSession ?: return

        try {
            session.setCameraTextureName(backgroundRenderer?.getTextureId() ?: 0)
            val frame = session.update()
            val camera = frame.camera

            backgroundRenderer?.draw(frame)

            if (camera.trackingState != TrackingState.TRACKING) return

            // OCR Auto-Recognition
            if (!isPositionRecognized) {
                runAutoOcr(frame)
            }

            val viewMatrix = FloatArray(16)
            val projectionMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)

            val cameraPosLocal = listOf(camera.pose.tx(), camera.pose.ty(), camera.pose.tz())

            // Try rotation calibration if position recognized but rotation not yet calibrated
            if (isPositionRecognized && !isCalibrated) {
                tryCompleteCalibration(cameraPosLocal)
            }

            // Map local AR coordinates to stored Map coordinates (with rotation)
            val mappedUserPos = localToMap(cameraPosLocal)
            userCurrentPosition = mappedUserPos

            // Check restricted area proximity
            if (isPositionRecognized) {
                checkRestrictedAreaProximity(mappedUserPos)
            }

            if (!isPositionRecognized) {
                recognizeUserPosition(cameraPosLocal)
            }

            if (pendingDestination != null) {
                processPendingNavigation()
            }

            if (isNavigating && currentPath != null) {
                updateNavigation(mappedUserPos)

                val path = currentPath!!
                val remainingPath = path.nodes.drop(currentWaypointIndex)
                val arrows = generateContinuousArrows(remainingPath, mappedUserPos)

                // Use camera Y for consistent arrow height (avoids floating/sunken arrows)
                val arrowLocalY = cameraPosLocal[1] - 0.5f

                arrowModel?.let { arrow ->
                    for (arrowPos in arrows) {
                        // Convert map-coordinate arrow to local-coordinate space (with rotation)
                        val localPos = mapToLocal(arrowPos.x, arrowPos.y, arrowPos.z)

                        val modelMatrix = FloatArray(16)
                        Matrix.setIdentityM(modelMatrix, 0)
                        Matrix.translateM(modelMatrix, 0, localPos[0], arrowLocalY, localPos[2])
                        // Adjust arrow rotation from map space to local space
                        val localRotation = arrowPos.rotation - Math.toDegrees(yawOffset.toDouble()).toFloat()
                        Matrix.rotateM(modelMatrix, 0, localRotation, 0f, 1f, 0f)
                        Matrix.scaleM(modelMatrix, 0, 0.35f, 0.35f, 0.35f)

                        val mvpMatrix = FloatArray(16)
                        val tempMatrix = FloatArray(16)
                        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
                        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

                        arrow.draw(mvpMatrix, arrowColor)
                    }
                }

                // Draw destination marker (converted to local space with rotation)
                renderer?.let { r ->
                    val destNode = path.nodes.last()
                    val pos = destNode.toFloatArray()
                    val localDest = mapToLocal(pos[0], pos[1], pos[2])
                    r.draw(viewMatrix, projectionMatrix, localDest[0], arrowLocalY, localDest[2], destinationColor)
                }
            } else {
                // Show avatar when not navigating
                avatarRenderer?.draw(viewMatrix, projectionMatrix, cameraPosLocal[0] - (camera.pose.zAxis[0] * 2.0f), cameraPosLocal[1] - 1.5f, cameraPosLocal[2] - (camera.pose.zAxis[2] * 2.0f))
            }

            // Always render restricted area markers (visible purple circles)
            if (isPositionRecognized) {
                val arrowLocalY = cameraPosLocal[1] - 0.5f
                renderer?.let { r ->
                    floorGraph?.getRestrictedAreas()?.forEach { restricted ->
                        val pos = restricted.toFloatArray()
                        val localPos = mapToLocal(pos[0], pos[1], pos[2])
                        r.draw(viewMatrix, projectionMatrix, localPos[0], arrowLocalY, localPos[2], restrictedColor)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDrawFrame", e)
        }
    }

    data class ArrowPosition(
        val x: Float,
        val y: Float,
        val z: Float,
        val rotation: Float
    )
}