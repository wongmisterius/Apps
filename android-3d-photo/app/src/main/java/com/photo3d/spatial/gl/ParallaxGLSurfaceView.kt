package com.photo3d.spatial.gl

import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import com.photo3d.spatial.data.DepthGrid

class ParallaxGLSurfaceView(context: Context) : GLSurfaceView(context) {

    private val renderer = ParallaxRenderer()

    init {
        setEGLContextClientVersion(2)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun showPhoto(bitmap: Bitmap, depth: DepthGrid) {
        renderer.setPhoto(bitmap, depth)
        requestRender()
    }

    fun updateTilt(x: Float, y: Float) {
        renderer.setTilt(x, y)
        requestRender()
    }
}
