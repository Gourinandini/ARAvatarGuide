package com.example.aravatarguide

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

/**
 * A stylized low-poly guide avatar with:
 *  - Sphere & cylinder geometry (not cubes)
 *  - Per-fragment Phong lighting (ambient + diffuse + specular)
 *  - Smooth idle / wave / speaking / blink animations
 *  - Friendly "navigation assistant" robot-character look
 */
class AvatarRenderer {

    // ── GL handles ──────────────────────────────────────────────
    private var program = 0
    private var aPosition = 0
    private var aNormal = 0
    private var uMvpMatrix = 0
    private var uModelMatrix = 0
    private var uColor = 0
    private var uLightDir = 0
    private var uAmbient = 0
    private var uViewPos = 0
    private var uEmissive = 0

    // ── Geometry caches ─────────────────────────────────────────
    // Sphere
    private lateinit var sphereVB: FloatBuffer
    private lateinit var sphereNB: FloatBuffer
    private lateinit var sphereIB: ShortBuffer
    private var sphereIdxCount = 0

    // Cylinder
    private lateinit var cylVB: FloatBuffer
    private lateinit var cylNB: FloatBuffer
    private lateinit var cylIB: ShortBuffer
    private var cylIdxCount = 0

    // Rounded-box (beveled cube)
    private lateinit var boxVB: FloatBuffer
    private lateinit var boxNB: FloatBuffer
    private lateinit var boxIB: ShortBuffer
    private var boxIdxCount = 0

    // ── Animation state ─────────────────────────────────────────
    private var startTime = 0L
    var isSpeaking = false

    // ═══════════════════════════════════════════════════════════
    //  Initialisation
    // ═══════════════════════════════════════════════════════════

    fun createOnGlThread() {
        // ── Shaders ──
        val vertSrc = """
            uniform mat4 u_MvpMatrix;
            uniform mat4 u_ModelMatrix;
            attribute vec4 a_Position;
            attribute vec3 a_Normal;
            varying vec3 v_WorldPos;
            varying vec3 v_Normal;
            void main() {
                v_WorldPos = (u_ModelMatrix * a_Position).xyz;
                v_Normal   = normalize(mat3(u_ModelMatrix) * a_Normal);
                gl_Position = u_MvpMatrix * a_Position;
            }
        """.trimIndent()

        val fragSrc = """
            precision mediump float;
            uniform vec4 u_Color;
            uniform vec3 u_LightDir;
            uniform float u_Ambient;
            uniform vec3 u_ViewPos;
            uniform float u_Emissive;
            varying vec3 v_WorldPos;
            varying vec3 v_Normal;
            void main() {
                vec3 N = normalize(v_Normal);
                vec3 L = normalize(u_LightDir);
                float diff = max(dot(N, L), 0.0);

                vec3 V = normalize(u_ViewPos - v_WorldPos);
                vec3 H = normalize(L + V);
                float spec = pow(max(dot(N, H), 0.0), 32.0) * 0.35;

                float rim = 1.0 - max(dot(V, N), 0.0);
                rim = smoothstep(0.55, 1.0, rim) * 0.25;

                vec3 lit = u_Color.rgb * (u_Ambient + diff * 0.65) + vec3(spec) + vec3(rim) * u_Color.rgb;
                lit = mix(lit, u_Color.rgb, u_Emissive);
                gl_FragColor = vec4(lit, u_Color.a);
            }
        """.trimIndent()

        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        aPosition   = GLES20.glGetAttribLocation(program, "a_Position")
        aNormal     = GLES20.glGetAttribLocation(program, "a_Normal")
        uMvpMatrix  = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        uModelMatrix= GLES20.glGetUniformLocation(program, "u_ModelMatrix")
        uColor      = GLES20.glGetUniformLocation(program, "u_Color")
        uLightDir   = GLES20.glGetUniformLocation(program, "u_LightDir")
        uAmbient    = GLES20.glGetUniformLocation(program, "u_Ambient")
        uViewPos    = GLES20.glGetUniformLocation(program, "u_ViewPos")
        uEmissive   = GLES20.glGetUniformLocation(program, "u_Emissive")

        buildSphere(24, 24)
        buildCylinder(20, 1)
        buildRoundedBox()

        startTime = System.currentTimeMillis()
    }

    // ═══════════════════════════════════════════════════════════
    //  Draw  (same public signature – drop-in replacement)
    // ═══════════════════════════════════════════════════════════

