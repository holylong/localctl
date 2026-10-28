// AudioEnergyHolder.kt
package com.minrray.voicectl.core.audio

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10

/**
 * 音频能量持有者 - 单例模式
 * 负责管理音频能量的计算、平滑、状态检测和峰值检测
 */
object AudioEnergyHolder {
    // 使用 CopyOnWriteArrayList 保证线程安全
    private val energyHistory = CopyOnWriteArrayList<Float>()
    private const val HISTORY_SIZE = 5

    // 使用 volatile 保证可见性
    @Volatile
    private var peakEnergy: Float = 0f

    @Volatile
    private var peakDecayTimer: Long = 0

    // 能量阈值配置
    private const val ENERGY_THRESHOLD_SILENCE = 0.05f
    private const val ENERGY_THRESHOLD_TALKING = 0.15f
    private const val ENERGY_THRESHOLD_LOUD = 0.3f

    // 峰值衰减配置
    private const val PEAK_DECAY_DELAY_MS = 200L
    private const val PEAK_DECAY_FACTOR = 0.95f
    private const val PEAK_MIN_THRESHOLD = 0.01f

    // 能量映射配置
    private const val ENERGY_AMPLIFICATION = 5f
    private const val LOG_SCALE_FACTOR = 9f

    // LiveData 实例
    private val _currentEnergy = MutableLiveData<Float>(0f)
    private val _energyState = MutableLiveData<EnergyState>(EnergyState.SILENT)

    // 公开的只读 LiveData
    val currentEnergy: LiveData<Float> get() = _currentEnergy
    val energyState: LiveData<EnergyState> get() = _energyState

    // 同步锁对象
    private val lock = Any()

    // 原子操作标志
    private val isResetting = AtomicBoolean(false)
    private val isUpdating = AtomicBoolean(false)

    // 主线程 Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 能量状态枚举
     */
    enum class EnergyState {
        SILENT,      // 安静
        BACKGROUND,  // 背景噪音
        TALKING,     // 说话
        LOUD         // 大声说话
    }

    /**
     * 更新原始音频能量值
     * 线程安全：可被多个音频线程同时调用
     */
    fun updateEnergy(rawEnergy: Float) {
        // 检查是否正在重置，如果是则跳过更新
        if (isResetting.get()) {
            return
        }

        // 防止并发更新 - 使用带超时的尝试
        var attempts = 0
        while (!isUpdating.compareAndSet(false, true)) {
            if (++attempts > 5) {
                // 避免死锁，放弃本次更新
                Log.w("AudioEnergyHolder", "更新尝试超时，放弃本次更新")
                return
            }
            Thread.sleep(1)
        }

        try {
            // 再次检查重置状态
            if (isResetting.get()) {
                return
            }

            val (smoothedEnergy, mappedEnergy) = synchronized(lock) {
                // 添加新能量值到历史
                if (energyHistory.size >= HISTORY_SIZE) {
                    energyHistory.removeAt(0)
                }
                energyHistory.add(rawEnergy)

                // 计算平滑后的能量值（移动平均）
                val smoothed = if (energyHistory.isNotEmpty()) {
                    energyHistory.average().toFloat()
                } else {
                    rawEnergy
                }

                // 更新峰值能量
                updatePeakEnergy(smoothed)

                // 对能量值进行非线性映射
                val mapped = mapEnergyForVisual(smoothed)

                Pair(smoothed, mapped)
            }

            // 更新能量状态（在锁外执行，避免阻塞）
            updateEnergyState(mappedEnergy)

            // 安全地发布更新后的能量值（在主线程）
            postValueSafely(_currentEnergy, mappedEnergy)

            // 调试日志
            if (mappedEnergy > 0.5f) {
               // Log.d("AudioEnergyHolder", "高能量检测: $mappedEnergy (原始: $rawEnergy, 平滑: $smoothedEnergy)")
            }
        } catch (e: Exception) {
            Log.e("AudioEnergyHolder", "更新能量异常: ${e.message}")
        } finally {
            isUpdating.set(false)
        }
    }
    /**
     * 更新峰值能量
     */
    private fun updatePeakEnergy(smoothedEnergy: Float) {
        if (smoothedEnergy > peakEnergy) {
            peakEnergy = smoothedEnergy
            peakDecayTimer = System.currentTimeMillis()
        }

        // 处理峰值衰减
        val currentTime = System.currentTimeMillis()
        if (currentTime - peakDecayTimer > PEAK_DECAY_DELAY_MS) {
            peakEnergy *= PEAK_DECAY_FACTOR
            if (peakEnergy < PEAK_MIN_THRESHOLD) peakEnergy = 0f
        }
    }

