// WakeWordService.kt
package com.minrray.voicectl.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log

import com.k2fsa.sherpa.onnx.*
import com.k2fsa.sherpa.onnx.kws.model.KwsModelProvider

import com.minrray.voicectl.core.audio.AudioEnergyHolder
import com.minrray.voicectl.core.audio.AudioRecorder
import kotlinx.coroutines.*
import kotlin.math.sqrt

class WakeWordService : Service() {

    private val CHANNEL_ID = "WakeWordService"
    private val NOTIFICATION_ID = 1
    private val mainHandler = Handler(Looper.getMainLooper())

    private var keywordSpotter: KeywordSpotter? = null
    private var wakeWordStream: OnlineStream? = null
    private var audioRecorder: AudioRecorder? = null
    private var serviceJob: Job? = null
    private var isRunning = false
    
    // 保存唤醒词监听器引用，以便移除
    private var wakeWordListener: ((ShortArray) -> Unit)? = null

    // 能量计算相关
    private var energyUpdateJob: Job? = null
    private var lastEnergyUpdateTime: Long = 0
    private val ENERGY_UPDATE_INTERVAL = 50L // 每50ms更新一次能量

    // 安全关闭标志
    @Volatile
    private var isShuttingDown = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 初始化音频记录器
        audioRecorder = AudioRecorder.getInstance()

        Log.d("WakeWordService", "服务创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("WakeWordService", "服务启动")

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        // 初始化唤醒词检测
        initWakeWordDetection()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("WakeWordService", "服务销毁 - 开始清理")

        isShuttingDown = true

        // 1. 先停止所有协程
        serviceJob?.cancel()
        energyUpdateJob?.cancel()

        // 2. 停止监听
        stopListening()

        // 3. 释放资源
        releaseResources()

        Log.d("WakeWordService", "服务销毁 - 清理完成")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "唤醒词服务"
            val descriptionText = "监听唤醒词，随时准备响应"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }

            val notificationManager: NotificationManager =
                getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("唤醒词服务正在运行")
                .setContentText("监听中...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("唤醒词服务正在运行")
                .setContentText("监听中...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
    }

    private fun initWakeWordDetection() {
        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // 创建唤醒词检测器配置
                val kwsConfig = KeywordSpotterConfig()
                kwsConfig.modelConfig = KwsModelProvider.getModelConfig(KwsModelProvider.KwsModelType.WENETSPEECH_CHINESE) ?: return@launch
                kwsConfig.keywordsFile = KwsModelProvider.getKeywordsFile(KwsModelProvider.KwsModelType.WENETSPEECH_CHINESE)

                // 初始化唤醒词检测器
                keywordSpotter = KeywordSpotter(assets, kwsConfig)
                Log.d("WakeWordService", "唤醒词检测器初始化成功")

                // 获取关键词文件内容
                val keywordsFile = KwsModelProvider.getKeywordsFile(KwsModelProvider.KwsModelType.WENETSPEECH_CHINESE)
                val keywords = assets.open(keywordsFile).bufferedReader().use { it.readText() }
                Log.d("WakeWordService", "加载唤醒词: $keywords")

                // 创建唤醒词检测流，传入关键词
                wakeWordStream = keywordSpotter?.createStream(keywords)
                if (wakeWordStream == null) {
                    Log.d("WakeWordService", "创建唤醒词检测流失败")
                    return@launch
                }
                Log.d("WakeWordService", "创建唤醒词检测流成功")

                // 初始化完成后开始监听麦克风
                startListening()

            } catch (e: Exception) {
                Log.e("WakeWordService", "初始化唤醒词检测器异常: ${e.message}")
            }
        }
    }

    private fun startListening() {
        if (isRunning || isShuttingDown) return

        isRunning = true

        // 重置能量数据
        AudioEnergyHolder.reset()

        // 添加PCM数据监听器，将音频数据传递给唤醒词检测器
        wakeWordListener = { pcmData: ShortArray ->
            // 使用条件判断而不是直接返回
            if (!isShuttingDown && keywordSpotter != null && wakeWordStream != null) {
                try {
                    // 将PCM数据转换为float数组（范围[-1.0, 1.0]）
                    val floatData = FloatArray(pcmData.size) {
                        pcmData[it] / 32768.0f
                    }

                    // 将音频数据喂给唤醒词检测流
                    wakeWordStream?.acceptWaveform(floatData, 16000)

                    // 计算音频能量值（RMS - 均方根）
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastEnergyUpdateTime >= ENERGY_UPDATE_INTERVAL) {
                        var sumOfSquares = 0.0
                        for (sample in floatData) {
                            sumOfSquares += sample * sample
                        }
                        val rms = sqrt(sumOfSquares / floatData.size).toFloat()

                        // 更新音频能量（用于波浪动画）
                        AudioEnergyHolder.updateEnergy(rms)

                        lastEnergyUpdateTime = currentTime
                    }

                    // 当检测器准备好时进行解码
                    while (keywordSpotter?.isReady(wakeWordStream!!) == true && !isShuttingDown) {
                        keywordSpotter?.decode(wakeWordStream!!)
                        val result = keywordSpotter?.getResult(wakeWordStream!!)
                        if (result?.keyword?.isNotBlank() == true) {
                            // 检测到唤醒词时，重置能量显示
                            AudioEnergyHolder.reset()

                            // 播放唤醒音频
                            Mp3Player.getInstance().playFromAssets(this@WakeWordService, "wakeup.mp3")
                            Log.d("WakeWordService", "检测到唤醒词: ${result.keyword}")

                            // 延迟发送唤醒事件，确保wakeup.mp3播放完成（约1秒）
                            Thread.sleep(1000)

                            // 触发唤醒事件
                            triggerWakeEvent(result.keyword)

                            // 只重置检测流，不重启录音
                            resetDetectionStream()
                            Log.d("WakeWordService", "重置检测流，继续监听")

                            break
                        }
                    }
                } catch (e: Exception) {
                    if (!isShuttingDown) {
                        Log.e("WakeWordService", "处理PCM数据异常: ${e.message}")
                    }
                }
            }
        }
        
        // 添加监听器
        wakeWordListener?.let { listener ->
            audioRecorder?.addPcmDataListener(listener)
        }

        // 启动AudioRecorder
        try {
            // 如果录音已经在运行且已启用Opus编码，保持Opus编码状态
            // 否则启动录音但不启用Opus编码（唤醒词服务只需要PCM数据）
            if (audioRecorder?.isRecording == true && audioRecorder?.isOpusEncodingEnabled == true) {
                audioRecorder?.start(enableOpusEncoding = true)
                Log.d("WakeWordService", "开始监听麦克风，保持Opus编码状态")
            } else {
                audioRecorder?.start(enableOpusEncoding = false)
                Log.d("WakeWordService", "开始监听麦克风，不启用Opus编码")
            }
            Log.d("WakeWordService", "波浪动画已启用")

            // 启动能量更新任务
            startEnergyUpdateTask()

        } catch (e: Exception) {
            Log.e("WakeWordService", "启动AudioRecorder异常: ${e.message}")
        }
    }

    private fun startEnergyUpdateTask() {
        if (isShuttingDown) return

        energyUpdateJob?.cancel()

        energyUpdateJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isRunning && !isShuttingDown) {
                try {
                    // 定期发送低能量值以保持动画活跃（即使没有声音）
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastEnergyUpdateTime > 500) { // 500ms没有更新
                        AudioEnergyHolder.updateEnergy(0.001f) // 发送微小能量保持动画
                    }

                    delay(100) // 每100ms检查一次
                } catch (e: Exception) {
                    if (!isShuttingDown) {
                        Log.e("WakeWordService", "能量更新任务异常: ${e.message}")
                    }
                }
            }
        }
    }

    private fun stopListening() {
        if (!isRunning || isShuttingDown) return

        isRunning = false

        // 停止能量更新任务
        energyUpdateJob?.cancel()

        // 移除唤醒词监听器
        wakeWordListener?.let {
            audioRecorder?.removePcmDataListener(it)
            wakeWordListener = null
        }

        Log.d("WakeWordService", "停止监听麦克风")
    }

    private fun triggerWakeEvent(keyword: String) {
        if (isShuttingDown) return

        // 发送广播，通知主程序唤醒词被检测到
        // 使用显式Intent，指定Action和包名，确保在Android 14+上能被RECEIVER_NOT_EXPORTED接收器接收
        val intent = Intent("com.minrray.voicectl.WAKE_WORD_DETECTED")
        intent.putExtra("keyword", keyword)
        intent.setPackage(packageName) // 显式指定包名
        sendBroadcast(intent)

        Log.d("WakeWordService", "发送唤醒广播: $keyword")
    }

    private fun resetDetectionStream() {
        if (isShuttingDown) return

        // 释放旧的流
        wakeWordStream?.release()

        // 创建新的检测流
        try {
            val keywordsFile = KwsModelProvider.getKeywordsFile(KwsModelProvider.KwsModelType.WENETSPEECH_CHINESE)
            val keywords = assets.open(keywordsFile).bufferedReader().use { it.readText() }
            wakeWordStream = keywordSpotter?.createStream(keywords)
            Log.d("WakeWordService", "重置唤醒词检测流成功")
        } catch (e: Exception) {
            Log.e("WakeWordService", "重置唤醒词检测流失败: ${e.message}")
        }
    }

    private fun releaseResources() {
        if (isShuttingDown) return

        // 停止能量更新任务
        energyUpdateJob?.cancel()

        // 释放唤醒词检测流
        wakeWordStream?.release()
        wakeWordStream = null

        // 释放唤醒词检测器
        keywordSpotter?.release()
        keywordSpotter = null

        Log.d("WakeWordService", "释放资源")
    }
}