    fun draw(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        x: Float, y: Float, z: Float
    ) {
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        // Global light direction (from upper-right-front)
        GLES20.glUniform3f(uLightDir, 0.4f, 0.85f, 0.35f)
        GLES20.glUniform1f(uAmbient, 0.38f)
        // Camera position for specular (extract from view matrix inverse)
        val invView = FloatArray(16)
        Matrix.invertM(invView, 0, viewMatrix, 0)
        GLES20.glUniform3f(uViewPos, invView[12], invView[13], invView[14])

        val t = (System.currentTimeMillis() - startTime) / 1000.0f

        // ── Base matrix ─────────────────────────────────────────
        val base = FloatArray(16)
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, x, y, z)
        // Gentle hover
        val hover = sin(t * 1.8f) * 0.015f
        Matrix.translateM(base, 0, 0f, hover, 0f)

        // ── Palette ─────────────────────────────────────────────
        // A friendly robot-guide with teal/cyan accent (matches app theme)
        val bodyMain    = floatArrayOf(0.18f, 0.22f, 0.28f, 1f)   // dark blue-grey body
        val bodyAccent  = floatArrayOf(0.0f,  0.74f, 0.83f, 1f)   // cyan / teal highlights
        val visor       = floatArrayOf(0.35f, 0.85f, 0.95f, 0.92f)// bright cyan visor
        val white       = floatArrayOf(1f, 1f, 1f, 1f)
        val black       = floatArrayOf(0.05f, 0.05f, 0.08f, 1f)
        val skinTone    = floatArrayOf(0.95f, 0.82f, 0.72f, 1f)
        val mouthColor  = floatArrayOf(0.85f, 0.30f, 0.30f, 1f)
        val accentGlow  = floatArrayOf(0.0f, 0.90f, 1.0f, 1f)     // emissive glow
        val shoeColor   = floatArrayOf(0.12f, 0.12f, 0.14f, 1f)
        val pantColor   = floatArrayOf(0.14f, 0.16f, 0.22f, 1f)

        // ── Dimensions ──────────────────────────────────────────
        val headR = 0.13f
        val neckH = 0.04f; val neckR = 0.045f
        val torsoH = 0.40f; val torsoRx = 0.16f; val torsoRz = 0.10f
        val hipH = 0.06f
        val upperArmH = 0.22f; val armR = 0.04f
        val lowerArmH = 0.20f
        val handR = 0.045f
        val upperLegH = 0.28f; val legR = 0.055f
        val lowerLegH = 0.26f
        val footH = 0.05f; val footRx = 0.06f; val footRz = 0.10f
        val legSpacing = 0.08f
        val shoulderOff = torsoRx + armR * 0.5f
        val torsoCenter = 0.88f  // Y center of torso above feet

        // ──────────────────────────────────────────────────────
        //  FEET
        // ──────────────────────────────────────────────────────
        // Idle walk-sway
        val walkAngle = sin(t * 2.5f) * 4f
        for (side in intArrayOf(-1, 1)) {
            val legAngle = walkAngle * side
            val legM = matCopy(base)
            Matrix.translateM(legM, 0, side * legSpacing, footH + lowerLegH + upperLegH, 0f)
            Matrix.rotateM(legM, 0, legAngle, 1f, 0f, 0f)

            // Upper leg
            drawCylinder(viewMatrix, projectionMatrix, legM,
                0f, -upperLegH / 2f, 0f, legR, upperLegH, pantColor)

            // Knee joint
            val kneeM = matCopy(legM)
            Matrix.translateM(kneeM, 0, 0f, -upperLegH, 0f)
            Matrix.rotateM(kneeM, 0, -legAngle * 0.5f + 4f, 1f, 0f, 0f) // slight natural bend
            drawSphere(viewMatrix, projectionMatrix, kneeM, 0f, 0f, 0f, legR * 1.05f, pantColor)

            // Lower leg
            drawCylinder(viewMatrix, projectionMatrix, kneeM,
                0f, -lowerLegH / 2f, 0f, legR * 0.9f, lowerLegH, pantColor)

            // Foot
            drawBox(viewMatrix, projectionMatrix, base,
                side * legSpacing, footH / 2f, 0.02f,
                footRx, footH, footRz, shoeColor)
            // Shoe accent stripe
            drawBox(viewMatrix, projectionMatrix, base,
                side * legSpacing, footH * 0.55f, footRz * 0.55f,
                footRx * 0.8f, 0.012f, 0.025f, bodyAccent, emissive = 0.5f)
        }

