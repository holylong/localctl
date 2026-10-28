package com.minrray.voicectl.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.app.Application

class AudioPlayer private constructor() {
    companion object {
        private const val TAG = "AudioPlayer"
        private lateinit var instance: AudioPlayer
        fun getInstance(): AudioPlayer {
            if (!::instance.isInitialized) {
                instance = AudioPlayer()
            }
            return instance
        }
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var decodingJob: Job? = null
    private var playingJob: Job? = null
    private val sampleRate = 16000 // 16kHz采样率
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO // 单声道
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT // 16位PCM
    private val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
    private val opusCodec = OpusCodec.getInstance()
    
    // 音频流管理
    private var opusDataChannel: Channel<ByteArray> = Channel(capacity = Channel.UNLIMITED) // 待解码的Opus数据队列
    private var pcmDataChannel: Channel<ShortArray> = Channel(capacity = Channel.UNLIMITED) // 已解码的PCM数据队列
    
    // 配置参数
    private val FRAME_SIZE = 960 // Opus帧大小（16kHz下60ms）
    private val MIN_AUDIO_DURATION = 0.12 // 最小音频持续时间（秒）

    // 日志限流相关
    private val logThrottleMap = HashMap<String, Long>()
    private val logThrottleInterval = 2000L // 日志限流间隔2秒

    init {
        opusCodec.initDecoder(sampleRate)
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

    // 开始播放
    fun start() {
        if (isPlaying) return

        try {
            // 重新创建新的通道，避免使用已关闭的通道
            opusDataChannel = Channel(capacity = Channel.UNLIMITED)
            pcmDataChannel = Channel(capacity = Channel.UNLIMITED)
            
            // 设置音频属性为媒体播放，使用外放
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA) // 使用媒体播放属性，默认使用外放
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            audioTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack初始化失败")
                return
            }

            audioTrack?.play()
            isPlaying = true
            
            // 启动解码协程
            startDecodingCoroutine()
            // 启动播放协程
            startPlayingCoroutine()
            
            Log.d(TAG, "AudioPlayer开始播放，使用外放扬声器")
        } catch (e: Exception) {
            Log.e(TAG, "AudioPlayer启动失败: ${e.message}", e)
        }
    }
    
    // 启动解码协程
    private fun startDecodingCoroutine() {
        decodingJob = CoroutineScope(Dispatchers.Default).launch {
         //   Log.d(TAG, "解码协程已启动")
            try {
                while (isPlaying) {
                    try {
                        // 使用通道的tryReceive来避免在通道关闭时抛出异常
                        val opusDataResult = opusDataChannel.tryReceive()
                        if (opusDataResult.isSuccess) {
                            val opusData = opusDataResult.getOrThrow()
                            
                            // 解码Opus数据为PCM
                            val pcmData = opusCodec.decode(opusData)
                            if (pcmData.isNotEmpty()) {
                                // 将解码后的PCM数据发送到PCM队列
                                pcmDataChannel.trySend(pcmData)
                                // Log.d(TAG, "解码完成，PCM数据已发送到队列: ${pcmData.size}采样点")
                            }
                        }
                        // 短暂休眠，避免CPU占用过高
                        kotlinx.coroutines.delay(10)
                    } catch (e: Exception) {
                        if (isPlaying) {
                            Log.e(TAG, "解码协程异常: ${e.message}", e)
                        }
                    }
                }
            } finally {
              //  Log.d(TAG, "解码协程已停止")
            }
        }
    }
    
    // 启动播放协程
    private fun startPlayingCoroutine() {
        playingJob = CoroutineScope(Dispatchers.Default).launch {
            Log.d(TAG, "播放协程已启动")
            try {
                while (isPlaying) {
                    try {
                        // 使用通道的tryReceive来避免在通道关闭时抛出异常
                        val pcmDataResult = pcmDataChannel.tryReceive()
                        if (pcmDataResult.isSuccess) {
                            val pcmData = pcmDataResult.getOrThrow()
                            
                            // 将PCM数据写入AudioTrack播放
                            playPCMData(pcmData)
                        }
                        // 短暂休眠，避免CPU占用过高
                        kotlinx.coroutines.delay(10)
                    } catch (e: Exception) {
                        if (isPlaying) {
                            Log.e(TAG, "播放协程异常: ${e.message}", e)
                        }
                    }
                }
            } finally {
                Log.d(TAG, "播放协程已停止")
            }
        }
    }
    
    // 播放PCM数据
    private fun playPCMData(pcmData: ShortArray) {
        val audioTrack = this.audioTrack ?: run {
            Log.e(TAG, "AudioTrack未初始化")
            return
        }
        
        try {
            // 将ShortArray转换为直接的byte数组
            val byteArray = ByteArray(pcmData.size * 2)
            for (i in pcmData.indices) {
                byteArray[i * 2] = (pcmData[i].toInt() and 0xFF).toByte()
                byteArray[i * 2 + 1] = (pcmData[i].toInt() shr 8 and 0xFF).toByte()
            }
            
            // 写入音频数据到AudioTrack
            val bytesWritten = audioTrack.write(byteArray, 0, byteArray.size, AudioTrack.WRITE_BLOCKING)
            if (bytesWritten < byteArray.size) {
                if (shouldLog("audiotrack_write_incomplete")) {
                    Log.w(TAG, "AudioTrack写入不完整: 预期${byteArray.size}字节，实际写入${bytesWritten}字节")
                }
            } else {
                // Log.d(TAG, "音频播放完成: ${pcmData.size}采样点，${bytesWritten}字节")
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放PCM数据失败: ${e.message}", e)
        }
    }

    // 播放Opus数据
    fun playOpusData(opusData: ByteArray) {
        // 检查播放器状态
        if (!isPlaying) {
            Log.e(TAG, "AudioPlayer未启动或已停止，无法播放数据")
            return
        }

        // 检查输入数据是否有效
        if (opusData.isEmpty()) {
            Log.d(TAG, "收到空Opus数据，忽略")
            return
        }

        try {
            // 将Opus数据发送到解码队列，使用trySend避免在通道关闭时抛出异常
            CoroutineScope(Dispatchers.Default).launch {
                opusDataChannel.trySend(opusData)
                // Log.d(TAG, "Opus数据已发送到解码队列: ${opusData.size}字节")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送Opus数据到队列失败: ${e.message}", e)
        }
    }

    // 停止播放
    fun stop() {
        if (!isPlaying) return

        try {
            isPlaying = false
            
            // 先取消协程，确保不再处理新数据
            decodingJob?.cancel()
            playingJob?.cancel()
            
            // 关闭通道，不再接受新数据
            opusDataChannel.close()
            pcmDataChannel.close()
            
            // 不再使用 runBlocking 阻塞线程，改为使用协程等待
            // 这样不会阻塞心跳定时器
            CoroutineScope(Dispatchers.Default).launch {
                kotlinx.coroutines.delay(50)
            }
            
            // 停止并释放AudioTrack，使用try-catch避免状态异常
            try {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            } catch (e: Exception) {
                Log.e(TAG, "释放AudioTrack失败: ${e.message}", e)
            }
            
            // 不要释放Opus解码器资源，因为它是单例的，可能被其他组件使用
            // opusCodec.releaseDecoder()
            
           // Log.d(TAG, "AudioPlayer停止播放，编解码器统计: ${opusCodec.getStats()}")
        } catch (e: Exception) {
            Log.e(TAG, "停止播放失败: ${e.message}", e)
        }
    }

    // 检查是否正在播放
    fun isPlaying(): Boolean {
        return isPlaying
    }
}