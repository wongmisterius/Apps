package com.photo3d.spatial.gl

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import com.photo3d.spatial.data.DepthGrid
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

private const val VERTEX_SHADER = """
    uniform mat4 uMVPMatrix;
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = uMVPMatrix * aPosition;
        vTexCoord = aTexCoord;
    }
"""

private const val FRAGMENT_SHADER = """
    precision mediump float;
    varying vec2 vTexCoord;
    uniform sampler2D uTexture;
    void main() {
        gl_FragColor = texture2D(uTexture, vTexCoord);
    }
"""

/**
 * Renderiza la foto como una malla desplazada en Z según el mapa de profundidad
 * y mueve una cámara virtual lateralmente según la inclinación del teléfono,
 * produciendo el efecto de paralaje "3D" (parecido a las fotos espaciales de iPhone).
 */
class ParallaxRenderer : GLSurfaceView.Renderer {

    private var program = 0
    private var textureId = 0
    private var pendingBitmap: Bitmap? = null
    private var pendingDepth: DepthGrid? = null
    private var bitmapUploaded = false

    private var vertexBuffer: FloatBuffer? = null
    private var texCoordBuffer: FloatBuffer? = null
    private var indexBuffer: ShortBuffer? = null
    private var indexCount = 0

    private val mvpMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)

    private var viewportWidth = 1
    private var viewportHeight = 1

    @Volatile private var tiltX = 0f
    @Volatile private var tiltY = 0f

    companion object {
        private const val DEPTH_SCALE = 0.22f
        private const val EYE_DIST = 1.5f
        private const val PARALLAX_AMPLITUDE = 0.35f
    }

    fun setPhoto(bitmap: Bitmap, depth: DepthGrid) {
        pendingBitmap = bitmap
        pendingDepth = depth
        bitmapUploaded = false
    }

    fun setTilt(x: Float, y: Float) {
        tiltX = x
        tiltY = y
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertexShader)
            GLES20.glAttachShader(it, fragmentShader)
            GLES20.glLinkProgram(it)
        }

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projMatrix, 0, 45f, aspect, 0.1f, 10f)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingBitmap?.let { bmp ->
            val depth = pendingDepth ?: DepthGrid.flat(2, 2)
            uploadTexture(bmp)
            buildMesh(depth, bmp.width.toFloat() / bmp.height.toFloat())
            pendingBitmap = null
            bitmapUploaded = true
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (!bitmapUploaded) return

        val eyeX = tiltX * PARALLAX_AMPLITUDE
        val eyeY = -tiltY * PARALLAX_AMPLITUDE
        Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX, eyeY, EYE_DIST,
            eyeX, eyeY, 0f,
            0f, 1f, 0f
        )
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        GLES20.glUseProgram(program)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        val mvpHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)

        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, indexBuffer)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun uploadTexture(bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun buildMesh(depth: DepthGrid, aspect: Float) {
        val gw = depth.width
        val gh = depth.height
        val halfW = if (aspect >= 1f) aspect else 1f
        val halfH = if (aspect >= 1f) 1f else 1f / aspect

        val positions = FloatArray(gw * gh * 3)
        val texCoords = FloatArray(gw * gh * 2)

        for (y in 0 until gh) {
            for (x in 0 until gw) {
                val i = y * gw + x
                val u = x / (gw - 1).toFloat()
                val v = y / (gh - 1).toFloat()
                val px = (u - 0.5f) * 2f * halfW
                // v=0 es la fila superior de la imagen; en el mundo Y crece hacia arriba.
                val py = (0.5f - v) * 2f * halfH
                val pz = depth[x, y] * DEPTH_SCALE

                positions[i * 3] = px
                positions[i * 3 + 1] = py
                positions[i * 3 + 2] = pz

                texCoords[i * 2] = u
                texCoords[i * 2 + 1] = v
            }
        }

        val indices = ShortArray((gw - 1) * (gh - 1) * 6)
        var idx = 0
        for (y in 0 until gh - 1) {
            for (x in 0 until gw - 1) {
                val topLeft = (y * gw + x).toShort()
                val topRight = (y * gw + x + 1).toShort()
                val bottomLeft = ((y + 1) * gw + x).toShort()
                val bottomRight = ((y + 1) * gw + x + 1).toShort()

                indices[idx++] = topLeft
                indices[idx++] = bottomLeft
                indices[idx++] = topRight

                indices[idx++] = topRight
                indices[idx++] = bottomLeft
                indices[idx++] = bottomRight
            }
        }

        vertexBuffer = ByteBuffer.allocateDirect(positions.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(positions); position(0) }
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texCoords); position(0) }
        indexBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(indices); position(0) }
        indexCount = indices.size
    }

    private fun compileShader(type: Int, source: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
        }
    }
}
