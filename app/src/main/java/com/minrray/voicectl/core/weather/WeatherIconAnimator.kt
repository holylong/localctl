package com.minrray.voicectl.core.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.caverock.androidsvg.SVG
import java.io.InputStream

class WeatherIconAnimator(private val context: Context) {
    companion object {
        private const val TAG = "WeatherIconAnimator"
        private const val ANIMATION_DURATION = 1000L
        private const val FRAME_INTERVAL = 2000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var animationRunnable: Runnable? = null
    private var isAnimating = false
    private var currentFrame = 0
    private var iconCode: String = ""
    private var onFrameUpdateListener: ((Drawable) -> Unit)? = null

    fun setIconCode(code: String) {
        this.iconCode = code
        Log.d(TAG, "setIconCode: $code")
    }

    fun setOnFrameUpdateListener(listener: (Drawable) -> Unit) {
        this.onFrameUpdateListener = listener
        Log.d(TAG, "setOnFrameUpdateListener: listener set")
    }

    fun startAnimation() {
        Log.d(TAG, "startAnimation: iconCode=$iconCode")
        if (isAnimating) {
            stopAnimation()
        }
        isAnimating = true
        currentFrame = 0
        scheduleNextFrame()
    }

    fun stopAnimation() {
        isAnimating = false
        animationRunnable?.let { mainHandler.removeCallbacks(it) }
        animationRunnable = null
    }

    private fun scheduleNextFrame() {
        if (!isAnimating) return

        animationRunnable = Runnable {
            if (!isAnimating) return@Runnable

            val drawable = loadCurrentFrame()
            //Log.d(TAG, "scheduleNextFrame: loaded drawable=$drawable, frame=$currentFrame")
            drawable?.let { onFrameUpdateListener?.invoke(it) }

            currentFrame = if (currentFrame == 0) 1 else 0
            mainHandler.postDelayed(this::scheduleNextFrame, FRAME_INTERVAL)
        }
        mainHandler.postDelayed(animationRunnable!!, FRAME_INTERVAL/2)
    }

    private fun loadCurrentFrame(): Drawable? {
        return try {
            val svgFileName = if (currentFrame == 0) {
                "icon_$iconCode.svg"
            } else {
                "icon_${iconCode}_fill.svg"
            }
            
            //Log.d(TAG, "loadCurrentFrame: trying to load $svgFileName")

            val resourceId = context.resources.getIdentifier(
                svgFileName.removeSuffix(".svg"),
                "raw",
                context.packageName
            )

            if (resourceId == 0) {
                Log.e(TAG, "Resource not found: $svgFileName")
                return null
            }
            
           // Log.d(TAG, "loadCurrentFrame: resourceId=$resourceId")

            val inputStream: InputStream = context.resources.openRawResource(resourceId)
            val svg = SVG.getFromInputStream(inputStream)
            
            val width = 128
            val height = 128
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)
            
            svg.setDocumentWidth(width.toFloat())
            svg.setDocumentHeight(height.toFloat())
            
            svg.renderToCanvas(canvas)
            
            val drawable = BitmapDrawable(context.resources, bitmap)
            drawable.setTint(Color.WHITE)
            //Log.d(TAG, "loadCurrentFrame: successfully loaded drawable, size=${bitmap.width}x${bitmap.height}")
            drawable
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load SVG icon: $iconCode, frame: $currentFrame", e)
            null
        }
    }

    fun loadStaticIcon(code: String): Drawable? {
        return try {
            val resourceId = context.resources.getIdentifier(
                code,
                "raw",
                context.packageName
            )

            if (resourceId == 0) {
                Log.e(TAG, "Resource not found: $code")
                return null
            }

            val inputStream: InputStream = context.resources.openRawResource(resourceId)
            val svg = SVG.getFromInputStream(inputStream)
            
            val width = 128
            val height = 128
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.TRANSPARENT)
            svg.renderToCanvas(canvas)

            BitmapDrawable(context.resources, bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load static SVG icon: $code", e)
            null
        }
    }

    fun destroy() {
        stopAnimation()
        onFrameUpdateListener = null
    }
}