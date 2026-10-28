package com.minrray.voicectl.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AudioRecorder private constructor() {
    companion object {
        private const val TAG = "AudioRecorder"
        private lateinit var instance: AudioRecorder
        fun getInstance(): AudioRecorder {
            if (!::instance.isInitialized) {
                instance = AudioRecorder()
            }
            return instance
        }
    }

    private var audioRecord: AudioRecord? = null
    private var _isRecording = false

    private var recordingJob: Job? = null
    private val sampleRate = 16000//16000 // 16kHz采样率
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO // 单声道
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16位PCM
    private val bufferSize: Int
    private val frameSize = 960 // 960个采样点/帧，与前端保持一致
    private val opusEncoder = OpusCodec.getInstance()
    private var pcmBuffer = ShortArray(0)

    // 音频处理组件（使用Any类型避免编译错误）
    private var noiseSuppressor: Any? = null

    // 回调接口
    var onRecordingStart: (() -> Unit)? = null
    var onRecordingStop: (() -> Unit)? = null
    var onVisualizerUpdate: ((FloatArray) -> Unit)? = null
    var onOpusDataAvailable: ((ByteArray) -> Unit)? = null
    var onPcmDataAvailable: ((ShortArray) -> Unit)? = null
    
    // PCM数据监听器列表，支持多个监听器
    private val pcmDataListeners = mutableListOf<(ShortArray) -> Unit>()
    
    // 控制是否进行Opus编码的标志位
    var isOpusEncodingEnabled = false

    // 日志限流相关
    private val logThrottleMap = HashMap<String, Long>()
    private val logThrottleInterval = 2000L // 日志限流间隔2秒

    init {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        Log.d(TAG, "getMinBufferSize返回值: $minBufferSize, sampleRate=$sampleRate, channelConfig=$channelConfig, audioFormat=$audioFormat")
        
        if (minBufferSize <= 0) {
            Log.e(TAG, "getMinBufferSize返回无效值: $minBufferSize, 使用默认值")
            bufferSize = 6400 // 默认buffer size，约200ms的音频数据(16kHz单声道16位)
        } else {
            bufferSize = minBufferSize * 2
            Log.d(TAG, "使用计算的bufferSize: $bufferSize")
        }
        
        opusEncoder.initEncoder(sampleRate)
    }

    // 日志限流辅助方法
    private fun shouldLog(logKey: String): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastLogTime = logThrottleMap[logKey] ?: 0L
        if (currentTime - lastLogTime >= logThrottleInterval) {
            logThrottleMap[logKey] = currentTime
            return true
        }
        return false
    }

    // 添加PCM数据监听器
    fun addPcmDataListener(listener: (ShortArray) -> Unit) {
        if (!pcmDataListeners.contains(listener)) {
            pcmDataListeners.add(listener)
            Log.d(TAG, "添加PCM监听器，当前监听器数量: ${pcmDataListeners.size}")
        }
    }

    // 移除PCM数据监听器
    fun removePcmDataListener(listener: (ShortArray) -> Unit) {
        pcmDataListeners.remove(listener)
        Log.d(TAG, "移除PCM监听器，当前监听器数量: ${pcmDataListeners.size}")
    }

    // 开始录音
    @SuppressLint("MissingPermission")
    fun start(enableOpusEncoding: Boolean = false) {
        Log.d(TAG, "AudioRecord start _isRecording=$_isRecording,enableOpusEncoding=$enableOpusEncoding")
        Log.d(TAG, "音频参数: sampleRate=$sampleRate, channelConfig=$channelConfig, audioFormat=$audioFormat, bufferSize=$bufferSize")
        // 如果已经在录音，只更新编码标志，不重新启动
        if (_isRecording) {
            isOpusEncodingEnabled = enableOpusEncoding
            Log.d(TAG, "AudioRecord已在录音，只更新编码标志为: $enableOpusEncoding")
            return
        }

        try {
            Log.d(TAG, "开始创建AudioRecord实例...")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            val state = audioRecord?.state
            Log.d(TAG, "AudioRecord创建完成，state=$state (STATE_INITIALIZED=${AudioRecord.STATE_INITIALIZED})")

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord初始化失败，state=$state")
                Log.e(TAG, "可能的错误原因:")
                Log.e(TAG, "1. 设备不支持当前的音频配置参数")
                Log.e(TAG, "2. 音频设备被其他应用占用")
                Log.e(TAG, "3. 摄像头和录音资源冲突")
                Log.e(TAG, "4. 权限不足或设备限制")
                return
            }

            // 设置Opus编码标志
            isOpusEncodingEnabled = enableOpusEncoding
            
            Log.d(TAG, "开始录音...")
            audioRecord?.startRecording()
            _isRecording = true
            Log.d(TAG, "录音已启动")
            
            // 初始化并启用音频处理功能
            initAudioEffects()

            recordingJob = CoroutineScope(Dispatchers.Default).launch {
                val buffer = ShortArray(bufferSize)
                val visualizerBuffer = FloatArray(64) // 可视化数据大小
                var startTime = System.currentTimeMillis()
                pcmBuffer = ShortArray(0) // 重置动态缓冲区，用于累积PCM数据

                while (_isRecording) {
                    // 确保audioRecord不为空且仍在录制状态
                    val currentAudioRecord = audioRecord
                    if (currentAudioRecord == null || currentAudioRecord.state != AudioRecord.STATE_INITIALIZED) {
                        break
                    }
                    
                    val readSize = try {
                        currentAudioRecord.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "读取音频数据失败: ${e.message}")
                        break
                    }
                    
                    if (readSize > 0 && readSize != AudioRecord.ERROR_INVALID_OPERATION && readSize != AudioRecord.ERROR_BAD_VALUE) {
                        // 更新录制时间
                        val elapsedTime = (System.currentTimeMillis() - startTime) / 1000.0
                        // 只在第一次调用时触发onRecordingStart回调
                        if (onRecordingStart != null && elapsedTime < 0.1) {
                            onRecordingStart?.invoke()
                        }

                        // 将读取的PCM数据添加到缓冲区
                        val newBuffer = ShortArray(pcmBuffer.size + readSize)
                        System.arraycopy(pcmBuffer, 0, newBuffer, 0, pcmBuffer.size)
                        System.arraycopy(buffer, 0, newBuffer, pcmBuffer.size, readSize)
                        pcmBuffer = newBuffer

                        // 当缓冲区中的数据达到或超过一帧大小(960个采样点)时，处理数据
                        while (pcmBuffer.size >= frameSize) {
                            // 提取一帧数据
                            val frameData = ShortArray(frameSize)
                            System.arraycopy(pcmBuffer, 0, frameData, 0, frameSize)
                            // 移除已处理的数据
                            val remainingBuffer = ShortArray(pcmBuffer.size - frameSize)
                            System.arraycopy(pcmBuffer, frameSize, remainingBuffer, 0, remainingBuffer.size)
                            pcmBuffer = remainingBuffer

                            // 将PCM数据通过回调传递出去，供唤醒词检测使用
                            onPcmDataAvailable?.invoke(frameData)

                            // 通知所有PCM监听器
                            for (listener in pcmDataListeners) {
                                try {
                                    listener(frameData)
                                } catch (e: Exception) {
                                    Log.e(TAG, "PCM监听器回调异常: ${e.message}")
                                }
                            }

                            // 只有当启用Opus编码时，才进行编码和发送
                            if (isOpusEncodingEnabled) {
                                // 进行Opus编码
                                val opusData = opusEncoder.encode(frameData)
                                // 通过回调发送编码后的Opus数据
                                if (opusData.isNotEmpty()) {
                                    onOpusDataAvailable?.invoke(opusData)
                                } else {
                                    if (shouldLog("opus_encode_empty")) {
                                        Log.w(TAG, "编码返回空数据，编解码器状态: ${opusEncoder.getStats()}")
                                    }
                                }
                            }
                        }

                        // 更新可视化数据
                        updateVisualizerData(buffer, visualizerBuffer)
                        onVisualizerUpdate?.invoke(visualizerBuffer)
                    }
                }

                // 处理剩余的PCM数据
                if (pcmBuffer.size > 0 && isOpusEncodingEnabled) {
                    // 填充剩余数据到一帧
                    val frameData = ShortArray(frameSize)
                    System.arraycopy(pcmBuffer, 0, frameData, 0, pcmBuffer.size)
                    // 编码并发送
                    val opusData = opusEncoder.encode(frameData)
                    // 只发送非空数据
                    if (opusData.isNotEmpty()) {
                        onOpusDataAvailable?.invoke(opusData)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "录音开始失败: ${e.message}")
        }
    }

    // 停止录音
    fun stop() {
        if (!_isRecording) return

        try {
            // 先更新状态标志，让协程退出循环
            _isRecording = false
            
            // 取消录音协程
            recordingJob?.cancel()
            recordingJob = null
            
            // 移除Thread.sleep，避免主线程阻塞，协程会自动安全退出
            
            // 释放音频效果资源
            releaseAudioEffects()
            
            // 停止并释放AudioRecord
            val currentAudioRecord = audioRecord
            if (currentAudioRecord != null) {
                try {
                    // 停止录音
                    currentAudioRecord.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "停止AudioRecord失败: ${e.message}")
                }
                
                try {
                    // 释放资源
                    currentAudioRecord.release()
                } catch (e: Exception) {
                    Log.e(TAG, "释放AudioRecord失败: ${e.message}")
                }
            }
            
            // 清空引用
            audioRecord = null
            
            // 调用停止回调
            onRecordingStop?.invoke()
            Log.d(TAG, "录音停止，编解码器统计: ${opusEncoder.getStats()}")
        } catch (e: Exception) {
            Log.e(TAG, "录音停止失败: ${e.message}", e)
            // 确保状态正确
            _isRecording = false
            audioRecord = null
        }
    }
    
    // 初始化音频效果（使用反射避免编译错误）
    private fun initAudioEffects() {
        val audioRecord = this.audioRecord ?: return
        val sessionId = audioRecord.audioSessionId
        
        try {
            // 启用噪音抑制
            val noiseSuppressorClass = Class.forName("android.media.audiofx.NoiseSuppressor")
            val isNoiseSuppressorAvailable = noiseSuppressorClass.getMethod("isAvailable").invoke(null) as Boolean
            if (isNoiseSuppressorAvailable) {
                val createNoiseSuppressor = noiseSuppressorClass.getMethod("create", Int::class.javaPrimitiveType)
                noiseSuppressor = createNoiseSuppressor.invoke(null, sessionId)
                if (noiseSuppressor != null) {
                    val setEnabled = noiseSuppressorClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                    setEnabled.invoke(noiseSuppressor, true)
                    Log.d(TAG, "噪音抑制功能已启用")
                } else {
                    Log.w(TAG, "创建噪音抑制器失败")
                }
            } else {
                Log.w(TAG, "设备不支持噪音抑制")
            }
        } catch (e: Exception) {
            Log.w(TAG, "初始化音频效果失败: ${e.message}")
        }
    }
    
    // 释放音频效果资源（使用反射）
    private fun releaseAudioEffects() {
        try {
            // 释放噪音抑制器
            if (noiseSuppressor != null) {
                val noiseSuppressorClass = Class.forName("android.media.audiofx.NoiseSuppressor")
                val setEnabled = noiseSuppressorClass.getMethod("setEnabled", Boolean::class.javaPrimitiveType)
                setEnabled.invoke(noiseSuppressor, false)
                val release = noiseSuppressorClass.getMethod("release")
                release.invoke(noiseSuppressor)
                noiseSuppressor = null
            }
            
            Log.d(TAG, "音频效果资源已释放")
        } catch (e: Exception) {
            Log.w(TAG, "释放音频效果资源失败: ${e.message}")
        }
    }

    // 更新可视化数据
    private fun updateVisualizerData(pcmData: ShortArray, visualizerBuffer: FloatArray) {
        val step = pcmData.size / visualizerBuffer.size
        for (i in visualizerBuffer.indices) {
            var sum = 0.0f
            var count = 0
            for (j in i * step until minOf((i + 1) * step, pcmData.size)) {
                sum += Math.abs(pcmData[j].toFloat())
                count++
            }
            if (count > 0) {
                visualizerBuffer[i] = sum / count / Short.MAX_VALUE
            }
        }
    }

    // 检查是否正在录音
    val isRecording: Boolean
        get() = this._isRecording
}