package com.minrray.voicectl.core.audio

import android.util.Log
import com.theeasiestway.opus.Constants
import com.theeasiestway.opus.Opus

class OpusCodec private constructor() {
    companion object {
        private const val TAG = "OpusCodec"
        private lateinit var instance: OpusCodec
        fun getInstance(): OpusCodec {
            if (!::instance.isInitialized) {
                instance = OpusCodec()
            }
            return instance
        }
    }

    private val sampleRate = 16000
    private val channels = 1
    private val opus = Opus()
    private var _encoderInitialized = false
    private var _decoderInitialized = false
    private var currentSampleRate = 16000

    // 添加同步锁，防止多线程竞争
    private val decoderLock = Any()
    private val encoderLock = Any()

    // 解码器状态跟踪
    private var decoderState = DecoderState.IDLE
    private enum class DecoderState {
        IDLE,           // 空闲
        INITIALIZING,   // 初始化中
        READY,          // 就绪
        DECODING,       // 解码中
        ERROR,          // 错误
        RELEASING       // 释放中
    }

    // 初始化编码器
    fun initEncoder(sampleRate: Int): Boolean {
        try {
            synchronized(encoderLock) {
                // 使用第三方Opus库初始化编码器
                val result = opus.encoderInit(
                    Constants.SampleRate._16000(),
                    Constants.Channels.mono(),
                    Constants.Application.voip()
                )
                
                if (result < 0) {
                    Log.e(TAG, "Opus编码器初始化失败: 错误码 $result")
                    _encoderInitialized = false
                    return false
                }
                
                // 设置编码参数
                opus.encoderSetBitrate(Constants.Bitrate.instance(16000)) // 16 kbps
                opus.encoderSetComplexity(Constants.Complexity.instance(5)) // 中等复杂度
                
                _encoderInitialized = true
                currentSampleRate = sampleRate
                Log.d(TAG, "Opus编码器初始化成功，采样率: $sampleRate")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Opus编码器初始化失败: ${e.message}", e)
            _encoderInitialized = false
            return false
        }
    }

    // 初始化解码器
    fun initDecoder(sampleRate: Int): Boolean {
        synchronized(decoderLock) {
            // 先确保解码器资源已释放
            releaseDecoderInternal()

            decoderState = DecoderState.INITIALIZING
            _decoderInitialized = false
            consecutiveDecodeFailures = 0

            try {
                // 使用第三方Opus库初始化解码器
                val result = opus.decoderInit(
                    Constants.SampleRate._16000(),
                    Constants.Channels.mono()
                )
                
                if (result < 0) {
                    Log.e(TAG, "Opus解码器初始化失败: 错误码 $result")
                    decoderState = DecoderState.ERROR
                    return false
                }

                _decoderInitialized = true
                decoderState = DecoderState.READY
                currentSampleRate = sampleRate
                Log.d(TAG, "Opus解码器初始化成功，采样率: $sampleRate")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Opus解码器初始化失败: ${e.message}", e)
                // 确保资源被释放
                releaseDecoderInternal()
                decoderState = DecoderState.ERROR
                return false
            }
        }
    }

    // 编码PCM数据为Opus
    fun encode(pcmData: ShortArray): ByteArray {
        if (!_encoderInitialized) {
            Log.e(TAG, "Opus编码器未初始化")
            return ByteArray(0)
        }

        try {
            synchronized(encoderLock) {
                // 使用第三方Opus库编码数据
                val frameSize = when (currentSampleRate) {
                    8000 -> Constants.FrameSize._160()
                    16000 -> Constants.FrameSize._960()
                    24000 -> Constants.FrameSize._480()
                    48000 -> Constants.FrameSize._960()
                    else -> Constants.FrameSize._960()
                }
                
                val encodedData = opus.encode(pcmData, frameSize)
                
                if (encodedData == null || encodedData.isEmpty()) {
                    Log.w(TAG, "Opus编码未获取到输出数据")
                    updateEncodeStats(false)
                    return ByteArray(0)
                }
                
                // 将ShortArray转换为ByteArray
                val result = ByteArray(encodedData.size * 2)
                for (i in encodedData.indices) {
                    result[i * 2] = (encodedData[i].toInt() and 0xFF).toByte()
                    result[i * 2 + 1] = (encodedData[i].toInt() shr 8 and 0xFF).toByte()
                }
                
                // Log.d(TAG, "Opus编码成功，输入大小: ${pcmData.size}采样点，输出大小: ${result.size}字节")
                updateEncodeStats(true)
                // Log.d(TAG, "编码统计: ${getStats()}")
                return result
            }
        } catch (e: Exception) {
            Log.e(TAG, "Opus编码失败", e)
            // 更新编码器状态标志
            synchronized(encoderLock) {
                _encoderInitialized = false
            }
            return ByteArray(0)
        }
    }

    // 解码Opus数据为PCM
    fun decode(opusData: ByteArray): ShortArray {
        // 检查输入数据是否有效
        if (opusData.isEmpty()) {
            Log.e(TAG, "无效的Opus数据: 数据为空")
            return ShortArray(0)
        }

        synchronized(decoderLock) {
            // 检查解码器状态
            if (decoderState != DecoderState.READY || !_decoderInitialized) {
                Log.e(TAG, "Opus解码器未就绪，当前状态: $decoderState")
                // 尝试重新初始化
                if (decoderState == DecoderState.ERROR || decoderState == DecoderState.IDLE) {
                    Log.d(TAG, "尝试重新初始化解码器")
                    if (!initDecoder(currentSampleRate)) {
                        return ShortArray(0)
                    }
                } else {
                    return ShortArray(0)
                }
            }

            decoderState = DecoderState.DECODING

            try {
                // Opus 数据直接传递给解码器，不需要转换为 ShortArray
                // Opus 数据是压缩的音频数据，不是 PCM 数据
                
                // 使用第三方Opus库解码数据
                val frameSize = when (currentSampleRate) {
                    8000 -> Constants.FrameSize._160()
                    16000 -> Constants.FrameSize._960()
                    24000 -> Constants.FrameSize._480()
                    48000 -> Constants.FrameSize._960()
                    else -> Constants.FrameSize._960()
                }
                
                val decodedData = opus.decode(opusData, frameSize)
                
                if (decodedData == null || decodedData.isEmpty()) {
                    Log.w(TAG, "Opus解码未获取到输出数据")
                    consecutiveDecodeFailures++
                    
                    if (consecutiveDecodeFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.e(TAG, "连续解码失败次数达到阈值 ($consecutiveDecodeFailures)，自动重置解码器")
                        consecutiveDecodeFailures = 0
                        releaseDecoderInternal()
                        if (initDecoder(currentSampleRate)) {
                            return ShortArray(0)
                        }
                    }
                    
                    updateDecodeStats(false)
                    decoderState = DecoderState.READY
                    return ShortArray(0)
                }
                
                // 将 PCM 数据（ByteArray）转换为 ShortArray
                // PCM 数据是 16 位小端字节序，每个采样点占用 2 个字节
                val pcmShortArray = ShortArray(decodedData.size / 2)
                for (i in pcmShortArray.indices) {
                    val lowByte = decodedData[i * 2].toInt() and 0xFF
                    val highByte = decodedData[i * 2 + 1].toInt() and 0xFF
                    pcmShortArray[i] = (highByte shl 8 or lowByte).toShort()
                }
                
                consecutiveDecodeFailures = 0
                // Log.d(TAG, "Opus解码成功，输入大小: ${opusData.size}字节，输出大小: ${pcmShortArray.size}采样点")
                updateDecodeStats(true)
                // Log.d(TAG, "解码统计: ${getStats()}")

                decoderState = DecoderState.READY
                return pcmShortArray
            } catch (e: Exception) {
                Log.e(TAG, "Opus解码失败", e)
                consecutiveDecodeFailures++
                
                if (consecutiveDecodeFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.e(TAG, "连续解码失败次数达到阈值 ($consecutiveDecodeFailures)，自动重置解码器")
                    consecutiveDecodeFailures = 0
                    releaseDecoderInternal()
                    initDecoder(currentSampleRate)
                }
                
                decoderState = DecoderState.ERROR
                // 安全释放解码器资源
                releaseDecoderInternal()
                return ShortArray(0)
            }
        }
    }

    // 性能监控统计
    private var encodeSuccessCount = 0
    private var encodeFailureCount = 0
    private var decodeSuccessCount = 0
    private var decodeFailureCount = 0
    private var lastErrorTime: Long = 0
    private var lastError: String? = null
    private var consecutiveDecodeFailures = 0
    private val MAX_CONSECUTIVE_FAILURES = 3

    // 检查解码器是否初始化
    val isDecoderInitialized: Boolean
        get() = decoderState == DecoderState.READY && _decoderInitialized

    // 检查编码器是否初始化
    val isEncoderInitialized: Boolean
        get() = _encoderInitialized

    // 获取解码器状态信息
    fun getDecoderStatus(): String {
        return "状态: $decoderState, 初始化: $_decoderInitialized"
    }

    // 获取性能统计
    fun getStats(): String {
        return "编码: 成功=$encodeSuccessCount, 失败=$encodeFailureCount | " +
               "解码: 成功=$decodeSuccessCount, 失败=$decodeFailureCount | " +
               "解码器状态: $decoderState | " +
               "最后错误: ${lastError ?: "无"} (${if (lastErrorTime > 0) "${(System.currentTimeMillis() - lastErrorTime) / 1000}秒前" else "从未"})"
    }

    // 更新统计信息
    private fun updateEncodeStats(success: Boolean) {
        if (success) encodeSuccessCount++ else encodeFailureCount++
        if (!success) {
            lastErrorTime = System.currentTimeMillis()
            lastError = "编码失败"
        }
    }

    private fun updateDecodeStats(success: Boolean) {
        if (success) decodeSuccessCount++ else decodeFailureCount++
        if (!success) {
            lastErrorTime = System.currentTimeMillis()
            lastError = "解码失败"
        }
    }
    
    // 释放编码器
    fun releaseEncoder() {
        try {
            synchronized(encoderLock) {
                opus.encoderRelease()
                _encoderInitialized = false
                Log.d(TAG, "Opus编码器已释放")
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放编码器失败: ${e.message}", e)
            synchronized(encoderLock) {
                _encoderInitialized = false
            }
        }
    }

    // 内部释放解码器方法（带锁保护）
    private fun releaseDecoderInternal() {
        synchronized(decoderLock) {
            if (decoderState == DecoderState.RELEASING) {
                Log.d(TAG, "解码器已经在释放中")
                return
            }

            decoderState = DecoderState.RELEASING

            try {
                opus.decoderRelease()
                Log.d(TAG, "解码器资源释放成功")
            } catch (e: Exception) {
                Log.e(TAG, "释放解码器资源失败: ${e.message}", e)
            } finally {
                // 确保状态被正确重置，无论之前的操作结果如何
                _decoderInitialized = false
                decoderState = DecoderState.IDLE
            }
            Log.d(TAG, "Opus解码器已释放")
        }
    }

    // 公共释放解码器方法
    fun releaseDecoder() {
        releaseDecoderInternal()
    }

    // 编解码器测试方法
    fun testCodec(): Boolean {
        Log.d(TAG, "开始编解码器测试...")

        // 测试编码器
        if (!_encoderInitialized) {
            Log.e(TAG, "编码器未初始化，无法测试")
            return false
        }

        // 生成测试PCM数据（正弦波）
        val testPcm = ShortArray(960) { i ->
            val angle = 2.0 * Math.PI * i / 960.0
            (Math.sin(angle * 440.0) * Short.MAX_VALUE).toInt().toShort()
        }

        try {
            // 测试编码
            Log.d(TAG, "测试编码: ${testPcm.size}采样点")
            val encoded = encode(testPcm)
            if (encoded.isEmpty()) {
                Log.e(TAG, "编码器测试失败: 输出为空")
                return false
            }
            Log.d(TAG, "编码测试通过: 输入=${testPcm.size}, 输出=${encoded.size}字节")

            // 测试解码
            Log.d(TAG, "测试解码: ${encoded.size}字节")
            val decoded = decode(encoded)
            if (decoded.isEmpty()) {
                Log.e(TAG, "解码器测试失败: 输出为空")
                return false
            }
            Log.d(TAG, "解码测试通过: 输入=${encoded.size}, 输出=${decoded.size}采样点")

            // 验证数据完整性（粗略检查）
            val valid = decoded.size > 0 && decoded.size <= testPcm.size * 2
            if (!valid) {
                Log.w(TAG, "数据完整性检查: 解码大小异常，期望<=${testPcm.size * 2}，实际=${decoded.size}")
            }

            Log.d(TAG, "编解码器测试完成: ${getStats()}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "编解码器测试失败: ${e.message}", e)
            return false
        }
    }

    // 重置统计信息
    fun resetStats() {
        encodeSuccessCount = 0
        encodeFailureCount = 0
        decodeSuccessCount = 0
        decodeFailureCount = 0
        lastErrorTime = 0
        lastError = null
        Log.d(TAG, "统计信息已重置")
    }
}