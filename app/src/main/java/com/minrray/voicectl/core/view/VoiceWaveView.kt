// VoiceWaveView.kt
package com.minrray.voicectl.core.view

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

class VoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 绘制对象
    private val wavePath = Path()
    private val secondaryPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 5f), 0f)
        isAntiAlias = true
    }

    // 波浪参数
    private var amplitude: Float = 0f
    private var targetAmplitude: Float = 0f
    private var phase: Float = 0f
    private var waveCount = 3
    private val wavePoints = 100

    // 能量峰值追踪
    private var peakAmplitude: Float = 0f
    private var peakDecayTimer: Long = 0

    // 颜色和渐变
    private var gradient: LinearGradient? = null
    private var secondaryGradient: LinearGradient? = null

    // 颜色主题
    enum class WaveTheme {
        BLUE_OCEAN,    // 蓝色海洋
        GREEN_FOREST,  // 绿色森林
        PURPLE_NIGHT,  // 紫色夜晚
        GOLDEN_SUNSET, // 金色日落
        ICE_COLD       // 冰蓝冷色
    }

    private var currentTheme: WaveTheme = WaveTheme.BLUE_OCEAN

    // 模式标志
    private var isSpeakingMode = false
    private var isAssistantMode = false
    private var isSilentMode = false

    // 动画参数
    private var pulsePhase: Float = 0f
    private var pulseAmplitude: Float = 0f

    // ============ 新增：线程安全控制 ============
    @Volatile
    private var isAnimating = false

    @Volatile
    private var isViewAttached = false

    private val animationHandler = Handler(Looper.getMainLooper())
    private var animationRunnable: Runnable? = null

    // 动画帧率控制
    private val TARGET_FPS = 60
    private val FRAME_DELAY_MS = 1000L / TARGET_FPS

    // 振幅变化平滑系数
    private val AMPLITUDE_SMOOTH_FACTOR = 0.3f
    private val MIN_AMPLITUDE_THRESHOLD = 0.001f

    // 绘制状态锁
    private val drawLock = Any()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null) // 开启硬件加速
        setupTheme(currentTheme)

        // 初始化动画runnable
        animationRunnable = object : Runnable {
            override fun run() {
                if (isAnimating && isViewAttached) {
                    invalidate()
                    scheduleNextFrame()
                }
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isViewAttached = true

        // 如果之前是动画状态，重新启动动画
        if (isAnimating) {
            startInternalAnimation()
        }

        Log.d("VoiceWaveView", "视图附加到窗口, isAnimating=$isAnimating")
    }

    override fun onDetachedFromWindow() {
        isViewAttached = false
        stopInternalAnimation()

        // 清除所有状态
        resetWaveState()

        super.onDetachedFromWindow()
        Log.d("VoiceWaveView", "视图从窗口分离")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGradients()
    }

    /**
     * 设置颜色主题
     */
    private fun setupTheme(theme: WaveTheme) {
        currentTheme = theme

        val colors = when (theme) {
            WaveTheme.BLUE_OCEAN -> intArrayOf(
                Color.parseColor("#1E3A8A"), // 深蓝
                Color.parseColor("#3B82F6"), // 蓝色
                Color.parseColor("#60A5FA")  // 浅蓝
            )
            WaveTheme.GREEN_FOREST -> intArrayOf(
                Color.parseColor("#064E3B"), // 深绿
                Color.parseColor("#10B981"), // 绿色
                Color.parseColor("#34D399")  // 浅绿
            )
            WaveTheme.PURPLE_NIGHT -> intArrayOf(
                Color.parseColor("#4C1D95"), // 深紫
                Color.parseColor("#8B5CF6"), // 紫色
                Color.parseColor("#A78BFA")  // 浅紫
            )
            WaveTheme.GOLDEN_SUNSET -> intArrayOf(
                Color.parseColor("#92400E"), // 深橙
                Color.parseColor("#F59E0B"), // 橙色
                Color.parseColor("#FBBF24")  // 浅黄
            )
            WaveTheme.ICE_COLD -> intArrayOf(
                Color.parseColor("#0E7490"), // 深青
                Color.parseColor("#06B6D4"), // 青色
                Color.parseColor("#67E8F9")  // 浅青
            )
        }

        val secondaryColors = when (theme) {
            WaveTheme.BLUE_OCEAN -> intArrayOf(
                Color.parseColor("#1E40AF"),
                Color.parseColor("#60A5FA")
            )
            WaveTheme.GREEN_FOREST -> intArrayOf(
                Color.parseColor("#065F46"),
                Color.parseColor("#34D399")
            )
            WaveTheme.PURPLE_NIGHT -> intArrayOf(
                Color.parseColor("#5B21B6"),
                Color.parseColor("#A78BFA")
            )
            WaveTheme.GOLDEN_SUNSET -> intArrayOf(
                Color.parseColor("#B45309"),
                Color.parseColor("#FBBF24")
            )
            WaveTheme.ICE_COLD -> intArrayOf(
                Color.parseColor("#0891B2"),
                Color.parseColor("#67E8F9")
            )
        }

        gradient = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            colors,
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )

        secondaryGradient = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            secondaryColors,
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        paint.shader = gradient
        secondaryPaint.shader = secondaryGradient

        // 设置峰值线颜色
        val peakColor = when (theme) {
            WaveTheme.BLUE_OCEAN -> Color.YELLOW
            WaveTheme.GREEN_FOREST -> Color.parseColor("#FEF3C7")
            WaveTheme.PURPLE_NIGHT -> Color.parseColor("#FDE68A")
            WaveTheme.GOLDEN_SUNSET -> Color.parseColor("#FEE2E2")
            WaveTheme.ICE_COLD -> Color.WHITE
        }
        peakPaint.color = peakColor
    }

    /**
     * 更新渐变
     */
    private fun updateGradients() {
        if (width == 0 || height == 0) return

        setupTheme(currentTheme) // 重新创建渐变
    }

    /**
     * 设置波浪样式
     */
    fun setWaveStyle(
        amplitudeMultiplier: Float = 1.0f,
        waveCount: Int = 3,
        flowSpeed: Float = 0.15f
    ) {
        this.waveCount = waveCount
      //  Log.d("VoiceWaveView", "设置波浪样式: 波浪数=$waveCount")
        invalidate()
    }

    /**
     * 设置说话模式
     */
    fun setSpeakingMode(isSpeaking: Boolean) {
        isSpeakingMode = isSpeaking
        if (isSpeaking) {
            waveCount = 4
            // 切换到更活跃的颜色主题
            if (currentTheme != WaveTheme.GOLDEN_SUNSET) {
                setupTheme(WaveTheme.GOLDEN_SUNSET)
            }
        } else {
            waveCount = 3
            // 恢复默认主题
            if (currentTheme != WaveTheme.BLUE_OCEAN && !isAssistantMode) {
                setupTheme(WaveTheme.BLUE_OCEAN)
            }
        }
        invalidate()
    }

    /**
     * 设置助手模式
     */
    fun setAssistantSpeakingMode(isAssistantSpeaking: Boolean) {
        isAssistantMode = isAssistantSpeaking
        if (isAssistantSpeaking) {
            waveCount = 3
            // 使用紫色主题
            if (currentTheme != WaveTheme.PURPLE_NIGHT) {
                setupTheme(WaveTheme.PURPLE_NIGHT)
            }
        } else {
            waveCount = 3
            // 恢复默认主题（除非用户正在说话）
            if (!isSpeakingMode && currentTheme != WaveTheme.BLUE_OCEAN) {
                setupTheme(WaveTheme.BLUE_OCEAN)
            }
        }
        invalidate()
    }

    /**
     * 设置静音模式
     */
    fun setSilentMode(isSilent: Boolean) {
        isSilentMode = isSilent
        if (isSilent) {
            waveCount = 2
            // 使用冷色调
            setupTheme(WaveTheme.ICE_COLD)
        } else {
            waveCount = 3
            // 恢复默认主题
            setupTheme(WaveTheme.BLUE_OCEAN)
        }
        invalidate()
    }

    /**
     * 更新能量值
     */
    fun updateEnergy(energy: Float) {
        if (!isViewAttached) return

        val newTarget = energy.coerceIn(0f, 1f) * height * 0.4f

        // 更新峰值
        if (newTarget > peakAmplitude) {
            peakAmplitude = newTarget
            peakDecayTimer = System.currentTimeMillis()
        }

        targetAmplitude = newTarget

        // 根据能量值调整模式
        when {
            energy < 0.05f -> setSilentMode(true)
            energy > 0.3f -> setSpeakingMode(true)
            else -> {
                setSilentMode(false)
                setSpeakingMode(false)
            }
        }

        invalidate()
    }

    /**
     * 启动波浪动画
     */
    fun startWaveAnimation() {
        if (isAnimating) return

        isAnimating = true

        // 重置波浪状态
        resetWaveState()

        // 启动一个初始脉冲动画
        pulseAmplitude = 1f

        // 启动动画循环
        if (isViewAttached) {
            startInternalAnimation()
        }

        Log.d("VoiceWaveView", "波浪动画启动, isViewAttached=$isViewAttached")
    }

    /**
     * 停止波浪动画
     */
    fun stopWaveAnimation() {
        if (!isAnimating) return

        isAnimating = false
        stopInternalAnimation()

        // 平滑归零
        targetAmplitude = 0f

        // 最后强制归零并重绘
        animationHandler.post {
            amplitude = 0f
            targetAmplitude = 0f
            invalidate()
        }

        Log.d("VoiceWaveView", "波浪动画停止")
    }

    /**
     * 内部启动动画
     */
    private fun startInternalAnimation() {
        if (!isAnimating || !isViewAttached) return

        animationHandler.removeCallbacksAndMessages(null)

        // 立即开始第一帧
        invalidate()

        // 使用固定帧率调度
        scheduleNextFrame()
    }

    /**
     * 内部停止动画
     */
    private fun stopInternalAnimation() {
        animationHandler.removeCallbacksAndMessages(null)
        animationRunnable?.let {
            animationHandler.removeCallbacks(it)
        }
    }

    /**
     * 调度下一帧
     */
    private fun scheduleNextFrame() {
        if (!isAnimating || !isViewAttached) return

        animationRunnable?.let {
            animationHandler.postDelayed(it, FRAME_DELAY_MS)
        }
    }

    /**
     * 重置波浪状态
     */
    private fun resetWaveState() {
        amplitude = 0f
        targetAmplitude = 0f
        phase = 0f
        peakAmplitude = 0f
        pulseAmplitude = 0f
        pulsePhase = 0f
        waveCount = 3
    }

    /**
     * 重置振幅状态（用于连接断开/恢复时）
     */
    fun resetAmplitude() {
        targetAmplitude = 0f
        amplitude = 0f
        peakAmplitude = 0f
        pulseAmplitude = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isViewAttached || width == 0 || height == 0) return

        synchronized(drawLock) {
            try {
                // 清空画布
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                // 如果没有动画或振幅极小，直接返回
                if (!isAnimating && amplitude < MIN_AMPLITUDE_THRESHOLD &&
                    targetAmplitude < MIN_AMPLITUDE_THRESHOLD) {
                    return
                }

                // 1. 平滑振幅过渡
                amplitude += (targetAmplitude - amplitude) * AMPLITUDE_SMOOTH_FACTOR

                // 如果振幅非常小，直接设置为0
                if (amplitude < MIN_AMPLITUDE_THRESHOLD) {
                    amplitude = 0f
                }

                // 2. 处理峰值衰减
                val currentTime = System.currentTimeMillis()
                if (currentTime - peakDecayTimer > 200) { // 200ms后开始衰减
                    peakAmplitude *= 0.95f
                    if (peakAmplitude < MIN_AMPLITUDE_THRESHOLD) peakAmplitude = 0f
                }

                // 3. 更新相位，创建波浪流动效果
                val speedMultiplier = when {
                    isSpeakingMode -> 1.5f
                    isAssistantMode -> 1.2f
                    else -> 1.0f
                }
                phase += 0.15f * speedMultiplier + (amplitude / height) * 0.1f

                // 4. 更新脉冲相位
                pulsePhase += 0.05f

                // 5. 绘制主波浪
                drawMainWave(canvas)

                // 6. 绘制次波浪
                drawSecondaryWave(canvas)

                // 7. 绘制峰值指示器
                if (peakAmplitude > height * 0.05f) {
                    drawPeakIndicator(canvas)
                }

                // 8. 绘制脉冲效果（高能量时）
                if (amplitude > height * 0.2f) {
                    drawPulseEffect(canvas)
                }

            } catch (e: Exception) {
                Log.e("VoiceWaveView", "绘制异常: ${e.message}", e)
                // 发生异常时停止动画，避免无限重试
                stopWaveAnimation()
            }
        }
    }

    private fun drawMainWave(canvas: Canvas) {
        wavePath.reset()
        val centerY = height / 2f

        // 添加起点
        wavePath.moveTo(0f, centerY)

        // 生成波浪点
        for (i in 0..wavePoints) {
            val x = i.toFloat() / wavePoints * width

            // 组合多个正弦波创建更自然的波浪
            val radians1 = (x / width * 2 * Math.PI * waveCount) + phase
            val radians2 = (x / width * 4 * Math.PI * (waveCount - 1)) + phase * 1.3f
            val radians3 = (x / width * 6 * Math.PI * (waveCount - 2)) + phase * 0.7f

            val wave1 = amplitude * sin(radians1).toFloat()
            val wave2 = amplitude * 0.3f * cos(radians2).toFloat()
            val wave3 = amplitude * 0.1f * sin(radians3).toFloat()

            val y = centerY + wave1 + wave2 + wave3

            if (i == 0) {
                wavePath.moveTo(x, y)
            } else {
                // 使用二次贝塞尔曲线使波浪更平滑
                val prevX = (i - 1).toFloat() / wavePoints * width
                val prevY = getLastPathPoint(wavePath)
                val ctrlX = (prevX + x) / 2
                wavePath.quadTo(ctrlX, prevY, x, y)
            }
        }

        // 闭合路径填充
        wavePath.lineTo(width.toFloat(), height.toFloat())
        wavePath.lineTo(0f, height.toFloat())
        wavePath.close()

        // 设置透明度基于能量
        val baseAlpha = if (isSilentMode) 100 else 150
        val energyAlpha = (amplitude / height * 105).toInt()
        paint.alpha = (baseAlpha + energyAlpha).coerceIn(50, 255)

        canvas.drawPath(wavePath, paint)
    }

    private fun drawSecondaryWave(canvas: Canvas) {
        secondaryPath.reset()
        val centerY = height / 2f
        val secondaryAmplitude = amplitude * 0.7f

        // 相位偏移，创建立体感
        val secondaryPhase = phase * 0.7f

        secondaryPath.moveTo(0f, centerY)

        for (i in 0..wavePoints) {
            val x = i.toFloat() / wavePoints * width
            val radians = (x / width * 2 * Math.PI * (waveCount + 1)) + secondaryPhase
            val y = centerY + secondaryAmplitude * cos(radians).toFloat()

            if (i == 0) {
                secondaryPath.moveTo(x, y)
            } else {
                secondaryPath.lineTo(x, y)
            }
        }

        // 设置次波浪样式
        val secondaryAlpha = if (isSilentMode) 50 else (100 + (amplitude / height * 50)).toInt()
        secondaryPaint.alpha = secondaryAlpha.coerceIn(0, 200)
        secondaryPaint.strokeWidth = if (isSpeakingMode) 3f else 2f

        canvas.drawPath(secondaryPath, secondaryPaint)
    }

    private fun drawPeakIndicator(canvas: Canvas) {
        val indicatorY = height / 2f - peakAmplitude

        // 绘制虚线
        peakPaint.alpha = 150
        canvas.drawLine(0f, indicatorY, width.toFloat(), indicatorY, peakPaint)

        // 绘制峰值标签
        if (peakAmplitude > height * 0.1f) {
            val labelPaint = Paint().apply {
                color = peakPaint.color
                textSize = 12f
                textAlign = Paint.Align.RIGHT
                isAntiAlias = true
            }

            val peakPercentage = (peakAmplitude / height * 200).toInt()
            val labelText = "峰值: ${peakPercentage}%"

            canvas.drawText(labelText, width - 10f, indicatorY - 5f, labelPaint)
        }
    }

    private fun drawPulseEffect(canvas: Canvas) {
        if (pulseAmplitude <= 0) return

        val pulsePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1f
            alpha = (pulseAmplitude * 100).toInt()
            pathEffect = DashPathEffect(floatArrayOf(5f, 10f), pulsePhase * 10)
            isAntiAlias = true
        }

        // 绘制脉冲波纹
        val pulseRadius = amplitude * pulseAmplitude
        val pulsePath = Path()

        for (i in 0..5) {
            val offset = i * 0.2f
            val currentPhase = pulsePhase + offset
            val currentRadius = pulseRadius * (1 - offset)

            if (currentRadius > 0) {
                pulsePath.reset()

                val centerY = height / 2f
                for (j in 0..wavePoints) {
                    val x = j.toFloat() / wavePoints * width
                    val radians = (x / width * 2 * Math.PI * waveCount) + currentPhase
                    val y = centerY + currentRadius * sin(radians).toFloat()

                    if (j == 0) {
                        pulsePath.moveTo(x, y)
                    } else {
                        pulsePath.lineTo(x, y)
                    }
                }

                pulsePaint.alpha = (pulseAmplitude * 50 * (1 - offset)).toInt()
                canvas.drawPath(pulsePath, pulsePaint)
            }
        }

        // 更新脉冲振幅
        pulseAmplitude *= 0.95f
        if (pulseAmplitude < 0.01f) {
            pulseAmplitude = 0f
        }
    }

    private fun getLastPathPoint(path: Path): Float {
        val bounds = RectF()
        path.computeBounds(bounds, true)
        return bounds.centerY()
    }

    /**
     * 切换颜色主题
     */
    fun toggleTheme() {
        val themes = WaveTheme.values()
        val currentIndex = themes.indexOf(currentTheme)
        val nextIndex = (currentIndex + 1) % themes.size
        setupTheme(themes[nextIndex])
        invalidate()
    }

    /**
     * 获取当前主题
     */
    fun getCurrentTheme(): WaveTheme {
        return currentTheme
    }

    /**
     * 获取当前动画状态
     */
    fun getAnimationState(): Boolean {
        return isAnimating
    }

    /**
     * 获取当前视图附加状态
     */
    fun getViewAttachedState(): Boolean {
        return isViewAttached
    }

    /**
     * 重启波浪动画（用于连接断开重连后）
     * 完全重置动画状态并重新启动，确保波浪动画能正常跟随人说话变化
     */
    fun restart() {
        Log.d("VoiceWaveView", "重启波浪动画")

        // 停止当前动画
        stopInternalAnimation()

        // 重置所有状态
        resetWaveState()

        // 重置模式标志
        isSpeakingMode = false
        isAssistantMode = false
        isSilentMode = false

        // 恢复默认主题
        setupTheme(WaveTheme.BLUE_OCEAN)

        // 重置波浪参数
        waveCount = 3

        // 重新启动动画
        isAnimating = true

        // 启动一个初始脉冲动画
        pulseAmplitude = 1f

        // 启动动画循环
        if (isViewAttached) {
            startInternalAnimation()
        }

        // 立即重绘
        invalidate()

        Log.d("VoiceWaveView", "波浪动画重启完成, isViewAttached=$isViewAttached")
    }

    /**
     * 清理所有资源
     */
    fun cleanup() {
        isAnimating = false
        isViewAttached = false
        stopInternalAnimation()
        animationHandler.removeCallbacksAndMessages(null)
        resetWaveState()
        Log.d("VoiceWaveView", "资源已清理")
    }
}