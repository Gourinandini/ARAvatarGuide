package com.example.aravatarguide

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ARActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    private var arSession: Session? = null
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tvInstruction: TextView
    private lateinit var tvRecordingStatus: TextView
    private lateinit var tvWaypointCount: TextView
    private lateinit var tvPathInfo: TextView
    private lateinit var etStartPoint: EditText
    private lateinit var btnStartRecording: Button
    private lateinit var btnMarkWaypoint: Button
    private lateinit var btnCaptureText: Button
    private lateinit var btnStopRecording: Button
    private lateinit var btnEmergency: Button
    private lateinit var layoutStartPoint: LinearLayout

    private var installRequested = false
    private var renderer: SimpleRenderer? = null
    private var backgroundRenderer: BackgroundRenderer? = null
    private val pathRecorder = PathRecorder()
    private var pendingWaypointName: String? = null
    private var isEmergencyExit: Boolean = false
    private var isRestrictedArea: Boolean = false

    private val waypointColor = floatArrayOf(0.0f, 1.0f, 0.0f, 1.0f) // Green
    private val namedWaypointColor = floatArrayOf(1.0f, 0.84f, 0.0f, 1.0f) // Gold
    private val emergencyExitColor = floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f) // Red
    private val restrictedAreaColor = floatArrayOf(0.6f, 0.0f, 0.8f, 1.0f) // Purple

    private lateinit var database: FirebaseDatabase
    private lateinit var firebasePathManager: FirebasePathManager
    private var isEmergencyActive = false

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var shouldCaptureText = false

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val TAG = "ARActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aractivity)

        database = FirebaseDatabase.getInstance()
        firebasePathManager = FirebasePathManager()

        // Initialize views
        surfaceView = findViewById(R.id.surfaceView)
        tvInstruction = findViewById(R.id.tvInstruction)
        tvRecordingStatus = findViewById(R.id.tvRecordingStatus)
        tvWaypointCount = findViewById(R.id.tvWaypointCount)
        tvPathInfo = findViewById(R.id.tvPathInfo)
        etStartPoint = findViewById(R.id.etStartPoint)
        btnStartRecording = findViewById(R.id.btnStartRecording)
        btnMarkWaypoint = findViewById(R.id.btnMarkWaypoint)
        btnCaptureText = findViewById(R.id.btnCaptureText)
        btnStopRecording = findViewById(R.id.btnStopRecording)
        btnEmergency = findViewById(R.id.btnEmergency)
        layoutStartPoint = findViewById(R.id.layoutStartPoint)

        // Setup OpenGL
        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(this)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        // Setup buttons
        btnStartRecording.setOnClickListener { startRecording() }
        btnMarkWaypoint.setOnClickListener { showNameWaypointDialog() }
        btnCaptureText.setOnClickListener { shouldCaptureText = true }
        btnStopRecording.setOnClickListener { stopRecording() }
        btnEmergency.setOnClickListener { toggleEmergency() }

        // Initially hide these buttons
        btnMarkWaypoint.visibility = View.GONE
        btnCaptureText.visibility = View.GONE
        btnStopRecording.visibility = View.GONE

        if (!hasCameraPermission()) {
            requestCameraPermission()
        }

        listenAndSetEmergencyState()
        listenForRestrictedAreaBreaches()
    }

    /**
     * Listen for real-time alerts when a visitor enters a restricted area.
     * Shows a Toast + audible alert to the host.
     */
    private fun listenForRestrictedAreaBreaches() {
        database.getReference("restrictedAreaBreach").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isActive = snapshot.child("active").getValue(Boolean::class.java) ?: false
                if (isActive) {
                    val areaName = snapshot.child("areaName").getValue(String::class.java) ?: "Unknown"
                    runOnUiThread {
                        AlertDialog.Builder(this@ARActivity)
                            .setTitle("⛔ RESTRICTED AREA BREACH")
                            .setMessage("A visitor has entered the restricted area: $areaName\n\nPlease take necessary action.")
                            .setPositiveButton("Acknowledge") { _, _ ->
                                // Clear the breach flag
                                database.getReference("restrictedAreaBreach/active").setValue(false)
                            }
                            .setCancelable(false)
                            .show()
                    }
                    Log.w(TAG, "⛔ ALERT: Visitor entered restricted area: $areaName")
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Restricted area listener cancelled: ${error.message}")
            }
        })
    }

    private fun listenAndSetEmergencyState() {
        val emergencyRef = database.getReference("emergency")
        emergencyRef.setValue(false) // Reset on startup

        emergencyRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val emergencyStatus = snapshot.getValue(Boolean::class.java) ?: false
                isEmergencyActive = emergencyStatus
                updateEmergencyButton()
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Emergency listener cancelled: ${error.message}")
            }
        })
    }

    private fun toggleEmergency() {
        database.getReference("emergency").setValue(!isEmergencyActive)
    }

    private fun updateEmergencyButton() {
        if (isEmergencyActive) {
            btnEmergency.text = "🚨 EMERGENCY ACTIVE - TAP TO CANCEL 🚨"
            btnEmergency.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_green_dark)
        } else {
            btnEmergency.text = "🚨 TRIGGER EMERGENCY 🚨"
            btnEmergency.backgroundTintList = ContextCompat.getColorStateList(this, android.R.color.holo_red_light)
        }
    }

    private fun startRecording() {
        val startName = etStartPoint.text.toString().trim()

        if (startName.isEmpty()) {
            Toast.makeText(this, "Please enter starting point name", Toast.LENGTH_SHORT).show()
            return
        }

        pathRecorder.startRecording(startName)

        // Update UI
        runOnUiThread {
            layoutStartPoint.visibility = View.GONE
            btnMarkWaypoint.visibility = View.VISIBLE
            btnCaptureText.visibility = View.VISIBLE
            btnStopRecording.visibility = View.VISIBLE
            tvRecordingStatus.visibility = View.VISIBLE
            tvWaypointCount.visibility = View.VISIBLE
            tvPathInfo.visibility = View.VISIBLE
            tvInstruction.text = "Walk around and mark important waypoints"
            tvPathInfo.text = "Recording: $startName (Starting Point)"
            hideKeyboard()

            Toast.makeText(this, "🎬 Recording started from $startName", Toast.LENGTH_LONG).show()
        }
    }

    private fun showNameWaypointDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_name_waypoint, null)
        val editTextName = dialogView.findViewById<EditText>(R.id.etWaypointName)
        val cbEmergencyExit = dialogView.findViewById<CheckBox>(R.id.cbEmergencyExit)
        val cbRestrictedArea = dialogView.findViewById<CheckBox>(R.id.cbRestrictedArea)

        AlertDialog.Builder(this)
            .setTitle("Name This Waypoint")
            .setMessage("Enter a name for this major location")
            .setView(dialogView)
            .setPositiveButton("Mark") { _, _ ->
                val name = editTextName.text.toString().trim()
                isEmergencyExit = cbEmergencyExit.isChecked
                isRestrictedArea = cbRestrictedArea.isChecked
                markCurrentPositionAsWaypoint(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun markCurrentPositionAsWaypoint(name: String) {
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter a waypoint name", Toast.LENGTH_SHORT).show()
            return
        }

        if (!pathRecorder.isCurrentlyRecording()) {
            Toast.makeText(this, "Recording is not active", Toast.LENGTH_SHORT).show()
            return
        }

        // Store the name to be marked on the next AR frame
        pendingWaypointName = name
        Toast.makeText(this, "Marking waypoint '$name'...", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        val graph = pathRecorder.stopRecording()

        if (graph.getNamedWaypointCount() < 2) {
            Toast.makeText(
                this,
                "⚠️ Please mark at least 2 named waypoints before saving",
                Toast.LENGTH_LONG
            ).show()
            pathRecorder.startRecording(etStartPoint.text.toString().trim())
            return
        }

        Log.d(TAG, "Saving graph with ${graph.getNodeCount()} nodes, ${graph.getNamedWaypointCount()} named")

        firebasePathManager.saveFloorGraph(graph) { success ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(
                        this,
                        "✅ Floor map saved to Firebase!\nTotal waypoints: ${graph.getNodeCount()}",
                        Toast.LENGTH_LONG
                    ).show()

                    tvRecordingStatus.visibility = View.GONE
                    tvWaypointCount.visibility = View.GONE
                    tvPathInfo.visibility = View.GONE
                    btnMarkWaypoint.visibility = View.GONE
                    btnCaptureText.visibility = View.GONE
                    btnStopRecording.visibility = View.GONE
                    layoutStartPoint.visibility = View.VISIBLE
                    etStartPoint.text.clear()
                    tvInstruction.text = "Floor map saved! Create another or go back."
                } else {
                    Toast.makeText(this, "❌ Failed to save map to Firebase", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateWaypointCount() {
        val totalCount = pathRecorder.getWaypointCount()
        val namedCount = pathRecorder.getNamedWaypointCount()
        tvWaypointCount.text = "Waypoints: $totalCount | Named: $namedCount"
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etStartPoint.windowToken, 0)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
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
                arSession?.configure(Config(arSession).apply { updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AR session", e)
                return
            }
        }
        try {
            arSession?.resume()
            surfaceView.onResume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
            arSession = null
        }
    }

    override fun onPause() {
        super.onPause()
        surfaceView.onPause()
        arSession?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        arSession?.close()
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f)
        backgroundRenderer = BackgroundRenderer().apply { createOnGlThread(this@ARActivity) }
        renderer = SimpleRenderer().apply { createOnGlThread() }
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        arSession?.setDisplayGeometry(0, width, height)
    }

    override fun onDrawFrame(gl: GL10) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val session = arSession ?: return
        try {
            session.setCameraTextureName(backgroundRenderer?.getTextureId() ?: 0)
            val frame = session.update()
            val camera = frame.camera

            backgroundRenderer?.draw(frame)

            if (camera.trackingState != TrackingState.TRACKING) return

            // OCR Capture logic
            if (shouldCaptureText) {
                shouldCaptureText = false
                processImageForOCR(frame)
            }

            if (pathRecorder.isCurrentlyRecording()) {
                val pendingName = pendingWaypointName
                if (pendingName != null) {
                    val success = pathRecorder.markNamedWaypoint(camera.pose, pendingName, isEmergencyExit, isRestrictedArea)
                    runOnUiThread {
                        if (success) {
                            val marker = when {
                                isRestrictedArea -> "⛔ Restricted"
                                isEmergencyExit -> "🚨 Emergency Exit"
                                else -> "📌"
                            }
                            Toast.makeText(this@ARActivity, "✅ $marker '$pendingName' marked!", Toast.LENGTH_SHORT).show()
                            updateWaypointCount()
                        } else {
                            Toast.makeText(this@ARActivity, "❌ Name already exists", Toast.LENGTH_SHORT).show()
                        }
                    }
                    pendingWaypointName = null
                    isEmergencyExit = false
                    isRestrictedArea = false
                } else {
                    if (pathRecorder.updatePosition(camera.pose)) {
                        runOnUiThread { updateWaypointCount() }
                    }
                }
            }   

            val viewMatrix = FloatArray(16)
            val projectionMatrix = FloatArray(16)
            camera.getViewMatrix(viewMatrix, 0)
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100f)

            renderer?.let { r ->
                pathRecorder.getRecordedPoints().forEach { node ->
                    val color = when {
                        node.isRestrictedArea -> restrictedAreaColor
                        node.isEmergencyExit -> emergencyExitColor
                        node.isNamedWaypoint -> namedWaypointColor
                        else -> waypointColor
                    }
                    val pos = node.toFloatArray()
                    r.draw(viewMatrix, projectionMatrix, pos[0], pos[1], pos[2], color)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDrawFrame", e)
        }
    }

    private fun processImageForOCR(frame: com.google.ar.core.Frame) {
        try {
            val image = frame.acquireCameraImage()
            val inputImage = InputImage.fromMediaImage(image, 90) // ARCore camera is usually rotated 90deg

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    image.close()
                    val detectedStrings = visionText.textBlocks.map { it.text }
                    if (detectedStrings.isNotEmpty()) {
                        showOCRSelectionDialog(detectedStrings)
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, "No text detected", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    image.close()
                    Log.e(TAG, "OCR failed", e)
                }
        } catch (e: NotYetAvailableException) {
            Log.e(TAG, "Frame not yet available for OCR", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring camera image", e)
        }
    }

    private fun showOCRSelectionDialog(detectedTexts: List<String>) {
        runOnUiThread {
            val items = detectedTexts.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Select Detected Text")
                .setItems(items) { _, which ->
                    val selectedText = items[which]
                    markCurrentPositionAsWaypoint(selectedText.uppercase())
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}