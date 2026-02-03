package com.example.aravatarguide

import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class AvatarRenderer {

    private var program = 0
    private var positionHandle = 0
    private var colorHandle = 0
    private var mvpMatrixHandle = 0

    private var vertexBuffer: FloatBuffer

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
    }

    fun draw(viewMatrix: FloatArray, projectionMatrix: FloatArray, x: Float, y: Float, z: Float) {
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        // Base transformation for the whole avatar
        val baseMatrix = FloatArray(16)
        Matrix.setIdentityM(baseMatrix, 0)
        Matrix.translateM(baseMatrix, 0, x, y, z)
        // Scale the whole avatar to be human size (approx 1.8m tall)
        Matrix.scaleM(baseMatrix, 0, 1.0f, 1.0f, 1.0f)

        // Colors
        val skinColor = floatArrayOf(1.0f, 0.8f, 0.6f, 1.0f)
        val shirtColor = floatArrayOf(0.0f, 0.4f, 0.8f, 1.0f) // Blue shirt
        val pantsColor = floatArrayOf(0.2f, 0.2f, 0.2f, 1.0f) // Dark pants
        val hairColor = floatArrayOf(0.1f, 0.1f, 0.1f, 1.0f) // Black hair
        val eyeColor = floatArrayOf(1.0f, 1.0f, 1.0f, 1.0f) // White
        val pupilColor = floatArrayOf(0.0f, 0.0f, 0.0f, 1.0f) // Black
        val mouthColor = floatArrayOf(0.8f, 0.2f, 0.2f, 1.0f) // Red

        // --- Legs ---
        // Left Leg
        drawPart(viewMatrix, projectionMatrix, baseMatrix, -0.2f, 0.4f, 0.0f, 0.15f, 0.8f, 0.15f, pantsColor)
        // Right Leg
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.2f, 0.4f, 0.0f, 0.15f, 0.8f, 0.15f, pantsColor)

        // --- Torso ---
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.0f, 1.1f, 0.0f, 0.6f, 0.7f, 0.3f, shirtColor)

        // --- Arms ---
        // Left Arm
        drawPart(viewMatrix, projectionMatrix, baseMatrix, -0.45f, 1.1f, 0.0f, 0.15f, 0.7f, 0.15f, skinColor) 
        // Right Arm
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.45f, 1.1f, 0.0f, 0.15f, 0.7f, 0.15f, skinColor)

        // --- Hands ---
        drawPart(viewMatrix, projectionMatrix, baseMatrix, -0.45f, 0.7f, 0.0f, 0.12f, 0.12f, 0.12f, skinColor)
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.45f, 0.7f, 0.0f, 0.12f, 0.12f, 0.12f, skinColor)

        // --- Head ---
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.0f, 1.65f, 0.0f, 0.3f, 0.35f, 0.3f, skinColor)

        // --- Hair ---
        // Top hair
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.0f, 1.85f, 0.0f, 0.32f, 0.1f, 0.32f, hairColor)
        // Back hair
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.0f, 1.7f, -0.16f, 0.32f, 0.3f, 0.05f, hairColor)
        // Side hair left
        drawPart(viewMatrix, projectionMatrix, baseMatrix, -0.16f, 1.7f, 0.0f, 0.05f, 0.3f, 0.32f, hairColor)
        // Side hair right
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.16f, 1.7f, 0.0f, 0.05f, 0.3f, 0.32f, hairColor)

        // --- Face Features ---
        // Eyes (White part)
        drawPart(viewMatrix, projectionMatrix, baseMatrix, -0.08f, 1.7f, 0.155f, 0.06f, 0.04f, 0.01f, eyeColor)
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.08f, 1.7f, 0.155f, 0.06f, 0.04f, 0.01f, eyeColor)
        // Pupils
        drawPart(viewMatrix, projectionMatrix, baseMatrix, -0.08f, 1.7f, 0.16f, 0.02f, 0.02f, 0.01f, pupilColor)
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.08f, 1.7f, 0.16f, 0.02f, 0.02f, 0.01f, pupilColor)

        // Nose
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.0f, 1.65f, 0.16f, 0.04f, 0.06f, 0.04f, skinColor)

        // Mouth (Smiling) - approximated by 3 small blocks
        // Center
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.0f, 1.55f, 0.155f, 0.1f, 0.02f, 0.01f, mouthColor)
        // Left corner up
        drawPart(viewMatrix, projectionMatrix, baseMatrix, -0.06f, 1.57f, 0.155f, 0.03f, 0.02f, 0.01f, mouthColor)
        // Right corner up
        drawPart(viewMatrix, projectionMatrix, baseMatrix, 0.06f, 1.57f, 0.155f, 0.03f, 0.02f, 0.01f, mouthColor)

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
        // Start with the base matrix (avatar position)
        System.arraycopy(baseMatrix, 0, modelMatrix, 0, 16)

        // Apply local transformation for the part
        Matrix.translateM(modelMatrix, 0, xOffset, yOffset, zOffset)
        Matrix.scaleM(modelMatrix, 0, scaleX, scaleY, scaleZ)

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