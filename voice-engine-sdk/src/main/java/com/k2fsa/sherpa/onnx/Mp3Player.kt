package com.k2fsa.sherpa.onnx

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.util.Log

private const val TAG = "Mp3Player"

class Mp3Player private constructor() {
    companion object {
        private lateinit var instance: Mp3Player
        fun getInstance(): Mp3Player {
            if (!::instance.isInitialized) {
                instance = Mp3Player()
            }
            return instance
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private var isReleased = false
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    /**
     * 播放assets文件夹中的MP3文件
     * @param context 上下文
     * @param fileName assets文件夹中的MP3文件名
     */
    fun playFromAssets(context: Context, fileName: String) {
        // 检查是否已经释放
        if (isReleased) {
            Log.e(TAG, "Mp3Player已经释放，无法播放音频")
            return
        }

        try {
            // 释放之前的MediaPlayer实例
            releaseCurrentMediaPlayer()

            // 获取音频管理器
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 请求音频焦点
            if (requestAudioFocus()) {
                // 创建新的MediaPlayer实例
                val newMediaPlayer = MediaPlayer()
                mediaPlayer = newMediaPlayer

                // 配置音频属性 - 使用音乐流类型，确保有声音
                newMediaPlayer.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC) // 使用音乐类型
                        .setUsage(AudioAttributes.USAGE_MEDIA) // 使用媒体用途
                        .build()
                )

                // 设置音量为最大
                newMediaPlayer.setVolume(1.0f, 1.0f)

                // 设置数据源为assets文件夹中的文件
                val assetFileDescriptor = context.assets.openFd(fileName)
                newMediaPlayer.setDataSource(
                    assetFileDescriptor.fileDescriptor,
                    assetFileDescriptor.startOffset,
                    assetFileDescriptor.length
                )
                assetFileDescriptor.close()

                // 设置播放完成监听器
                newMediaPlayer.setOnCompletionListener {
                    Log.d(TAG, "播放完成: $fileName")
                    abandonAudioFocus()
                    releaseCurrentMediaPlayer()
                }

                // 设置错误监听器
                newMediaPlayer.setOnErrorListener { mp, what, extra ->
                    Log.e(TAG, "播放错误: what=$what, extra=$extra")
                    abandonAudioFocus()
                    releaseCurrentMediaPlayer()
                    true
                }

                // 设置准备完成监听器
                newMediaPlayer.setOnPreparedListener {
                    Log.d(TAG, "MediaPlayer准备完成，开始播放: $fileName")
                    try {
                        it.start()
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "开始播放失败: ${e.message}", e)
                        abandonAudioFocus()
                        releaseCurrentMediaPlayer()
                    }
                }

                // 异步准备，避免阻塞主线程
                newMediaPlayer.prepareAsync()

                Log.d(TAG, "MediaPlayer创建成功，开始异步准备: $fileName")
            } else {
                Log.e(TAG, "请求音频焦点失败，无法播放音频")
            }
        } catch (e: Exception) {
            Log.e(TAG, "播放MP3文件失败: ${e.message}", e)
            abandonAudioFocus()
            releaseCurrentMediaPlayer()
        }
    }

    /**
     * 请求音频焦点
     * @return 是否成功获取音频焦点
     */
    private fun requestAudioFocus(): Boolean {
        if (audioManager == null) {
            return false
        }

        try {
            val focusGain = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0及以上，使用AudioFocusRequest
                val focusRequest = AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    .setOnAudioFocusChangeListener { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                // 失去焦点，停止播放
                                Log.d(TAG, "失去音频焦点，停止播放")
                                abandonAudioFocus()
                                releaseCurrentMediaPlayer()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                // 暂时失去焦点，暂停播放
                                Log.d(TAG, "暂时失去音频焦点，暂停播放")
                                pauseMediaPlayer()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                // 可以降低音量继续播放
                                Log.d(TAG, "音频焦点降低，降低音量")
                                duckMediaPlayer()
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                // 重新获得焦点，继续播放
                                Log.d(TAG, "重新获得音频焦点，继续播放")
                                resumeMediaPlayer()
                            }
                        }
                    }
                    .build()
                
                audioFocusRequest = focusRequest
                val result = audioManager!!.requestAudioFocus(focusRequest)
                hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            } else {
                // Android 8.0以下，使用旧的API
                val result = audioManager!!.requestAudioFocus(
                    { focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS -> {
                                Log.d(TAG, "失去音频焦点，停止播放")
                                abandonAudioFocus()
                                releaseCurrentMediaPlayer()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                Log.d(TAG, "暂时失去音频焦点，暂停播放")
                                pauseMediaPlayer()
                            }
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                                Log.d(TAG, "音频焦点降低，降低音量")
                                duckMediaPlayer()
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                Log.d(TAG, "重新获得音频焦点，继续播放")
                                resumeMediaPlayer()
                            }
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    focusGain
                )
                hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            }
            
            Log.d(TAG, "请求音频焦点结果: $hasAudioFocus")
            return hasAudioFocus
        } catch (e: Exception) {
            Log.e(TAG, "请求音频焦点失败: ${e.message}", e)
            return false
        }
    }

    /**
     * 放弃音频焦点
     */
    private fun abandonAudioFocus() {
        try {
            if (audioManager != null && hasAudioFocus) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let {
                        audioManager!!.abandonAudioFocusRequest(it)
                    }
                } else {
                    audioManager!!.abandonAudioFocus(null)
                }
                hasAudioFocus = false
                audioFocusRequest = null
                Log.d(TAG, "已放弃音频焦点")
            }
        } catch (e: Exception) {
            Log.e(TAG, "放弃音频焦点失败: ${e.message}", e)
        }
    }

    /**
     * 暂停MediaPlayer
     */
    private fun pauseMediaPlayer() {
        try {
            mediaPlayer?.let {
                if (isMediaPlayerPlaying(it)) {
                    it.pause()
                    Log.d(TAG, "MediaPlayer已暂停")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "暂停MediaPlayer失败: ${e.message}", e)
        }
    }

    /**
     * 恢复MediaPlayer播放
     */
    private fun resumeMediaPlayer() {
        try {
            mediaPlayer?.let {
                if (!isMediaPlayerPlaying(it)) {
                    it.start()
                    Log.d(TAG, "MediaPlayer已恢复播放")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "恢复MediaPlayer播放失败: ${e.message}", e)
        }
    }

    /**
     * 降低MediaPlayer音量（duck模式）
     */
    private fun duckMediaPlayer() {
        try {
            mediaPlayer?.setVolume(0.3f, 0.3f) // 降低到30%音量
            Log.d(TAG, "MediaPlayer音量已降低")
        } catch (e: Exception) {
            Log.e(TAG, "降低MediaPlayer音量失败: ${e.message}", e)
        }
    }

    /**
     * 释放当前的MediaPlayer资源
     */
    private fun releaseCurrentMediaPlayer() {
        try {
            val tempMediaPlayer = mediaPlayer
            if (tempMediaPlayer != null) {
                // 置空引用，防止并发问题
                mediaPlayer = null
                
                // 移除所有监听器，避免内存泄漏
                tempMediaPlayer.setOnCompletionListener(null)
                tempMediaPlayer.setOnErrorListener(null)
                tempMediaPlayer.setOnPreparedListener(null)
                
                try {
                    // 尝试停止播放（如果正在播放）
                    if (isMediaPlayerPlaying(tempMediaPlayer)) {
                        tempMediaPlayer.stop()
                    }
                    // 重置MediaPlayer
                    tempMediaPlayer.reset()
                } catch (e: IllegalStateException) {
                    // 如果MediaPlayer已经处于无效状态，忽略
                    Log.w(TAG, "MediaPlayer状态异常，跳过stop/reset: ${e.message}")
                } finally {
                    // 无论如何都要释放资源
                    try {
                        tempMediaPlayer.release()
                        Log.d(TAG, "MediaPlayer资源释放成功")
                    } catch (releaseException: Exception) {
                        Log.e(TAG, "释放MediaPlayer失败: ${releaseException.message}", releaseException)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "释放当前MediaPlayer失败: ${e.message}", e)
            mediaPlayer = null
        }
    }

    /**
     * 安全检查MediaPlayer是否正在播放
     * @param mp MediaPlayer实例
     * @return 是否正在播放
     */
    private fun isMediaPlayerPlaying(mp: MediaPlayer): Boolean {
        return try {
            mp.isPlaying
        } catch (e: IllegalStateException) {
            // 如果MediaPlayer处于无效状态，返回false
            Log.w(TAG, "检查MediaPlayer播放状态失败: ${e.message}")
            false
        }
    }

    /**
     * 释放Mp3Player资源
     */
    fun release() {
        if (isReleased) {
            Log.w(TAG, "Mp3Player已经释放，无需重复释放")
            return
        }
        
        try {
            // 放弃音频焦点
            abandonAudioFocus()
            // 释放当前MediaPlayer
            releaseCurrentMediaPlayer()
            // 标记为已释放
            isReleased = true
            audioManager = null
            Log.d(TAG, "Mp3Player资源释放成功")
        } catch (e: Exception) {
            Log.e(TAG, "释放Mp3Player失败: ${e.message}", e)
            isReleased = true
            mediaPlayer = null
            audioManager = null
        }
    }
}
