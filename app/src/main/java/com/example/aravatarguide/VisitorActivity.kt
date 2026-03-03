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
    private var lastCalibrationAttemptTime = 0L             // Throttle calibration to avoid frame drops

    // Groq AI (Free Llama 3.3 70B)
    private lateinit var chatHelper: GroqChatHelper

    // Multi-point trajectory calibration state
    private data class WalkedPoint(val dxLocal: Float, val dzLocal: Float)
    private val walkedPoints = mutableListOf<WalkedPoint>()
    private var lastWalkedPointDist = 0f
    private var lastCalibratedDistance = 0f

    // Rotation-based calibration: instant — turn in place, no walking needed
    private data class FacingSample(val localFwdX: Float, val localFwdZ: Float)
    private val facingSamples = mutableListOf<FacingSample>()
    private var lastSampledFacingDeg = Float.MAX_VALUE
    private var calibratedByRotation = false // True if only rotation-calibrated (walking will refine)

    // Pre-computed graph edge segments for calibration scoring
    private data class EdgeSegment(val ax: Float, val az: Float, val bx: Float, val bz: Float)
    private var cachedEdgeSegments: List<EdgeSegment>? = null

    // Destination arrival: avatar faces user
    private var hasReachedDestination = false
    private var destinationReachedMapPos: FloatArray? = null

    // Camera forward direction (updated each frame for directional guidance)
    private var lastCameraForwardX = 0f
    private var lastCameraForwardZ = 0f
    private var initialGuidanceGiven = false

    companion object {
        private const val PERMISSION_CODE = 100
        private const val WAYPOINT_REACHED_DISTANCE = 1.2f // Increased for simplified turn-point paths
        private const val CALIBRATION_MIN_DISTANCE = 0.1f // Reduced for faster calibration start
        private const val ARROW_SPACING = 0.6f // Distance between arrows (meters)
        private const val ARROW_HEIGHT_OFFSET = 0.1f // Height above ground
        private const val MAX_VISIBLE_ARROWS = 5 // Show more arrows for straight-line paths
        private const val MAX_ARROW_DISTANCE = 4.0f // Show arrows within 4m for better guidance
        private const val RESTRICTED_AREA_ALERT_DISTANCE = 2.0f // Alert when within 2m of restricted area
        private const val TAG = "VisitorActivity"
        private const val WALK_POINT_SPACING = 0.12f // Reduced for faster calibration
        private const val MIN_CALIBRATION_POINTS = 2 // Reduced from 3 for faster calibration
        private const val MAX_CALIBRATION_POINTS = 12 // Keep last 12 trajectory points
        private const val CALIBRATION_REFINE_DISTANCE = 0.8f // Re-refine after walking further
        private const val FACING_SAMPLE_INTERVAL_DEG = 5f // Collect facing sample every 5° turn
        private const val MIN_ROTATION_RANGE_DEG = 15f // Need 15° of turning for calibration
        private const val MIN_FACING_SAMPLES = 3 // Minimum facing samples for rotation calibration
        private const val AVATAR_DISPLAY_DISTANCE = 2.0f // Fixed distance to render avatar from user
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
        hasReachedDestination = false
        destinationReachedMapPos = null
        initialGuidanceGiven = false
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
            Log.d(TAG, "   Calibration Node ID: $calibrationNodeId")
            Log.d(TAG, "   Graph: ${graph.getNodeCount()} nodes, ${graph.getNamedWaypointCount()} named")
            Log.d(TAG, "   Named waypoints: ${graph.getNamedWaypoints().joinToString { "'${it.name}'(${it.id.take(8)})" }}")

            // Use the exact OCR-matched node as start if available, otherwise fall back to nearest node
            var pathResult: PathResult? = null

            if (calibrationNodeId != null) {
                val startExists = graph.nodes.containsKey(calibrationNodeId)
                Log.d(TAG, "   Start node exists in graph: $startExists")
                if (startExists) {
                    pathResult = finder.findPathFromNode(calibrationNodeId!!, destName)
                    Log.d(TAG, "   findPathFromNode result: ${if (pathResult != null) "${pathResult.nodes.size} nodes" else "null"}")
                }
            }

            // Fallback: try position-based pathfinding if node-based failed
            if (pathResult == null) {
                Log.d(TAG, "   Trying position-based fallback...")
                pathResult = finder.findPathToDestination(currentPos, destName)
                Log.d(TAG, "   findPathToDestination result: ${if (pathResult != null) "${pathResult.nodes.size} nodes" else "null"}")
            }

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
                // Compute direction — use calibrated direction if available, otherwise generic
                if (isCalibrated) {
                    val initialDirection = computeInitialDirection(pathResult, currentPos)
                    speak("Starting navigation to $destName. $initialDirection and follow the arrows.")
                } else {
                    speak("Navigation to $destName ready. Look around slowly to see the path.")
                }
                initialGuidanceGiven = true
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

    /**
     * Compute initial turn direction by comparing the user's camera facing direction
     * against the first path segment direction (same direction as the rendered arrows).
     * Only called AFTER calibration is complete, so yawOffset is accurate.
     */
    private fun computeInitialDirection(pathResult: PathResult, userMapPos: List<Float>): String {
        if (pathResult.nodes.isEmpty()) return "Go straight ahead"

        // Use the first path segment direction — this is exactly where arrows point
        val targetNode = pathResult.nodes.first()
        val pathDx = targetNode.position[0].toFloat() - userMapPos[0]
        val pathDz = targetNode.position[2].toFloat() - userMapPos[2]

        // If the first node is very close, look at the second node for better direction
        val dx: Float
        val dz: Float
        val distToFirst = sqrt(pathDx * pathDx + pathDz * pathDz)
        if (distToFirst < 0.5f && pathResult.nodes.size > 1) {
            val secondNode = pathResult.nodes[1]
            dx = secondNode.position[0].toFloat() - userMapPos[0]
            dz = secondNode.position[2].toFloat() - userMapPos[2]
        } else {
            dx = pathDx
            dz = pathDz
        }

        // Path direction angle in map space (this matches arrow rendering direction)
        val pathAngle = Math.toDegrees(atan2(dx.toDouble(), dz.toDouble()))

        // Convert camera forward from local space to map space using calibrated yawOffset
        val cosY = cos(yawOffset.toDouble()).toFloat()
        val sinY = sin(yawOffset.toDouble()).toFloat()
        val mapFwdX = lastCameraForwardX * cosY + lastCameraForwardZ * sinY
        val mapFwdZ = -lastCameraForwardX * sinY + lastCameraForwardZ * cosY
        val cameraAngle = Math.toDegrees(atan2(mapFwdX.toDouble(), mapFwdZ.toDouble()))

        // Relative angle: positive = path is to the right of camera
        var relativeAngle = pathAngle - cameraAngle
        while (relativeAngle > 180) relativeAngle -= 360
        while (relativeAngle < -180) relativeAngle += 360

        Log.d(TAG, "\uD83E\uDDED Direction guidance: pathAngle=${String.format("%.0f", pathAngle)}° cameraAngle=${String.format("%.0f", cameraAngle)}° relative=${String.format("%.0f", relativeAngle)}°")

        return when {
            abs(relativeAngle) < 25 -> "Go straight ahead"
            relativeAngle in 25.0..155.0 -> "Turn left"
            relativeAngle in -155.0..-25.0 -> "Turn right"
            else -> "Turn back"
        }
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
        isCalibrated = false
        walkedPoints.clear()
        lastWalkedPointDist = 0f
        lastCalibratedDistance = 0f
        cachedEdgeSegments = null
        facingSamples.clear()
        lastSampledFacingDeg = Float.MAX_VALUE
        calibratedByRotation = false

        isPositionRecognized = true
        userCurrentPosition = mapPosition

        Log.d(TAG, "✅ Position recognized: $locationName (node: $nodeId)")
        Log.d(TAG, "   Local Position: [${localPosition[0]}, ${localPosition[1]}, ${localPosition[2]}]")
        Log.d(TAG, "   Map Position: [${mapPosition[0]}, ${mapPosition[1]}, ${mapPosition[2]}]")
        Log.d(TAG, "   ⏳ Calibration pending — look around slowly to calibrate")

        runOnUiThread {
            binding.tvStatus.text = "Position: $locationName"
            binding.tvOcrStatus.text = "Recognized: $locationName ✓"
            binding.btnMicrophone.isEnabled = true
            Toast.makeText(this, "✅ Position: $locationName", Toast.LENGTH_SHORT).show()
        }

        if (isTtsReady && !hasAskedInitialQuestion && pendingDestination == null) {
            hasAskedInitialQuestion = true
            speak("Hello! You are near $locationName. Please look around slowly, then tell me where you'd like to go.")
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
     * After OCR position lock, compute the yaw rotation offset by fitting the
     * user's walked trajectory to the graph. Uses:
     *
     * 1. Multi-point trajectory: collects walked positions at 0.2m intervals
     * 2. Edge-based scoring: measures distance to nearest graph EDGE (line segment),
     *    not just nearest node — much more accurate for paths between nodes
     * 3. 0.5° resolution brute-force across 360° (720 candidates)
     * 4. Finds the single yaw that best fits ALL trajectory points simultaneously
     * 5. Progressive re-refinement as user walks further
     */
    private fun tryCompleteCalibration(currentLocalPos: List<Float>) {
        val now = System.currentTimeMillis()
        if (now - lastCalibrationAttemptTime < 200) return
        lastCalibrationAttemptTime = now

        val refLocal = calibrationLocalPos ?: return
        val refMap = calibrationMapPos ?: return
        val graph = floorGraph ?: return

        val dxLocal = currentLocalPos[0] - refLocal[0]
        val dzLocal = currentLocalPos[2] - refLocal[2]
        val distMoved = sqrt(dxLocal * dxLocal + dzLocal * dzLocal)

        if (distMoved < CALIBRATION_MIN_DISTANCE) return

        // Collect trajectory points at regular intervals
        if (distMoved - lastWalkedPointDist >= WALK_POINT_SPACING || walkedPoints.isEmpty()) {
            walkedPoints.add(WalkedPoint(dxLocal, dzLocal))
            lastWalkedPointDist = distMoved
            if (walkedPoints.size > MAX_CALIBRATION_POINTS) {
                walkedPoints.removeAt(0)
            }
        }

        // Need enough trajectory points for reliable fitting
        if (walkedPoints.size < MIN_CALIBRATION_POINTS) return

        // Skip re-refinement if we haven't walked significantly further
        // BUT always allow walking to override rotation-only calibration immediately
        if (isCalibrated && !calibratedByRotation && distMoved - lastCalibratedDistance < CALIBRATION_REFINE_DISTANCE) return

        // Collect graph edges once (cache for performance)
        if (cachedEdgeSegments == null) {
            cachedEdgeSegments = collectGraphEdges(graph)
        }
        val edges = cachedEdgeSegments!!
        if (edges.isEmpty()) return

        var bestYaw = 0f
        var bestScore = Float.MAX_VALUE

        // Two-pass calibration for speed (~190 candidates vs 720 = 3.7x faster):
        // Pass 1: Coarse search at 2° resolution (180 candidates)
        for (deg in 0 until 360 step 2) {
            val candidateYaw = Math.toRadians(deg.toDouble()).toFloat()
            val cosA = cos(candidateYaw.toDouble()).toFloat()
            val sinA = sin(candidateYaw.toDouble()).toFloat()

            var totalScore = 0f
            for (point in walkedPoints) {
                val mappedX = refMap[0] + point.dxLocal * cosA + point.dzLocal * sinA
                val mappedZ = refMap[2] - point.dxLocal * sinA + point.dzLocal * cosA
                totalScore += distanceToNearestEdge(mappedX, mappedZ, edges)
            }

            if (totalScore < bestScore) {
                bestScore = totalScore
                bestYaw = candidateYaw
            }
        }

        // Pass 2: Refine ±3° around best at 0.5° resolution (12 candidates)
        val coarseBestDeg = Math.toDegrees(bestYaw.toDouble())
        for (i in -6..6) {
            val refineDeg = coarseBestDeg + i * 0.5
            val candidateYaw = Math.toRadians(refineDeg).toFloat()
            val cosA = cos(candidateYaw.toDouble()).toFloat()
            val sinA = sin(candidateYaw.toDouble()).toFloat()

            var totalScore = 0f
            for (point in walkedPoints) {
                val mappedX = refMap[0] + point.dxLocal * cosA + point.dzLocal * sinA
                val mappedZ = refMap[2] - point.dxLocal * sinA + point.dzLocal * cosA
                totalScore += distanceToNearestEdge(mappedX, mappedZ, edges)
            }

            if (totalScore < bestScore) {
                bestScore = totalScore
                bestYaw = candidateYaw
            }
        }

        // Average score per point — reject if too far from any edge
        val avgScore = bestScore / walkedPoints.size
        if (avgScore > 1.0f) return

        val wasAlreadyCalibrated = isCalibrated
        yawOffset = bestYaw
        isCalibrated = true
        calibratedByRotation = false // Walking calibration is more accurate
        lastCalibratedDistance = distMoved

        Log.d(TAG, "✅ Calibration ${if (lastCalibratedDistance < 1.5f) "locked" else "refined"}!")
        Log.d(TAG, "   Yaw: ${String.format("%.1f", Math.toDegrees(yawOffset.toDouble()))}°")
        Log.d(TAG, "   Trajectory points: ${walkedPoints.size}, Distance: ${String.format("%.2f", distMoved)}m")
        Log.d(TAG, "   Avg edge distance: ${String.format("%.3f", avgScore)}m")

        runOnUiThread {
            binding.tvOcrStatus.text = "Calibrated ✓ (${String.format("%.0f", Math.toDegrees(yawOffset.toDouble()))}°)"
            // On first calibration during active navigation, give accurate direction guidance
            if (!wasAlreadyCalibrated && isNavigating && currentPath != null && userCurrentPosition != null) {
                val dir = computeInitialDirection(currentPath!!, userCurrentPosition!!)
                speak("Calibrated. $dir.")
            }
        }
    }

    /**
     * Rotation-based calibration: determines yaw offset by matching the user's
     * camera facing directions to graph edge directions from the calibration node.
     *
     * The user just needs to look around (turn ~15°+). No walking required.
     * Works because the user naturally faces corridors more than walls.
     *
     * For straight corridors, there may be 180° ambiguity which walking
     * calibration resolves in 1-2 steps.
     */
    private fun tryRotationCalibration() {
        if (isCalibrated) return
        val now = System.currentTimeMillis()
        if (now - lastCalibrationAttemptTime < 150) return
        lastCalibrationAttemptTime = now

        val graph = floorGraph ?: return
        val nodeId = calibrationNodeId ?: return
        val node = graph.nodes[nodeId] ?: return
        val neighbors = graph.getNeighborsOf(node)
        if (neighbors.isEmpty()) return

        // Collect camera facing sample
        val currentAngleDeg = Math.toDegrees(
            atan2(lastCameraForwardX.toDouble(), lastCameraForwardZ.toDouble())
        ).toFloat()

        if (facingSamples.isEmpty() || abs(currentAngleDeg - lastSampledFacingDeg) >= FACING_SAMPLE_INTERVAL_DEG) {
            facingSamples.add(FacingSample(lastCameraForwardX, lastCameraForwardZ))
            lastSampledFacingDeg = currentAngleDeg
            if (facingSamples.size > 36) facingSamples.removeAt(0)
        }

        if (facingSamples.size < MIN_FACING_SAMPLES) return

        // Check angular range of collected samples
        val angles = facingSamples.map {
            Math.toDegrees(atan2(it.localFwdX.toDouble(), it.localFwdZ.toDouble())).toFloat()
        }
        val range = computeAngularRange(angles)
        if (range < MIN_ROTATION_RANGE_DEG) return

        // Edge directions from calibration node (map space)
        val edgeAnglesRad = neighbors.map { neighbor ->
            val dx = (neighbor.position[0] - node.position[0]).toFloat()
            val dz = (neighbor.position[2] - node.position[2]).toFloat()
            atan2(dx.toDouble(), dz.toDouble()).toFloat()
        }

        var bestYaw = 0f
        var bestScore = Float.MAX_VALUE

        // Coarse pass: 2° resolution
        for (deg in 0 until 360 step 2) {
            val candidateYaw = Math.toRadians(deg.toDouble()).toFloat()
            val score = scoreRotationCandidate(candidateYaw, facingSamples, edgeAnglesRad)
            if (score < bestScore) {
                bestScore = score
                bestYaw = candidateYaw
            }
        }

        // Fine pass: ±3° at 0.5°
        val coarseDeg = Math.toDegrees(bestYaw.toDouble())
        for (i in -6..6) {
            val candidateYaw = Math.toRadians(coarseDeg + i * 0.5).toFloat()
            val score = scoreRotationCandidate(candidateYaw, facingSamples, edgeAnglesRad)
            if (score < bestScore) {
                bestScore = score
                bestYaw = candidateYaw
            }
        }

        // Confidence check: average angular error per sample
        val avgErrorRad = bestScore / facingSamples.size
        if (avgErrorRad > Math.toRadians(50.0).toFloat()) return // Too uncertain

        yawOffset = bestYaw
        isCalibrated = true
        calibratedByRotation = true

        Log.d(TAG, "✅ Rotation calibration complete!")
        Log.d(TAG, "   Yaw: ${String.format("%.1f", Math.toDegrees(yawOffset.toDouble()))}°")
        Log.d(TAG, "   Samples: ${facingSamples.size}, Range: ${String.format("%.0f", range)}°")
        Log.d(TAG, "   Avg edge alignment error: ${String.format("%.1f", Math.toDegrees(avgErrorRad.toDouble()))}°")

        runOnUiThread {
            binding.tvOcrStatus.text = "Calibrated ✓ (${String.format("%.0f", Math.toDegrees(yawOffset.toDouble()))}°)"
        }

        // Give direction guidance if already navigating
        if (isNavigating && currentPath != null && userCurrentPosition != null && !initialGuidanceGiven) {
            val dir = computeInitialDirection(currentPath!!, userCurrentPosition!!)
            speak("Calibrated. $dir and follow the arrows.")
            initialGuidanceGiven = true
        }
    }

    /**
     * Score a candidate yaw for rotation calibration.
     * For each facing sample, transforms to map space and measures angular
     * distance to the nearest graph edge direction.
     */
    private fun scoreRotationCandidate(
        candidateYaw: Float,
        samples: List<FacingSample>,
        edgeAnglesRad: List<Float>
    ): Float {
        val cosY = cos(candidateYaw.toDouble()).toFloat()
        val sinY = sin(candidateYaw.toDouble()).toFloat()

        var totalScore = 0f
        for (sample in samples) {
            val mapFwdX = sample.localFwdX * cosY + sample.localFwdZ * sinY
            val mapFwdZ = -sample.localFwdX * sinY + sample.localFwdZ * cosY
            val mapAngle = atan2(mapFwdX.toDouble(), mapFwdZ.toDouble()).toFloat()

            var minEdgeDist = Float.MAX_VALUE
            for (edgeAngle in edgeAnglesRad) {
                var diff = abs(mapAngle - edgeAngle)
                if (diff > Math.PI.toFloat()) diff = (2 * Math.PI).toFloat() - diff
                if (diff < minEdgeDist) minEdgeDist = diff
            }
            totalScore += minEdgeDist
        }
        return totalScore
    }

    /**
     * Compute the angular range spanned by a set of angles (in degrees).
     * Uses the max-gap method: range = 360° - largest gap between sorted angles.
     */
    private fun computeAngularRange(anglesDeg: List<Float>): Float {
        if (anglesDeg.size < 2) return 0f
        val normalized = anglesDeg.map { ((it % 360) + 360) % 360 }
        val sorted = normalized.sorted()
        var maxGap = 0f
        for (i in 0 until sorted.size - 1) {
            maxGap = maxOf(maxGap, sorted[i + 1] - sorted[i])
        }
        maxGap = maxOf(maxGap, 360f - sorted.last() + sorted.first())
        return 360f - maxGap
    }

    /**
     * Collect all unique graph edges as line segments for calibration scoring.
     */
    private fun collectGraphEdges(graph: FloorGraph): List<EdgeSegment> {
        val edges = mutableListOf<EdgeSegment>()
        val seen = mutableSetOf<String>()

        for ((_, edgeList) in graph.adjacencyList) {
            for (edge in edgeList) {
                val key = if (edge.from < edge.to) "${edge.from}-${edge.to}" else "${edge.to}-${edge.from}"
                if (seen.contains(key)) continue
                seen.add(key)

                val nodeA = graph.nodes[edge.from] ?: continue
                val nodeB = graph.nodes[edge.to] ?: continue
                edges.add(EdgeSegment(
                    nodeA.position[0].toFloat(), nodeA.position[2].toFloat(),
                    nodeB.position[0].toFloat(), nodeB.position[2].toFloat()
                ))
            }
        }
        return edges
    }

    /**
     * Find the minimum distance from point (px, pz) to any graph edge segment.
     */
    private fun distanceToNearestEdge(px: Float, pz: Float, edges: List<EdgeSegment>): Float {
        var minDist = Float.MAX_VALUE
        for (edge in edges) {
            val d = pointToSegmentDist(px, pz, edge.ax, edge.az, edge.bx, edge.bz)
            if (d < minDist) minDist = d
        }
        return minDist
    }

    /**
     * Distance from point P to line segment AB in the XZ plane.
     * Projects P onto line AB and clamps to segment endpoints.
     */
    private fun pointToSegmentDist(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Float {
        val dx = bx - ax
        val dz = bz - az
        val lenSq = dx * dx + dz * dz
        if (lenSq < 0.0001f) {
            // Degenerate segment (zero length)
            val ex = px - ax
            val ez = pz - az
            return sqrt(ex * ex + ez * ez)
        }
        // Project P onto AB, clamp t to [0,1]
        val t = (((px - ax) * dx + (pz - az) * dz) / lenSq).coerceIn(0f, 1f)
        val cx = ax + t * dx
        val cz = az + t * dz
        val ex = px - cx
        val ez = pz - cz
        return sqrt(ex * ex + ez * ez)
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

        val isLastNode = currentWaypointIndex == path.nodes.size - 1
        val targetLabel = if (isLastNode) path.nodes.last().name else "next turn"
        runOnUiThread {
            binding.tvDirection.text = String.format("%.1f m to %s", distance, targetLabel)
        }

        if (distance < WAYPOINT_REACHED_DISTANCE) {
            currentWaypointIndex++
            if (currentWaypointIndex >= path.nodes.size) {
                // Store destination position so avatar can appear there facing user
                val destNode = path.nodes.last()
                destinationReachedMapPos = floatArrayOf(
                    destNode.position[0].toFloat(),
                    destNode.position[1].toFloat(),
                    destNode.position[2].toFloat()
                )
                hasReachedDestination = true

                isNavigating = false
                currentPath = null
                speak("You have reached your destination.")
                runOnUiThread {
                    binding.tvDirection.text = "✅ You have arrived!"
                    binding.tvStatus.text = "Destination reached"
                }
            } else {
                // Smart navigation: arrows guide visually at turn points
                // Don't announce intermediate named locations — go direct to destination
            }
        }
    }

    // Generate a rolling window of 3-4 arrows just ahead of the user (Map Coordinates)
    // Since this is called every frame with the user's current position, arrows
    // naturally "roll forward" as the user walks — old arrows disappear behind,
    // new ones appear ahead. Max error at 3m with 2° calibration error ≈ 0.10m.
    private fun generateContinuousArrows(path: List<GraphNode>, userPosition: List<Float>): List<ArrowPosition> {
        if (path.isEmpty()) return emptyList()

        val arrows = mutableListOf<ArrowPosition>()
        var arrowCount = 0

        data class PathPoint(val x: Float, val y: Float, val z: Float)

        val polyline = mutableListOf<PathPoint>()
        polyline.add(PathPoint(userPosition[0], userPosition[1], userPosition[2]))
        for (node in path) {
            polyline.add(PathPoint(
                node.position[0].toFloat(),
                node.position[1].toFloat(),
                node.position[2].toFloat()
            ))
        }

        var isFirstSegment = true
        var cumulativeDistance = 0f

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

            var distance = if (isFirstSegment) 0.5f else 0f
            isFirstSegment = false

            while (distance < segmentLength && arrowCount < MAX_VISIBLE_ARROWS) {
                val distFromUser = cumulativeDistance + distance
                if (distFromUser > MAX_ARROW_DISTANCE) break

                val t = distance / segmentLength

                val arrowX = start.x + dx * t
                val arrowY = start.y + dy * t + ARROW_HEIGHT_OFFSET
                val arrowZ = start.z + dz * t

                arrows.add(ArrowPosition(arrowX, arrowY, arrowZ, angleY))
                arrowCount++

                distance += ARROW_SPACING
            }

            cumulativeDistance += segmentLength
            if (cumulativeDistance > MAX_ARROW_DISTANCE) break
        }

        if (arrows.isNotEmpty()) {
            Log.v(TAG, "📍 Rolling arrows: ${arrows.size} within ${String.format("%.1f", MAX_ARROW_DISTANCE)}m")
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

                    // Alert the visitor (warning only — does NOT stop navigation)
                    speak("Warning! You are near a restricted area: ${area.name}. Please be cautious.")

                    runOnUiThread {
                        binding.tvStatus.text = "⛔ RESTRICTED: ${area.name}"
                        Toast.makeText(this, "⛔ Restricted Area: ${area.name}", Toast.LENGTH_LONG).show()
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

            // Store camera forward direction for directional guidance
            lastCameraForwardX = -camera.pose.zAxis[0]
            lastCameraForwardZ = -camera.pose.zAxis[2]

            // Calibration: try rotation first (instant), then walking for refinement
            if (isPositionRecognized) {
                if (!isCalibrated) {
                    tryRotationCalibration()
                }
                tryCompleteCalibration(cameraPosLocal)
            }

            // Map local AR coordinates to stored Map coordinates (with rotation)
            val mappedUserPos = localToMap(cameraPosLocal)
            userCurrentPosition = mappedUserPos

            // Check restricted area proximity
            if (isPositionRecognized) {
                checkRestrictedAreaProximity(mappedUserPos)

                // Periodic debug: log restricted area distances every ~2 seconds
                if (System.currentTimeMillis() % 2000 < 50) {
                    floorGraph?.getRestrictedAreas()?.forEach { area ->
                        val dist = floorGraph!!.calculateDistanceFromFloat(mappedUserPos, area.position)
                        Log.d(TAG, "📍 Distance to restricted '${area.name}': ${String.format("%.1f", dist)}m | mapped=[${String.format("%.2f", mappedUserPos[0])}, ${String.format("%.2f", mappedUserPos[2])}] area=[${String.format("%.2f", area.position[0])}, ${String.format("%.2f", area.position[2])}]")
                    }
                }
            }

            if (!isPositionRecognized) {
                recognizeUserPosition(cameraPosLocal)
            }

            // Process pending navigation (path computation doesn't need calibration)
            if (pendingDestination != null) {
                processPendingNavigation()
            }

            if (isNavigating && currentPath != null) {
                if (isCalibrated) {
                    // Arrows only render when calibrated — never shows wrong direction
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

                    // Draw destination marker at the exact last node position (same height as arrows)
                    renderer?.let { r ->
                        val destNode = path.nodes.last()
                        val localDest = mapToLocal(
                            destNode.position[0].toFloat(),
                            destNode.position[1].toFloat(),
                            destNode.position[2].toFloat()
                        )
                        r.draw(viewMatrix, projectionMatrix, localDest[0], arrowLocalY, localDest[2], destinationColor)
                    }
                } else {
                    // Path ready but calibration pending — no arrows shown yet
                    runOnUiThread {
                        binding.tvDirection.text = "Look around slowly..."
                        binding.tvStatus.text = "Calibrating direction..."
                    }
                }
            } else if (hasReachedDestination && destinationReachedMapPos != null) {
                // Destination reached: show avatar at fixed distance from user (same size as idle)
                // facing the user directly, NOT placed at the destination coordinate
                val avatarX = cameraPosLocal[0] - (camera.pose.zAxis[0] * AVATAR_DISPLAY_DISTANCE)
                val avatarY = cameraPosLocal[1] - 1.5f
                val avatarZ = cameraPosLocal[2] - (camera.pose.zAxis[2] * AVATAR_DISPLAY_DISTANCE)

                // Compute yaw so avatar faces the user
                val adx = cameraPosLocal[0] - avatarX
                val adz = cameraPosLocal[2] - avatarZ
                val facingYaw = Math.toDegrees(atan2(adx.toDouble(), adz.toDouble())).toFloat()

                avatarRenderer?.draw(viewMatrix, projectionMatrix, avatarX, avatarY, avatarZ, facingYaw)
            } else {
                // Show avatar when not navigating (idle, in front of camera)
                avatarRenderer?.draw(viewMatrix, projectionMatrix, cameraPosLocal[0] - (camera.pose.zAxis[0] * AVATAR_DISPLAY_DISTANCE), cameraPosLocal[1] - 1.5f, cameraPosLocal[2] - (camera.pose.zAxis[2] * AVATAR_DISPLAY_DISTANCE))
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