        // ──────────────────────────────────────────────────────
        //  TORSO
        // ──────────────────────────────────────────────────────
        // Torso (slightly tapered cylinder)
        drawCylinder(viewMatrix, projectionMatrix, base,
            0f, torsoCenter, 0f, torsoRx, torsoH, bodyMain)

        // Belt / waist accent ring
        drawCylinder(viewMatrix, projectionMatrix, base,
            0f, torsoCenter - torsoH * 0.42f, 0f, torsoRx * 1.04f, 0.025f, bodyAccent, emissive = 0.3f)

        // Chest emblem (small glowing sphere)
        drawSphere(viewMatrix, projectionMatrix, base,
            0f, torsoCenter + 0.06f, torsoRz + 0.01f, 0.025f, accentGlow, emissive = 0.85f)
        // Chest plate highlight
        drawBox(viewMatrix, projectionMatrix, base,
            0f, torsoCenter + 0.06f, torsoRz * 0.5f,
            0.09f, 0.12f, 0.005f, bodyAccent, emissive = 0.15f)

        // Hip section
        drawCylinder(viewMatrix, projectionMatrix, base,
            0f, torsoCenter - torsoH / 2f - hipH / 2f, 0f,
            torsoRx * 0.95f, hipH, bodyMain)

        // Shoulder pads
        for (side in intArrayOf(-1, 1)) {
            drawSphere(viewMatrix, projectionMatrix, base,
                side * shoulderOff, torsoCenter + torsoH / 2f - 0.04f, 0f,
                armR * 1.6f, bodyAccent)
        }

        // ──────────────────────────────────────────────────────
        //  NECK & HEAD
        // ──────────────────────────────────────────────────────
        val neckY = torsoCenter + torsoH / 2f + neckH / 2f
        drawCylinder(viewMatrix, projectionMatrix, base, 0f, neckY, 0f, neckR, neckH, skinTone)

        val headY = neckY + neckH / 2f + headR
        // Head idle rotation
        val headYaw = sin(t * 0.7f) * 8f

        val headBase = matCopy(base)
        Matrix.translateM(headBase, 0, 0f, headY, 0f)
        Matrix.rotateM(headBase, 0, headYaw, 0f, 1f, 0f)

        // Main head sphere
        drawSphere(viewMatrix, projectionMatrix, headBase, 0f, 0f, 0f, headR, skinTone)

        // Hair (top cap + back)
        val hairColor = floatArrayOf(0.12f, 0.08f, 0.06f, 1f)
        drawSphere(viewMatrix, projectionMatrix, headBase,
            0f, headR * 0.35f, -headR * 0.1f, headR * 1.02f, hairColor)
        // Fringe
        drawBox(viewMatrix, projectionMatrix, headBase,
            0f, headR * 0.55f, headR * 0.3f,
            headR * 0.8f, 0.03f, headR * 0.5f, hairColor)

        // Helmet visor (futuristic band across eyes)
        drawBox(viewMatrix, projectionMatrix, headBase,
            0f, 0.015f, headR * 0.85f,
            headR * 1.05f, 0.045f, 0.025f, visor, emissive = 0.6f)

        // ── Face ────────────────────────────────────────────────
        val blink = (t % 3.5f) > 3.3f
        val eyeH = if (blink) 0.004f else 0.022f

        // Eyes (on visor)
        for (side in floatArrayOf(-1f, 1f)) {
            drawBox(viewMatrix, projectionMatrix, headBase,
                side * 0.042f, 0.016f, headR * 0.88f,
                0.022f, eyeH, 0.012f, white, emissive = 0.9f)
            if (!blink) {
                // Pupil
                drawBox(viewMatrix, projectionMatrix, headBase,
                    side * 0.042f, 0.014f, headR * 0.895f,
                    0.011f, 0.012f, 0.008f, black)
            }
        }

        // Nose
        drawSphere(viewMatrix, projectionMatrix, headBase,
            0f, -0.015f, headR * 0.95f, 0.015f, skinTone)

        // Mouth
        val mouthOpen = if (isSpeaking) (sin(t * 18f) * 0.5f + 0.5f) * 0.018f else 0f
        drawBox(viewMatrix, projectionMatrix, headBase,
            0f, -0.048f - mouthOpen * 0.5f, headR * 0.87f,
            0.04f, 0.008f + mouthOpen, 0.01f, mouthColor)
        // Smile corners (when not speaking)
        if (!isSpeaking) {
            for (side in floatArrayOf(-1f, 1f)) {
                drawBox(viewMatrix, projectionMatrix, headBase,
                    side * 0.035f, -0.044f, headR * 0.86f,
                    0.012f, 0.006f, 0.008f, mouthColor)
            }
        }

