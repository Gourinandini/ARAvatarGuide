package com.example.aravatarguide

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.PI

class AvatarRenderer {

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpMatrixHandle = 0

    private var vertexBuffer: FloatBuffer
    private var startTime = 0L
    
    // State to control mouth animation
    var isSpeaking = false

    // Cube vertices (36 vertices for 6 faces x 2 triangles)
    // Centered at 0,0,0, size 1x1x1 (-0.5 to 0.5)
    private val cubeVertices = floatArrayOf(
        // Front face
        -0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f,
        0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f,
        // Back face
        0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f,
        -0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f,
        // Top face
        -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f,
        0.5f, 0.5f, 0.5f, 0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f,
        // Bottom face
        -0.5f, -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f,
        0.5f, -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f,
        // Right face
        0.5f, 0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, -0.5f,
        0.5f, -0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, 0.5f, 0.5f,
        // Left face
        -0.5f, 0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f,
        -0.5f, -0.5f, 0.5f, -0.5f, 0.5f, 0.5f, -0.5f, 0.5f, -0.5f
    )

    init {
        val bb = ByteBuffer.allocateDirect(cubeVertices.size * 4)
        bb.order(ByteOrder.nativeOrder())
        vertexBuffer = bb.asFloatBuffer()
        vertexBuffer.put(cubeVertices)
        vertexBuffer.position(0)
    }