    /**
     * 根据能量值映射为视觉效果更佳的值
     */
    private fun mapEnergyForVisual(energy: Float): Float {
        // 使用指数函数增强小值的可见性，同时限制最大值
        val amplified = energy * ENERGY_AMPLIFICATION

        // 使用log函数使响应更自然
        val logScaled = log10(amplified * LOG_SCALE_FACTOR + 1)

        // 限制在0-1范围内
        return logScaled.coerceIn(0f, 1f)
    }

    /**
     * 更新能量状态
     */
    private fun updateEnergyState(mappedEnergy: Float) {
        val newState = when {
            mappedEnergy < ENERGY_THRESHOLD_SILENCE -> EnergyState.SILENT
            mappedEnergy < ENERGY_THRESHOLD_TALKING -> EnergyState.BACKGROUND
            mappedEnergy < ENERGY_THRESHOLD_LOUD -> EnergyState.TALKING
            else -> EnergyState.LOUD
        }

        // 只在状态变化时更新
        if (_energyState.value != newState) {
            postValueSafely(_energyState, newState)
          //  Log.d("AudioEnergyHolder", "能量状态变化: ${_energyState.value} -> $newState")
        }
    }

    /**
     * 安全地发布 LiveData 值
     */
    private fun <T> postValueSafely(liveData: MutableLiveData<T>, value: T) {
        if (Thread.currentThread() == Looper.getMainLooper().thread) {
            // 如果在主线程，直接设置值
            liveData.value = value
        } else {
            // 如果在后台线程，使用 Handler 发布到主线程
            mainHandler.post {
                // 再次检查是否正在重置
                if (!isResetting.get()) {
                    liveData.value = value
                }
            }
        }
    }

    /**
     * 获取当前峰值能量
     */
    fun getPeakEnergy(): Float = synchronized(lock) {
        return peakEnergy
    }

    /**
     * 重置所有能量数据（安全版本）
     */
    fun reset() {
        // 标记正在重置
        isResetting.set(true)

        // 等待可能的更新完成
        while (isUpdating.get()) {
            Thread.yield()
        }

        synchronized(lock) {
            try {
                energyHistory.clear()
                peakEnergy = 0f
                peakDecayTimer = 0

                // 在主线程重置 LiveData 值
                if (Thread.currentThread() == Looper.getMainLooper().thread) {
                    _currentEnergy.value = 0f
                    _energyState.value = EnergyState.SILENT
                } else {
                    mainHandler.post {
                        _currentEnergy.value = 0f
                        _energyState.value = EnergyState.SILENT
                    }
                }

                Log.d("AudioEnergyHolder", "能量数据已重置")
            } finally {
                // 重置完成后清除标志
                isResetting.set(false)
            }
        }
    }

    /**
     * 快速重置（不等待更新完成）
     */
    fun fastReset() {
        synchronized(lock) {
            energyHistory.clear()
            peakEnergy = 0f
            peakDecayTimer = 0
        }

        Log.d("AudioEnergyHolder", "能量数据快速重置")
    }

    /**
     * 获取能量历史记录（用于图表等）
     */
    fun getEnergyHistory(): List<Float> = synchronized(lock) {
        return energyHistory.toList()
    }

    /**
     * 获取能量统计信息
     */
    fun getEnergyStats(): Map<String, Float> = synchronized(lock) {
        return mapOf(
            "current" to (_currentEnergy.value ?: 0f),
            "peak" to peakEnergy,
            "average" to (if (energyHistory.isNotEmpty()) energyHistory.average().toFloat() else 0f),
            "min" to (if (energyHistory.isNotEmpty()) energyHistory.minOrNull() ?: 0f else 0f),
            "max" to (if (energyHistory.isNotEmpty()) energyHistory.maxOrNull() ?: 0f else 0f)
        )
    }

    /**
     * 获取当前能量状态
     */
    fun getCurrentState(): EnergyState? = _energyState.value

    /**
     * 检查是否处于特定状态
     */
    fun isState(state: EnergyState): Boolean = _energyState.value == state

    /**
     * 获取能量阈值配置
     */
    fun getThresholds(): Map<String, Float> = mapOf(
        "silence" to ENERGY_THRESHOLD_SILENCE,
        "talking" to ENERGY_THRESHOLD_TALKING,
        "loud" to ENERGY_THRESHOLD_LOUD
    )

    /**
     * 检查是否正在重置
     */
    fun isResetting(): Boolean = isResetting.get()
}