        // Ears (small spheres)
        for (side in floatArrayOf(-1f, 1f)) {
            drawSphere(viewMatrix, projectionMatrix, headBase,
                side * headR * 0.95f, -0.01f, 0f, 0.025f, skinTone)
            // Ear accent (comm device)
            drawSphere(viewMatrix, projectionMatrix, headBase,
                side * headR * 1.0f, -0.01f, 0f, 0.015f, bodyAccent, emissive = 0.4f)
        }

        // ── Antenna (small navigation beacon on top of head) ───
        val antennaBase = matCopy(headBase)
        val antennaPulse = (sin(t * 3f) * 0.5f + 0.5f)
        drawCylinder(viewMatrix, projectionMatrix, antennaBase,
            0f, headR + 0.03f, 0f, 0.008f, 0.06f, bodyMain)
        // Glowing tip
        val tipGlow = floatArrayOf(0f, 0.9f, 1f, 0.6f + antennaPulse * 0.4f)
        drawSphere(viewMatrix, projectionMatrix, antennaBase,
            0f, headR + 0.07f, 0f, 0.018f + antennaPulse * 0.005f, tipGlow, emissive = 0.95f)

        // ──────────────────────────────────────────────────────
        //  ARMS
        // ──────────────────────────────────────────────────────
        val shoulderY = torsoCenter + torsoH / 2f - 0.04f
        val isWaving = t < 6f

        for (side in intArrayOf(-1, 1)) {
            val isRight = side == 1

            // Upper arm rotation
            val upperZ: Float
            val lowerZ: Float
            if (isRight && isWaving) {
                upperZ = 135f
                lowerZ = 40f + sin(t * 10f) * 30f
            } else {
                upperZ = side * (sin(t * 2f) * 4f + 5f)
                lowerZ = 8f
            }

            val armM = matCopy(base)
            Matrix.translateM(armM, 0, side * shoulderOff, shoulderY, 0f)
            Matrix.rotateM(armM, 0, upperZ, 0f, 0f, 1f)

            // Upper arm
            drawCylinder(viewMatrix, projectionMatrix, armM,
                0f, -upperArmH / 2f, 0f, armR, upperArmH,
                if (isRight && isWaving) bodyAccent else bodyMain)

            // Elbow joint
            val elbowM = matCopy(armM)
            Matrix.translateM(elbowM, 0, 0f, -upperArmH, 0f)
            drawSphere(viewMatrix, projectionMatrix, elbowM, 0f, 0f, 0f, armR * 1.1f, bodyAccent)
            Matrix.rotateM(elbowM, 0, lowerZ * side.toFloat(), 0f, 0f, 1f)

            // Lower arm
            drawCylinder(viewMatrix, projectionMatrix, elbowM,
                0f, -lowerArmH / 2f, 0f, armR * 0.85f, lowerArmH, skinTone)

            // Hand sphere
            val handM = matCopy(elbowM)
            Matrix.translateM(handM, 0, 0f, -lowerArmH, 0f)
            drawSphere(viewMatrix, projectionMatrix, handM, 0f, -handR * 0.4f, 0f, handR, skinTone)

            // Wrist accent
            drawCylinder(viewMatrix, projectionMatrix, elbowM,
                0f, -lowerArmH + 0.01f, 0f, armR * 0.92f, 0.018f, bodyAccent, emissive = 0.3f)
        }