    fun createOnGlThread() {
        val vertexShader = """
            uniform mat4 u_MvpMatrix;
            attribute vec4 a_Position;
            void main() {
               gl_Position = u_MvpMatrix * a_Position;
            }
        """.trimIndent()

        val fragmentShader = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() {
               gl_FragColor = u_Color;
            }
        """.trimIndent()

        val vertexShaderHandle = loadShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val fragmentShaderHandle = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShaderHandle)
        GLES20.glAttachShader(program, fragmentShaderHandle)
        GLES20.glLinkProgram(program)

        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        colorHandle = GLES20.glGetUniformLocation(program, "u_Color")
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix")

        startTime = System.currentTimeMillis()
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, x: Float, y: Float, z: Float) {
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // Animation Time
        val time = (System.currentTimeMillis() - startTime) / 1000.0f

        // Base transformation for the whole avatar
        val baseMatrix = FloatArray(16)
        Matrix.setIdentityM(baseMatrix, 0)
        Matrix.translateM(baseMatrix, 0, x, y, z)
        
        // Breathing animation (slight up/down)
        val breathOffset = sin(time * 2.0f) * 0.01f
        Matrix.translateM(baseMatrix, 0, 0f, breathOffset, 0f)
        
        // Scale to human size
        Matrix.scaleM(baseMatrix, 0, 1.0f, 1.0f, 1.0f)

        // Colors
        val skinColor = floatArrayOf(1.0f, 0.85f, 0.7f, 1.0f)
        val shirtColor = floatArrayOf(0.1f, 0.5f, 0.9f, 1.0f) // Blue shirt
        val pantsColor = floatArrayOf(0.2f, 0.2f, 0.25f, 1.0f) // Dark pants
        val shoeColor = floatArrayOf(0.1f, 0.1f, 0.1f, 1.0f) // Black shoes
        val hairColor = floatArrayOf(0.15f, 0.1f, 0.05f, 1.0f) // Dark Brown hair
        val eyeColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) // White
        val pupilColor = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f) // Black
        val mouthColor = floatArrayOf(0.8f, 0.3f, 0.3f, 1.0f) // Reddish

        // --- Dimensions ---
        val torsoWidth = 0.35f
        val torsoHeight = 0.55f
        val torsoDepth = 0.2f
        val torsoY = 1.2f // Center of torso

        val headSize = 0.22f
        val headY = torsoY + torsoHeight/2 + 0.05f + headSize/2 // Neck gap included

        val legWidth = 0.14f
        val legHeight = 0.85f
        val legY = legHeight / 2
        val legSpacing = 0.09f

        val armWidth = 0.12f
        val armLength = 0.7f
        val upperArmLength = 0.35f
        val lowerArmLength = 0.35f
        
        // --- Legs ---
        // Left Leg
        drawPart(viewMatrix, projectionMatrix, baseMatrix, -legSpacing, legY, 0.0f, legWidth, legHeight, legWidth, pantsColor)
        // Right Leg
        drawPart(viewMatrix, projectionMatrix, baseMatrix, legSpacing, legY, 0.0f, legWidth, legHeight, legWidth, pantsColor)
        
        // Shoes
        drawPart(viewMatrix, projectionMatrix, baseMatrix, -legSpacing, 0.05f, 0.05f, legWidth + 0.02f, 0.1f, legWidth + 0.08f, shoeColor)
        drawPart(viewMatrix, projectionMatrix, baseMatrix, legSpacing, 0.05f, 0.05f, legWidth + 0.02f, 0.1f, legWidth + 0.08f, shoeColor)

        // --- Torso ---
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.0f, torsoY, 0.0f, torsoWidth, torsoHeight, torsoDepth, shirtColor)

        // --- Neck ---
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.0f, torsoY + torsoHeight/2 + 0.025f, 0.0f, 0.1f, 0.05f, 0.1f, skinColor)

        // --- Head ---
        // Head rotation (idle look around)
        val headYaw = sin(time * 0.8f) * 5.0f
        drawRotatedPart(viewMatrix, projectionMatrix, baseMatrix, 
            0.0f, headY - headSize/2, 0.0f, // Pivot at base of head
            0f, headYaw, 0f,
            0.0f, headSize/2, 0.0f, // Center of head relative to pivot
            headSize, headSize, headSize, skinColor)

        // Helper to draw head attached parts
        fun drawHeadPart(xOff: Float, yOff: Float, zOff: Float, sx: Float, sy: Float, sz: Float, color: FloatArray) {
             drawRotatedPart(viewMatrix, projectionMatrix, baseMatrix, 
                0.0f, headY - headSize/2, 0.0f,
                0f, headYaw, 0f,
                xOff, headSize/2 + yOff, zOff,
                sx, sy, sz, color)
        }

        // Hair Top
        drawHeadPart(0.0f, headSize/2 + 0.02f, 0.0f, headSize + 0.02f, 0.08f, headSize + 0.02f, hairColor)
        // Hair Back
        drawHeadPart(0.0f, 0.0f, -headSize/2 + 0.02f, headSize + 0.02f, headSize, 0.05f, hairColor)
        // Hair Sides
        drawHeadPart(-headSize/2 - 0.01f, 0.0f, 0.0f, 0.04f, headSize, headSize, hairColor)
        drawHeadPart(headSize/2 + 0.01f, 0.0f, 0.0f, 0.04f, headSize, headSize, hairColor)

        // --- Face Features ---
        // Blinking Logic
        val isBlinking = (time % 4.0f) > 3.8f
        val eyeH = if (isBlinking) 0.005f else 0.035f
        
        // Eyes
        drawHeadPart(-0.06f, 0.02f, headSize/2 + 0.005f, 0.05f, eyeH, 0.01f, eyeColor)
        drawHeadPart(0.06f, 0.02f, headSize/2 + 0.005f, 0.05f, eyeH, 0.01f, eyeColor)
        
        if (!isBlinking) {
            // Pupils
            drawHeadPart(-0.06f, 0.02f, headSize/2 + 0.006f, 0.02f, 0.02f, 0.01f, pupilColor)
            drawHeadPart(0.06f, 0.02f, headSize/2 + 0.006f, 0.02f, 0.02f, 0.01f, pupilColor)
        }

        // Nose
        drawHeadPart(0.0f, -0.02f, headSize/2 + 0.01f, 0.03f, 0.04f, 0.03f, skinColor)

        // Mouth (Smile / Speaking)
        // If speaking, modulate mouth height
        val mouthOpen = if (isSpeaking) (sin(time * 20.0f) * 0.5f + 0.5f) * 0.03f else 0.0f
        val mouthBaseH = 0.015f
        val mouthH = mouthBaseH + mouthOpen
        
        // Center
        drawHeadPart(0.0f, -0.07f - mouthOpen/2, headSize/2 + 0.005f, 0.08f, mouthH, 0.01f, mouthColor)
        // Left corner up (only if not speaking too much, otherwise it looks weird)
        if (!isSpeaking) {
            drawHeadPart(-0.04f, -0.06f, headSize/2 + 0.005f, 0.02f, 0.015f, 0.01f, mouthColor)
            drawHeadPart(0.04f, -0.06f, headSize/2 + 0.005f, 0.02f, 0.015f, 0.01f, mouthColor)
        }


        // --- Arms ---
        val shoulderY = torsoY + torsoHeight/2 - 0.05f
        val shoulderX = torsoWidth/2 + armWidth/2

        // Right Arm Animation (Waving)
        // Wave for first 5 seconds
        val isWaving = time < 5.0f
        
        // Upper Arm Angle
        val rightUpperArmAngleZ = if (isWaving) {
            // Raise arm to side/up
            130f 
        } else {
            // Idle: Slight sway
            sin(time * 2.0f) * 3.0f
        }
        
        // Lower Arm Angle (Elbow bend)
        // Relative to upper arm
        val rightLowerArmAngleZ = if (isWaving) {
            // Wave: Oscillate forearm
            // 0 is straight, positive bends inward/upward depending on axis
            // We want a "hello" wave, so forearm moves back and forth
            45f + sin(time * 12.0f) * 30f
        } else {
            // Idle: Slight bend
            10f
        }
        
        // Left Arm Animation (Idle)
        val leftUpperArmAngleZ = -sin(time * 2.0f) * 3.0f
        val leftLowerArmAngleZ = 10f // Slight natural bend

        // --- Right Arm Construction ---
        // 1. Upper Arm
        // Pivot at shoulder
        val rightArmMatrix = FloatArray(16)
        System.arraycopy(baseMatrix, 0, rightArmMatrix, 0, 16)
        
        // Translate to Shoulder
        Matrix.translateM(rightArmMatrix, 0, shoulderX, shoulderY, 0.0f)
        // Rotate Upper Arm
        Matrix.rotateM(rightArmMatrix, 0, rightUpperArmAngleZ, 0f, 0f, 1f)
        
        // Draw Upper Arm (offset so pivot is at top)
        drawMeshWithMatrix(viewMatrix, projectionMatrix, rightArmMatrix, 
            0.0f, -upperArmLength/2, 0.0f, 
            armWidth, upperArmLength, armWidth, shirtColor)

        // 2. Lower Arm (Forearm)
        // Pivot at Elbow (end of upper arm)
        Matrix.translateM(rightArmMatrix, 0, 0.0f, -upperArmLength, 0.0f)
        // Rotate Lower Arm
        Matrix.rotateM(rightArmMatrix, 0, rightLowerArmAngleZ, 0f, 0f, 1f)
        
        // Draw Lower Arm
        drawMeshWithMatrix(viewMatrix, projectionMatrix, rightArmMatrix,
            0.0f, -lowerArmLength/2, 0.0f,
            armWidth, lowerArmLength, armWidth, skinColor) // Skin color for forearm/hand usually, or shirt if long sleeves. Let's assume short sleeves or skin.

        // 3. Hand
        // End of lower arm
        Matrix.translateM(rightArmMatrix, 0, 0.0f, -lowerArmLength, 0.0f)
        drawMeshWithMatrix(viewMatrix, projectionMatrix, rightArmMatrix,
            0.0f, -0.06f, 0.0f,
            0.1f, 0.12f, 0.1f, skinColor)


        // --- Left Arm Construction ---
        val leftArmMatrix = FloatArray(16)
        System.arraycopy(baseMatrix, 0, leftArmMatrix, 0, 16)
        
        // Translate to Shoulder
        Matrix.translateM(leftArmMatrix, 0, -shoulderX, shoulderY, 0.0f)
        // Rotate Upper Arm
        Matrix.rotateM(leftArmMatrix, 0, leftUpperArmAngleZ, 0f, 0f, 1f)
        
        // Draw Upper Arm
        drawMeshWithMatrix(viewMatrix, projectionMatrix, leftArmMatrix,
            0.0f, -upperArmLength/2, 0.0f,
            armWidth, upperArmLength, armWidth, shirtColor)
            
        // Elbow
        Matrix.translateM(leftArmMatrix, 0, 0.0f, -upperArmLength, 0.0f)
        Matrix.rotateM(leftArmMatrix, 0, -leftLowerArmAngleZ, 0f, 0f, 1f) // Negative for symmetry
        
        // Draw Lower Arm
        drawMeshWithMatrix(viewMatrix, projectionMatrix, leftArmMatrix,
            0.0f, -lowerArmLength/2, 0.0f,
            armWidth, lowerArmLength, armWidth, skinColor)
            
        // Hand
        Matrix.translateM(leftArmMatrix, 0, 0.0f, -lowerArmLength, 0.0f)
        drawMeshWithMatrix(viewMatrix, projectionMatrix, leftArmMatrix,
            0.0f, -0.06f, 0.0f,
            0.1f, 0.12f, 0.1f, skinColor)

        GLES20.glDisableVertexAttribArray(positionHandle)
    }

    private fun drawPart(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        baseMatrix: FloatArray,
        xOffset: Float, yOffset: Float, zOffset: Float,
        scaleX: Float, scaleY: Float, scaleZ: Float,
        color: FloatArray
    ) {
        val modelMatrix = FloatArray(16)
        System.arraycopy(baseMatrix, 0, modelMatrix, 0, 16)
        Matrix.translateM(modelMatrix, 0, xOffset, yOffset, zOffset)
        Matrix.scaleM(modelMatrix, 0, scaleX, scaleY, scaleZ)
        drawMesh(viewMatrix, projectionMatrix, modelMatrix, color)
    }

    private fun drawRotatedPart(
        viewMatrix: FloatArray,
        projectionMatrix: FloatArray,
        baseMatrix: FloatArray,
        pivotX: Float, pivotY: Float, pivotZ: Float,
        pitch: Float, yaw: Float, roll: Float,
        offsetX: Float, offsetY: Float, offsetZ: Float,
        scaleX: Float, scaleY: Float, scaleZ: Float,
        color: FloatArray
    ) {
        val modelMatrix = FloatArray(16)
        System.arraycopy(baseMatrix, 0, modelMatrix, 0, 16)

        // 1. Translate to Pivot
        Matrix.translateM(modelMatrix, 0, pivotX, pivotY, pivotZ)
        
        // 2. Rotate
        if (yaw != 0f) Matrix.rotateM(modelMatrix, 0, yaw, 0f, 1f, 0f)
        if (pitch != 0f) Matrix.rotateM(modelMatrix, 0, pitch, 1f, 0f, 0f)
        if (roll != 0f) Matrix.rotateM(modelMatrix, 0, roll, 0f, 0f, 1f)

        // 3. Translate to Center of Part (relative to pivot)
        Matrix.translateM(modelMatrix, 0, offsetX, offsetY, offsetZ)

        // 4. Scale
        Matrix.scaleM(modelMatrix, 0, scaleX, scaleY, scaleZ)

        drawMesh(viewMatrix, projectionMatrix, modelMatrix, color)
    }
    
    // Helper to draw mesh with an already fully calculated model matrix (including scale)
    private fun drawMeshWithMatrix(
        viewMatrix: FloatArray, 
        projectionMatrix: FloatArray, 
        currentModelMatrix: FloatArray,
        offsetX: Float, offsetY: Float, offsetZ: Float,
        scaleX: Float, scaleY: Float, scaleZ: Float,
        color: FloatArray
    ) {
        val finalModelMatrix = FloatArray(16)
        System.arraycopy(currentModelMatrix, 0, finalModelMatrix, 0, 16)
        
        Matrix.translateM(finalModelMatrix, 0, offsetX, offsetY, offsetZ)
        Matrix.scaleM(finalModelMatrix, 0, scaleX, scaleY, scaleZ)
        
        drawMesh(viewMatrix, projectionMatrix, finalModelMatrix, color)
    }

    private fun drawMesh(viewMatrix: FloatArray, projectionMatrix: FloatArray, modelMatrix: FloatArray, color: FloatArray) {
        val mvpMatrix = FloatArray(16)
        val tempMatrix = FloatArray(16)
        Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }
}