        // ── Backpack (navigation equipment) ─────────────────────
        drawBox(viewMatrix, projectionMatrix, base,
            0f, torsoCenter + 0.02f, -torsoRz - 0.04f,
            0.10f, 0.16f, 0.06f, bodyMain)
        // Backpack glow strip
        drawBox(viewMatrix, projectionMatrix, base,
            0f, torsoCenter + 0.02f, -torsoRz - 0.072f,
            0.06f, 0.12f, 0.004f, accentGlow, emissive = 0.5f)

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
    }

    // ═══════════════════════════════════════════════════════════
    //  High-level draw helpers
    // ═══════════════════════════════════════════════════════════

    private fun drawSphere(
        v: FloatArray, p: FloatArray, base: FloatArray,
        ox: Float, oy: Float, oz: Float, r: Float,
        color: FloatArray, emissive: Float = 0f
    ) {
        val m = matCopy(base)
        Matrix.translateM(m, 0, ox, oy, oz)
        Matrix.scaleM(m, 0, r, r, r)
        submitMesh(v, p, m, color, sphereVB, sphereNB, sphereIB, sphereIdxCount, emissive)
    }

    private fun drawCylinder(
        v: FloatArray, p: FloatArray, base: FloatArray,
        ox: Float, oy: Float, oz: Float,
        radius: Float, height: Float, color: FloatArray,
        emissive: Float = 0f
    ) {
        val m = matCopy(base)
        Matrix.translateM(m, 0, ox, oy, oz)
        Matrix.scaleM(m, 0, radius, height, radius)
        submitMesh(v, p, m, color, cylVB, cylNB, cylIB, cylIdxCount, emissive)
    }

    private fun drawBox(
        v: FloatArray, p: FloatArray, base: FloatArray,
        ox: Float, oy: Float, oz: Float,
        sx: Float, sy: Float, sz: Float,
        color: FloatArray, emissive: Float = 0f
    ) {
        val m = matCopy(base)
        Matrix.translateM(m, 0, ox, oy, oz)
        Matrix.scaleM(m, 0, sx, sy, sz)
        submitMesh(v, p, m, color, boxVB, boxNB, boxIB, boxIdxCount, emissive)
    }

    // ═══════════════════════════════════════════════════════════
    //  Submit mesh to GPU
    // ═══════════════════════════════════════════════════════════

    private fun submitMesh(
        viewMatrix: FloatArray, projMatrix: FloatArray,
        modelMatrix: FloatArray, color: FloatArray,
        vb: FloatBuffer, nb: FloatBuffer, ib: ShortBuffer,
        idxCount: Int, emissive: Float
    ) {
        val mvp = FloatArray(16)
        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvp, 0, projMatrix, 0, tmp, 0)

        GLES20.glUniformMatrix4fv(uMvpMatrix, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModelMatrix, 1, false, modelMatrix, 0)
        GLES20.glUniform4fv(uColor, 1, color, 0)
        GLES20.glUniform1f(uEmissive, emissive)

        vb.position(0); nb.position(0); ib.position(0)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, vb)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, nb)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, idxCount, GLES20.GL_UNSIGNED_SHORT, ib)
    }

    // ═══════════════════════════════════════════════════════════
    //  Geometry builders
    // ═══════════════════════════════════════════════════════════

    /** UV-sphere centred at origin, radius 1 */
    private fun buildSphere(stacks: Int, slices: Int) {
        val verts = mutableListOf<Float>()
        val norms = mutableListOf<Float>()
        val idxs  = mutableListOf<Short>()

        for (i in 0..stacks) {
            val phi = PI.toFloat() * i / stacks
            val sinP = sin(phi); val cosP = cos(phi)
            for (j in 0..slices) {
                val theta = 2f * PI.toFloat() * j / slices
                val x = sinP * cos(theta)
                val y = cosP
                val z = sinP * sin(theta)
                verts += x; verts += y; verts += z
                norms += x; norms += y; norms += z
            }
        }
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = (i * (slices + 1) + j).toShort()
                val b = (a + slices + 1).toShort()
                idxs += a; idxs += b; idxs += (a + 1).toShort()
                idxs += (a + 1).toShort(); idxs += b; idxs += (b + 1).toShort()
            }
        }
        sphereVB = makeFloatBuf(verts.toFloatArray())
        sphereNB = makeFloatBuf(norms.toFloatArray())
        sphereIB = makeShortBuf(idxs.toShortArray())
        sphereIdxCount = idxs.size
    }

    /** Cylinder along Y axis, radius 1, height 1 (−0.5 to 0.5) */
    private fun buildCylinder(slices: Int, capDetail: Int) {
        val verts = mutableListOf<Float>()
        val norms = mutableListOf<Float>()
        val idxs  = mutableListOf<Short>()

        // Side vertices  (two rings)
        for (ring in 0..1) {
            val y = ring.toFloat() - 0.5f
            for (j in 0..slices) {
                val theta = 2f * PI.toFloat() * j / slices
                val cx = cos(theta); val cz = sin(theta)
                verts += cx; verts += y; verts += cz
                norms += cx; norms += 0f; norms += cz
            }
        }
        // Side indices
        val s = slices + 1
        for (j in 0 until slices) {
            val a = j.toShort(); val b = (j + s).toShort()
            idxs += a; idxs += b; idxs += (a + 1).toShort()
            idxs += (a + 1).toShort(); idxs += b; idxs += (b + 1).toShort()
        }
        // Caps
        for (cap in 0..1) {
            val y = cap.toFloat() - 0.5f
            val ny = if (cap == 1) 1f else -1f
            val centerIdx = (verts.size / 3).toShort()
            verts += 0f; verts += y; verts += 0f
            norms += 0f; norms += ny; norms += 0f
            val ringStart = (verts.size / 3).toShort()
            for (j in 0..slices) {
                val theta = 2f * PI.toFloat() * j / slices
                verts += cos(theta); verts += y; verts += sin(theta)
                norms += 0f; norms += ny; norms += 0f
            }
            for (j in 0 until slices) {
                if (cap == 1) {
                    idxs += centerIdx; idxs += (ringStart + j).toShort(); idxs += (ringStart + j + 1).toShort()
                } else {
                    idxs += centerIdx; idxs += (ringStart + j + 1).toShort(); idxs += (ringStart + j).toShort()
                }
            }
        }

        cylVB = makeFloatBuf(verts.toFloatArray())
        cylNB = makeFloatBuf(norms.toFloatArray())
        cylIB = makeShortBuf(idxs.toShortArray())
        cylIdxCount = idxs.size
    }

    /** Unit box centred at origin (−0.5 … 0.5) with face normals */
    private fun buildRoundedBox() {
        val v = floatArrayOf(
            // Front (+Z)
            -0.5f, 0.5f, 0.5f,  -0.5f,-0.5f, 0.5f,   0.5f,-0.5f, 0.5f,   0.5f, 0.5f, 0.5f,
            // Back (−Z)
             0.5f, 0.5f,-0.5f,   0.5f,-0.5f,-0.5f,  -0.5f,-0.5f,-0.5f,  -0.5f, 0.5f,-0.5f,
            // Top (+Y)
            -0.5f, 0.5f,-0.5f,  -0.5f, 0.5f, 0.5f,   0.5f, 0.5f, 0.5f,   0.5f, 0.5f,-0.5f,
            // Bottom (−Y)
            -0.5f,-0.5f, 0.5f,  -0.5f,-0.5f,-0.5f,   0.5f,-0.5f,-0.5f,   0.5f,-0.5f, 0.5f,
            // Right (+X)
             0.5f, 0.5f, 0.5f,   0.5f,-0.5f, 0.5f,   0.5f,-0.5f,-0.5f,   0.5f, 0.5f,-0.5f,
            // Left (−X)
            -0.5f, 0.5f,-0.5f,  -0.5f,-0.5f,-0.5f,  -0.5f,-0.5f, 0.5f,  -0.5f, 0.5f, 0.5f,
        )
        val n = floatArrayOf(
            0f,0f,1f, 0f,0f,1f, 0f,0f,1f, 0f,0f,1f,
            0f,0f,-1f,0f,0f,-1f,0f,0f,-1f,0f,0f,-1f,
            0f,1f,0f, 0f,1f,0f, 0f,1f,0f, 0f,1f,0f,
            0f,-1f,0f,0f,-1f,0f,0f,-1f,0f,0f,-1f,0f,
            1f,0f,0f, 1f,0f,0f, 1f,0f,0f, 1f,0f,0f,
            -1f,0f,0f,-1f,0f,0f,-1f,0f,0f,-1f,0f,0f,
        )
        val idx = shortArrayOf(
            0,1,2, 2,3,0,
            4,5,6, 6,7,4,
            8,9,10, 10,11,8,
            12,13,14, 14,15,12,
            16,17,18, 18,19,16,
            20,21,22, 22,23,20,
        )
        boxVB = makeFloatBuf(v)
        boxNB = makeFloatBuf(n)
        boxIB = makeShortBuf(idx)
        boxIdxCount = idx.size
    }

    // ═══════════════════════════════════════════════════════════
    //  Utility
    // ═══════════════════════════════════════════════════════════

    private fun matCopy(src: FloatArray): FloatArray {
        val dst = FloatArray(16)
        System.arraycopy(src, 0, dst, 0, 16)
        return dst
    }

    private fun makeFloatBuf(a: FloatArray): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer(); fb.put(a); fb.position(0); return fb
    }

    private fun makeShortBuf(a: ShortArray): ShortBuffer {
        val bb = ByteBuffer.allocateDirect(a.size * 2).order(ByteOrder.nativeOrder())
        val sb = bb.asShortBuffer(); sb.put(a); sb.position(0); return sb
    }

    private fun loadShader(type: Int, code: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, code)
        GLES20.glCompileShader(s)
        return s
    }
}