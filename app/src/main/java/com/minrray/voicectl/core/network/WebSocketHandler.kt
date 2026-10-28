package com.minrray.voicectl.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import com.minrray.voicectl.core.audio.AudioPlayer
import com.minrray.voicectl.core.audio.AudioRecorder
import com.minrray.voicectl.core.audio.OpusCodec
import com.minrray.voicectl.core.service.FloatingWindowService
import com.minrray.voicectl.core.face.FaceRecognitionManager
import com.minrray.voicectl.core.activechat.ActiveChatManager

class WebSocketHandler private constructor() {
    companion object {
        private const val TAG = "WebSocketHandler"
        private lateinit var instance: WebSocketHandler
        fun getInstance(): WebSocketHandler {
            if (!::instance.isInitialized) {
                instance = WebSocketHandler()
            }
            return instance
        }
    }

    private var webSocket: WebSocket? = null
    private var client: OkHttpClient? = null
    private var serverUrl: String = ""
    private var isConnected = AtomicBoolean(false)
    private var currentSessionId: String = ""
    private var isRemoteSpeaking = false
    private var deviceConfig: DeviceConfig? = null
    private val audioPlayer = AudioPlayer.getInstance()
    private val audioRecorder = AudioRecorder.getInstance()

    // 主线程Handler，用于UI更新
    private val mainHandler = Handler(Looper.getMainLooper())

    // 心跳相关
    private var heartbeatTimer: java.util.Timer? = null
    private val heartbeatInterval = 30000L // 30秒发送一次心跳
    private var lastHeartbeatTime = 0L
    private var lastMessageReceivedTime = 0L
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private val reconnectDelay = arrayOf(1000L, 2000L, 5000L, 10000L, 30000L)
    private var lastReconnectTime = 0L
    private val reconnectCooldown = 10000L

    // 连接健康度检测
    private var healthCheckTimer: java.util.Timer? = null
    private val healthCheckInterval = 10000L // 10秒检查一次连接健康度
    private val connectionTimeout = 45000L // 45秒无消息视为连接超时

    // 网络状态监听
    private var networkAvailable = true

    // 日志限流相关
    private val logThrottleMap = HashMap<String, Long>()
    private val logThrottleInterval = 2000L

    // 音频发送控制相关
    private var lastFaceDetectedTime = -1L // 初始化为-1，表示从未检测到人脸
    private val faceDetectionTimeout = 5000L // 5秒内必须检测到人脸
    private var audioEnergyLevel = 0.0f
    private val energyThreshold = 1000.0f // 音频能量阈值
    private val opusCodec = OpusCodec.getInstance()
    private val frameSize = 960 // 960个采样点/帧，16kHz采样率
    private var hasRegisteredFaces = false // 是否已注册人脸

    // Opus音频缓存队列，用于缓存最近1秒的录音数据（约17帧）
    private val opusAudioCache = ArrayDeque<ByteArray>(20)

    // 控制是否发送实时音频的标志位
    private var isSendingRealTimeAudio = false

    // 缓存更新和发送的互斥锁
    private val cacheLock = Any()

    // 设备配置数据类
    data class DeviceConfig(
        val deviceId: String,
        val deviceName: String,
        val token: String,
        val otaUrl: String = "",
        val serverUrl: String = ""
    )

    // 回调接口
    var onConnectionStateChange: ((Boolean) -> Unit)? = null
    var onRecordButtonStateChange: ((Boolean) -> Unit)? = null
    var onSessionStateChange: ((Boolean?) -> Unit)? = null
    var onSessionEmotionChange: ((String) -> Unit)? = null
    var onMessageReceived: ((String) -> Unit)? = null
    var onTextMessage: ((String, Boolean) -> Unit)? = null
    var onConnectionHealthChange: ((Boolean) -> Unit)? = null // 连接健康度回调
    var onMeetingQuery: ((java.util.Date?) -> Unit)? = null // 会议查询回调，参数为目标日期

    // 初始化WebSocket客户端 - 优化版本
    private fun initClient() {
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS) // 增加连接超时
            .readTimeout(60, TimeUnit.SECONDS)    // 增加读取超时
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)   // 启用Ping心跳（OkHttp自动处理）
            .retryOnConnectionFailure(true)       // 启用连接失败重试
            .build()
    }

    init {
        // 在构造函数中初始化AudioRecorder回调
        initAudioRecorderCallbacks()
    }

    // 初始化AudioRecorder回调
    private fun initAudioRecorderCallbacks() {
        Log.d(TAG, "initAudioRecorderCallbacks: enter")
        audioRecorder.onRecordingStart = {
            Log.d(TAG, "initAudioRecorderCallbacks 录音开始")
            audioEnergyLevel = 0.0f
           // sendListenStartMessage()
        }

        audioRecorder.onRecordingStop = {
            Log.d(TAG, "录音停止")
            audioEnergyLevel = 0.0f
          //  sendListenStopMessage()
        }

        audioRecorder.onOpusDataAvailable = { opusData ->
            if (opusData.isNotEmpty()) {
                sendOpusData(opusData)
            } else {
                Log.d(TAG, "收到空的Opus数据，跳过发送")
            }
        }

        audioRecorder.addPcmDataListener { pcmData ->
            checkAudioQuality(pcmData)
        }
    }

    // 更新人脸检测时间
    fun updateFaceDetectedTime() {
        lastFaceDetectedTime = System.currentTimeMillis()
       // Log.d(TAG, "更新人脸检测时间: ${System.currentTimeMillis()}")
    }

    // 更新人脸注册状态
    fun updateFaceRegistrationStatus(hasFaces: Boolean) {
    hasRegisteredFaces = hasFaces
    Log.d(TAG, "更新人脸注册状态: hasRegisteredFaces = $hasRegisteredFaces")
    
    if (hasFaces) {
        val service = FloatingWindowService.getInstance()
        service?.let {
            try {
                it.activeChatManager.resetGreetingDate()
                Log.d(TAG, "人脸注册成功，重置问候日期")
            } catch (e: Exception) {
                Log.w(TAG, "重置问候日期失败: ${e.message}")
            }
        }
    }
}

    // 检查音频质量
    private fun checkAudioQuality(pcmData: ShortArray) {
        val energy = calculateAudioEnergy(pcmData)
        audioEnergyLevel = energy
    }

    // 计算音频能量
    private fun calculateAudioEnergy(pcmData: ShortArray): Float {
        var sum = 0.0
        for (sample in pcmData) {
            sum += sample * sample
        }
        return (sum / pcmData.size).toFloat()
    }

    // 检查是否满足发送条件
    private fun shouldSendAudioData(): Boolean {
        val currentTime = System.currentTimeMillis()
        
        // 如果没有注册过人脸，直接返回true
        if (!hasRegisteredFaces) {
         //   Log.d(TAG, "无注册人脸直接发送: hasRegisteredFaces=$hasRegisteredFaces")
            return true
        }
        
        // 已经注册过人脸的，必须满足以下条件才发送音频数据
        val lastRegisteredFaceTime = FloatingWindowService.getLastRegisteredFaceTime()
        val lastFacingCameraTime = FloatingWindowService.getLastFacingCameraTime()
        val lastLipMovementTime = FloatingWindowService.getLastLipMovementTime()
        val autoListenTimeWindow = FloatingWindowService.getAutoListenTimeWindow()
        
        val hasRegisteredFace = (currentTime - lastRegisteredFaceTime) <= autoListenTimeWindow
        val isFacingCamera = (currentTime - lastFacingCameraTime) <= autoListenTimeWindow
        val hasLipMovement = (currentTime - lastLipMovementTime) <= autoListenTimeWindow
        
        val result = hasRegisteredFace && isFacingCamera && hasLipMovement
        
        if (!result) {
         //   Log.d(TAG, "已注册人脸但不满足发送条件: registeredFace=$hasRegisteredFace, facingCamera=$isFacingCamera, lipMovement=$hasLipMovement")
        }
        
        return result
    }

    // 生成静音Opus数据
    private fun generateSilenceOpus(): ByteArray {
        val silencePcm = ShortArray(frameSize) { 0 }
        return opusCodec.encode(silencePcm)
    }

    // 启动心跳检测
    private fun startHeartbeat() {
        stopHeartbeat() // 先停止之前的心跳

        heartbeatTimer = java.util.Timer(true)
        heartbeatTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (isConnected.get()) {
                    sendHeartbeat()
                }
            }
        }, heartbeatInterval, heartbeatInterval)

        Log.d(TAG, "心跳检测已启动")
    }

    // 停止心跳检测
    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
        Log.d(TAG, "心跳检测已停止")
    }

    // 发送心跳消息
    private fun sendHeartbeat() {
        if (!isConnected.get() || webSocket == null) return

        try {
            val now = System.currentTimeMillis()

            // 检查距离上次收到消息的时间
            if (lastMessageReceivedTime > 0 && now - lastMessageReceivedTime > connectionTimeout) {
                if (shouldLog("heartbeat_timeout")) {
                    Log.w(TAG, "长时间未收到服务器消息(${now - lastMessageReceivedTime}ms)，可能连接已断开")
                }
                mainHandler.post {
                    onConnectionHealthChange?.invoke(false)
                }
                scheduleReconnect()
                return
            }

            // 检查距离上次心跳的时间
            if (now - lastHeartbeatTime > heartbeatInterval * 2) {
                if (shouldLog("heartbeat_interval_timeout")) {
                    Log.w(TAG, "心跳间隔过长(${now - lastHeartbeatTime}ms)，可能连接已断开")
                }
                mainHandler.post {
                    onConnectionHealthChange?.invoke(false)
                }
                scheduleReconnect()
                return
            }

            val heartbeatMessage = JSONObject()
            heartbeatMessage.put("type", "heartbeat")
            heartbeatMessage.put("timestamp", now)

            webSocket?.send(heartbeatMessage.toString())
            lastHeartbeatTime = now
            if (shouldLog("send_heartbeat")) {
             //   Log.d(TAG, "发送心跳消息")
            }
        } catch (e: Exception) {
            if (shouldLog("heartbeat_send_error")) {
                Log.e(TAG, "发送心跳消息失败: ${e.message}", e)
            }
            mainHandler.post {
                onConnectionHealthChange?.invoke(false)
            }
            // 心跳发送失败，尝试重连
            scheduleReconnect()
        }
    }

    // 启动连接健康度检查
    private fun startHealthCheck() {
        stopHealthCheck()

        lastMessageReceivedTime = System.currentTimeMillis()

        healthCheckTimer = java.util.Timer(true)
        healthCheckTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (isConnected.get()) {
                    checkConnectionHealth()
                }
            }
        }, healthCheckInterval, healthCheckInterval)

        Log.d(TAG, "连接健康度检查已启动")
    }

    // 停止连接健康度检查
    private fun stopHealthCheck() {
        healthCheckTimer?.cancel()
        healthCheckTimer = null
        Log.d(TAG, "连接健康度检查已停止")
    }

    // 检查连接健康度
    private fun checkConnectionHealth() {
        val now = System.currentTimeMillis()
        val timeSinceLastMessage = now - lastMessageReceivedTime

        if (timeSinceLastMessage > connectionTimeout) {
            Log.w(TAG, "连接健康度检查: 长时间未收到消息(${timeSinceLastMessage}ms)")
            mainHandler.post {
                onConnectionHealthChange?.invoke(false)
            }
            // 可以尝试发送一个测试消息
            sendTestMessage()
        } else if (timeSinceLastMessage > connectionTimeout / 2) {
            if (shouldLog("connection_health_delay")) {
                Log.d(TAG, "连接健康度检查: 消息接收延迟(${timeSinceLastMessage}ms)")
            }
            mainHandler.post {
                onConnectionHealthChange?.invoke(true)
            }
        } else {
            mainHandler.post {
                onConnectionHealthChange?.invoke(true)
            }
        }
    }

    // 发送测试消息
    private fun sendTestMessage() {
        if (!isConnected.get() || webSocket == null) return

        try {
            val testMessage = JSONObject()
            testMessage.put("type", "ping")
            testMessage.put("timestamp", System.currentTimeMillis())

            webSocket?.send(testMessage.toString())
            Log.d(TAG, "发送连接测试消息")
        } catch (e: Exception) {
            Log.e(TAG, "发送测试消息失败: ${e.message}", e)
            scheduleReconnect()
        }
    }

    // 检查网络状态
    private fun isNetworkAvailable(context: Context?): Boolean {
        if (context == null) return false

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // 发送Opus数据
    fun sendOpusData(data: ByteArray): Boolean {
        if (!isConnected.get() || webSocket == null) {
            if(shouldLog("WebSocket未连接"))
                Log.e(TAG, "WebSocket未连接，无法发送Opus数据")
            return false
        }

        try {
            synchronized(cacheLock) {
                // 缓存音频数据（保持最近1秒，约17帧）
                opusAudioCache.add(data)
                if (opusAudioCache.size > 32) {
                    opusAudioCache.removeFirst()
                }
            }

            // 只有在允许发送实时音频时才发送
            if (!isSendingRealTimeAudio) {
                return false
            }

            if (data.isNotEmpty()) {
               // Log.d(TAG, "发送Opus数据")
                webSocket?.send(ByteString.of(*data))
                return true
            } else {
                Log.d(TAG, "跳过发送空的Opus数据")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送Opus数据失败: ${e.message}", e)
            scheduleReconnect()
            return false
        }
    }

    // 发送开始监听消息
    fun sendListenStartMessage() {
        if (!isConnected.get() || webSocket == null) return

        try {
            // 实时查询人脸注册状态
            val hasFaces = try {
                val service = FloatingWindowService.getInstance()
                if (service != null) {
                    val faceManager = FaceRecognitionManager.getInstance(service)
                    faceManager.hasRegisteredFaces()
                } else {
                    Log.w(TAG, "FloatingWindowService实例为空，使用缓存的hasRegisteredFaces")
                    hasRegisteredFaces
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询人脸注册状态失败，使用缓存的hasRegisteredFaces: ${e.message}")
                hasRegisteredFaces
            }

            val listenMode = if (hasFaces) "manual" else "auto"
            val listenMessage = JSONObject()
            listenMessage.put("type", "listen")
            listenMessage.put("mode", listenMode)
            listenMessage.put("state", "start")

            Log.d(TAG, "发送开始监听消息: mode=$listenMode, hasFaces=$hasFaces")
            webSocket?.send(listenMessage.toString())

            // 在锁保护下复制缓存数据，防止发送时缓存被更新
            val cachedDataCopy: List<ByteArray> = synchronized(cacheLock) {
                opusAudioCache.toList()
            }

            // 发送缓存的1秒音频数据（只发一遍，防止起始语音丢失）
            if (cachedDataCopy.isNotEmpty()) {
                Log.d(TAG, "发送缓存的${cachedDataCopy.size}帧Opus音频数据")
                for (cachedOpus in cachedDataCopy) {
                    webSocket?.send(ByteString.of(*cachedOpus))
                }
            }

            // 开始发送实时音频
            isSendingRealTimeAudio = true
            Log.d(TAG, "开始发送实时音频")
        } catch (e: Exception) {
            Log.e(TAG, "发送开始监听消息失败: ${e.message}", e)
        }
    }

    // 发送停止监听消息
    fun sendListenStopMessage() {
        if (!isConnected.get() || webSocket == null) return

        try {
            // 先停止发送实时音频
            isSendingRealTimeAudio = false
            Log.d(TAG, "停止发送实时音频")

            // 等待一小段时间，确保所有正在发送的实时音频数据都已完成
            Thread.sleep(100)

            // 发送1秒的空Opus数据（约17帧），告知服务端ASR语音结束
            val silenceOpus = generateSilenceOpus()
            Log.d(TAG, "发送1秒空Opus数据（17帧）")
            for (i in 1..17) {
                webSocket?.send(ByteString.of(*silenceOpus))
            }

            // 实时查询人脸注册状态
            val hasFaces = try {
                val service = FloatingWindowService.getInstance()
                if (service != null) {
                    val faceManager = FaceRecognitionManager.getInstance(service)
                    faceManager.hasRegisteredFaces()
                } else {
                    Log.w(TAG, "FloatingWindowService实例为空，使用缓存的hasRegisteredFaces")
                    hasRegisteredFaces
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询人脸注册状态失败，使用缓存的hasRegisteredFaces: ${e.message}")
                hasRegisteredFaces
            }

            val listenMode = if (hasFaces) "manual" else "auto"
            val listenMessage = JSONObject()
            listenMessage.put("type", "listen")
            listenMessage.put("mode", listenMode)
            listenMessage.put("state", "stop")

            Log.d(TAG, "发送停止监听消息: mode=$listenMode, hasFaces=$hasFaces")
            webSocket?.send(listenMessage.toString())
        } catch (e: Exception) {
            Log.e(TAG, "发送停止监听消息失败: ${e.message}", e)
        }
    }

    // 发送会议开始消息
    fun sendMeetingStartMessage() {
        if (!isConnected.get() || webSocket == null) {
            Log.w(TAG, "WebSocket未连接，无法发送会议开始消息")
            return
        }

        try {
            val meetingMessage = JSONObject()
            meetingMessage.put("type", "listen")
            meetingMessage.put("mode", "meeting")
            meetingMessage.put("state", "start")

            Log.d(TAG, "发送会议开始消息")
            webSocket?.send(meetingMessage.toString())

            // 在锁保护下复制缓存数据，防止发送时缓存被更新
            val cachedDataCopy: List<ByteArray> = synchronized(cacheLock) {
                opusAudioCache.toList()
            }

            // 发送缓存的1秒音频数据（只发一遍，防止起始语音丢失）
            if (cachedDataCopy.isNotEmpty()) {
                Log.d(TAG, "发送缓存的${cachedDataCopy.size}帧Opus音频数据")
                for (cachedOpus in cachedDataCopy) {
                    webSocket?.send(ByteString.of(*cachedOpus))
                }
            }

            // 开始发送实时音频
            isSendingRealTimeAudio = true
            Log.d(TAG, "会议模式：开始发送实时音频")
        } catch (e: Exception) {
            Log.e(TAG, "发送会议开始消息失败: ${e.message}", e)
        }
    }

    // 发送会议结束消息
    fun sendMeetingEndMessage() {
        if (!isConnected.get() || webSocket == null) {
            Log.w(TAG, "WebSocket未连接，无法发送会议结束消息")
            return
        }

        try {
            // 先停止发送实时音频
            isSendingRealTimeAudio = false
            Log.d(TAG, "会议模式：停止发送实时音频")

            // 等待一小段时间，确保所有正在发送的实时音频数据都已完成
            Thread.sleep(100)

            // 发送1秒的空Opus数据（约17帧），告知服务端ASR语音结束
            val silenceOpus = generateSilenceOpus()
            Log.d(TAG, "发送1秒空Opus数据（17帧）")
            for (i in 1..17) {
                webSocket?.send(ByteString.of(*silenceOpus))
            }

            val meetingMessage = JSONObject()
            meetingMessage.put("type", "listen")
            meetingMessage.put("mode", "meeting")
            meetingMessage.put("state", "end")

            Log.d(TAG, "发送会议结束消息")
            webSocket?.send(meetingMessage.toString())
        } catch (e: Exception) {
            Log.e(TAG, "发送会议结束消息失败: ${e.message}", e)
        }
    }

    // 设置设备配置
    fun setDeviceConfig(config: DeviceConfig) {
        deviceConfig = config
    }

    // 连接到WebSocket服务器
    fun connect(url: String) {
        if (isConnected.get()) {
            Log.d(TAG, "已经连接到服务器，跳过重复连接")
            return
        }

        if (deviceConfig == null) {
            Log.e(TAG, "deviceConfig为空，无法连接到服务器")
            return
        }

        serverUrl = url
        initClient()

        Log.d(TAG, "开始连接WebSocket服务器: $url, deviceConfig=${deviceConfig}")

        val request = Request.Builder()
            .url(url)
            .addHeader("Connection", "Upgrade")
            .addHeader("Upgrade", "websocket")
            .addHeader("Sec-WebSocket-Version", "13")
            .addHeader("Cache-Control", "no-cache")
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket连接成功")
                this@WebSocketHandler.webSocket = webSocket
                isConnected.set(true)
                reconnectAttempts = 0
                lastHeartbeatTime = System.currentTimeMillis()
                lastMessageReceivedTime = System.currentTimeMillis()

                mainHandler.post {
                    onConnectionStateChange?.invoke(true)
                    onConnectionHealthChange?.invoke(true)
                }

                isRemoteSpeaking = false
                mainHandler.post {
                    onSessionStateChange?.invoke(false)
                }

                Log.d(TAG, "准备发送hello消息，deviceConfig=${deviceConfig}")
                sendHelloMessage()

                startHeartbeat()
                startHealthCheck()

                //sendListenStartMessage()
                
                // 重新启用Opus编码
                if (audioRecorder.isRecording) {
                    Log.d(TAG, "重新启用Opus编码")
                    audioRecorder.start(enableOpusEncoding = true)
                } else {
                    Log.d(TAG, "录音未启动，跳过Opus编码启用")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (shouldLog("websocket_text_message")) {
                    Log.d(TAG, "收到WebSocket文本消息")
                }
                // 更新消息接收时间
                lastMessageReceivedTime = System.currentTimeMillis()
                mainHandler.post {
                    onConnectionHealthChange?.invoke(true)
                }

                // 确保消息监听器被调用
                mainHandler.post {
                    onMessageReceived?.invoke(text)
                }
                // 解析消息并处理
                handleTextMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 更新消息接收时间
                lastMessageReceivedTime = System.currentTimeMillis()
                mainHandler.post {
                    onConnectionHealthChange?.invoke(true)
                }

                // 处理二进制消息（语音流）
                handleBinaryMessage(bytes)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket关闭中: $code, $reason")
                isConnected.set(false)
                mainHandler.post {
                    onConnectionStateChange?.invoke(false)
                    onConnectionHealthChange?.invoke(false)
                }

                // 停止心跳和健康检查
                stopHeartbeat()
                stopHealthCheck()

                // 尝试优雅关闭
                webSocket.close(1000, null)

                // 如果不是正常关闭，尝试重连
                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket已关闭: $code, $reason")
                isConnected.set(false)
                mainHandler.post {
                    onConnectionStateChange?.invoke(false)
                    onConnectionHealthChange?.invoke(false)
                }

                // 停止心跳和健康检查
                stopHeartbeat()
                stopHealthCheck()

                // 如果不是正常关闭，尝试重连
                if (code != 1000) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket连接失败: ${t.message}")
                isConnected.set(false)
                mainHandler.post {
                    onConnectionStateChange?.invoke(false)
                    onConnectionHealthChange?.invoke(false)
                }

                // 停止心跳和健康检查
                stopHeartbeat()
                stopHealthCheck()

                // 尝试重连
                scheduleReconnect()
            }
        }

        webSocket = client?.newWebSocket(request, listener)
    }

    // 重连机制
    private fun reconnect() {
        if (!shouldReconnect) {
            Log.d(TAG, "重连已禁用，停止重连")
            return
        }

        val delay = if (reconnectAttempts < reconnectDelay.size) {
            reconnectDelay[reconnectAttempts]
        } else {
            reconnectDelay.last()
        }

        reconnectAttempts++
        Log.d(TAG, "准备第${reconnectAttempts}次重连，延迟${delay}ms")

        mainHandler.postDelayed({
            if (!isConnected.get() && shouldReconnect) {
                Log.d(TAG, "执行第${reconnectAttempts}次重连")
                disconnectInternal()
                connect(serverUrl)
            }
        }, delay)
    }

    // 带冷却时间的重连机制
    private fun scheduleReconnect() {
        val now = System.currentTimeMillis()
        
        // 检查是否在冷却时间内
        if (now - lastReconnectTime < reconnectCooldown) {
            val remainingCooldown = reconnectCooldown - (now - lastReconnectTime)
            if (shouldLog("reconnect_cooldown")) {
                Log.d(TAG, "重连冷却中，还需等待${remainingCooldown}ms，将在冷却后自动重连")
            }
            // 在冷却时间结束后自动重连
            mainHandler.postDelayed({
                if (!isConnected.get() && shouldReconnect) {
                    reconnect()
                }
            }, remainingCooldown)
            return
        }
        
        lastReconnectTime = now
        reconnect()
    }

    // 内部断开连接方法
    private fun disconnectInternal() {
        webSocket?.close(1000, "准备重连")
        webSocket = null
        isConnected.set(false)
    }

    // 断开WebSocket连接
    fun disconnect() {
        Log.d(TAG, "主动断开WebSocket连接")
        shouldReconnect = false
        reconnectAttempts = 0

        stopHeartbeat()
        stopHealthCheck()

        webSocket?.close(1000, "用户主动断开")
        webSocket = null
        isConnected.set(false)

        mainHandler.post {
            onConnectionStateChange?.invoke(false)
            onConnectionHealthChange?.invoke(false)
        }

        audioPlayer.stop()
    }

    // 重置重连计数
    fun resetReconnectAttempts() {
        reconnectAttempts = 0
        shouldReconnect = true
        Log.d(TAG, "已重置重连计数")
    }

    // 发送hello握手消息
    private fun sendHelloMessage() {
        Log.d(TAG, "sendHelloMessage: isConnected.get()=${isConnected.get()},webSocket=${webSocket},deviceConfig=${deviceConfig}")
        if (!isConnected.get() || webSocket == null) {
            Log.e(TAG, "sendHelloMessage: 连接未建立或webSocket为空，无法发送hello消息")
            return
        }

        try {
            val config = deviceConfig
            if (config == null) {
                Log.e(TAG, "sendHelloMessage: deviceConfig为空，无法发送hello消息")
                return
            }

            // 实时查询数据库，获取人脸注册状态
            val hasFaces = try {
                val service = FloatingWindowService.getInstance()
                if (service != null) {
                    val faceManager = FaceRecognitionManager.getInstance(service)
                    faceManager.hasRegisteredFaces()
                } else {
                    Log.w(TAG, "FloatingWindowService实例为空，使用缓存的hasRegisteredFaces")
                    hasRegisteredFaces
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询人脸注册状态失败，使用缓存的hasRegisteredFaces: ${e.message}")
                hasRegisteredFaces
            }

            val helloMessage = JSONObject()
            helloMessage.put("type", "hello")
            helloMessage.put("device_id", config.deviceId)
            helloMessage.put("device_name", config.deviceName)
            helloMessage.put("token", config.token)
            helloMessage.put("features", JSONObject().put("mcp", true))
            helloMessage.put("keepalive", true)
            
            // 根据人脸注册状态设置工作模式
            val listenMode = if (hasFaces) "manual" else "auto"
            helloMessage.put("client_listen_mode", listenMode)
            Log.d(TAG, "设置工作模式: client_listen_mode=$listenMode, hasFaces=$hasFaces, hasRegisteredFaces=$hasRegisteredFaces")

            Log.d(TAG, "发送hello握手消息: $helloMessage")
            webSocket?.send(helloMessage.toString())
        } catch (e: Exception) {
            Log.e(TAG, "发送hello消息失败: ${e.message}", e)
        }
    }

    // 发送工作模式更新消息
    fun sendListenModeUpdate() {
        if (!isConnected.get() || webSocket == null) {
            Log.e(TAG, "WebSocket未连接，无法发送工作模式更新消息")
            return
        }

        try {
            val listenMode = if (hasRegisteredFaces) "manual" else "auto"
            val updateMessage = JSONObject()
            updateMessage.put("type", "listen_mode_update")
            updateMessage.put("client_listen_mode", listenMode)
            
            Log.d(TAG, "发送工作模式更新消息: client_listen_mode=$listenMode, hasRegisteredFaces=$hasRegisteredFaces")
            webSocket?.send(updateMessage.toString())
        } catch (e: Exception) {
            Log.e(TAG, "发送工作模式更新消息失败: ${e.message}", e)
        }
    }

    // 发送文本消息
    fun sendTextMessage(text: String): Boolean {
        if (!isConnected.get() || webSocket == null || text.isEmpty()) {
            Log.e(TAG, "WebSocket未连接或文本为空，无法发送消息")
            return false
        }

        try {
            // 如果对方正在说话，先发送打断消息
            if (isRemoteSpeaking && currentSessionId.isNotEmpty()) {
                val abortMessage = JSONObject()
                abortMessage.put("session_id", currentSessionId)
                abortMessage.put("type", "abort")
                abortMessage.put("reason", "wake_word_detected")
                webSocket?.send(abortMessage.toString())
                Log.d(TAG, "发送打断消息")
            }

            val listenMessage = JSONObject()
            listenMessage.put("type", "listen")
            listenMessage.put("mode", "auto")
            listenMessage.put("state", "detect")
            listenMessage.put("text", text)

            Log.d(TAG, "发送文本消息")
            webSocket?.send(listenMessage.toString())
            return true
        } catch (e: Exception) {
            Log.e(TAG, "发送消息错误: ${e.message}", e)
            return false
        }
    }

    // 发送终端模式同步消息
    fun sendTerminalModeSyncMessage(mode: String) {
        if (!isConnected.get() || webSocket == null) {
            Log.e(TAG, "WebSocket未连接，无法发送终端模式同步消息")
            return
        }

        try {
            val syncMessage = JSONObject()
            syncMessage.put("type", "terminal_mode_sync")
            syncMessage.put("mode", mode)
            
            Log.d(TAG, "发送终端模式同步消息: mode=$mode")
            webSocket?.send(syncMessage.toString())
        } catch (e: Exception) {
            Log.e(TAG, "发送终端模式同步消息失败: ${e.message}", e)
        }
    }

    // 发送原始JSON消息（不包装成listen消息）
    fun sendRawJsonMessage(jsonString: String): Boolean {
        if (!isConnected.get() || webSocket == null || jsonString.isEmpty()) {
            Log.e(TAG, "WebSocket未连接或消息为空，无法发送消息")
            return false
        }

        try {
            webSocket?.send(jsonString)
            Log.d(TAG, "发送原始JSON消息")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "发送原始JSON消息错误: ${e.message}", e)
            return false
        }
    }

    // 发送会议提醒消息
    fun sendMeetingReminderMessage(
        meetingId: String,
        meetingType: String,
        meetingNumber: String,
        meetingTime: String,
        meetingName: String
    ): Boolean {
        if (!isConnected.get() || webSocket == null) {
            Log.e(TAG, "WebSocket未连接，无法发送会议提醒消息")
            return false
        }

        try {
            val reminderMessage = JSONObject()
            reminderMessage.put("type", "meeting_reminder")
            reminderMessage.put("meeting_id", meetingId)
            reminderMessage.put("meeting_type", meetingType)
            reminderMessage.put("meeting_number", meetingNumber)
            reminderMessage.put("meeting_time", meetingTime)
            reminderMessage.put("meeting_name", meetingName)

            Log.d(TAG, "发送会议提醒消息")
            webSocket?.send(reminderMessage.toString())
            return true
        } catch (e: Exception) {
            Log.e(TAG, "发送会议提醒消息失败: ${e.message}", e)
            return false
        }
    }

    // 处理文本消息
    private fun handleTextMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "hello" -> handleHelloMessage(json)
                "tts" -> handleTtsMessage(json)
                "audio" -> handleAudioMessage(json)
                "stt" -> handleSttMessage(json)
                "llm" -> handleLlmMessage(json)
                "mcp" -> handleMcpMessage(json)
                "terminal_mode" -> handleTerminalModeMessage(json)
                "terminal_mode_sync" -> handleTerminalModeSyncMessage(json)
                "heartbeat" -> {
                    // 处理服务器心跳响应
                  //  Log.d(TAG, "收到服务器心跳响应")
                    lastMessageReceivedTime = System.currentTimeMillis()
                    mainHandler.post {
                        onConnectionHealthChange?.invoke(true)
                    }
                }
                "pong" -> {
                    // 处理测试消息响应
                    Log.d(TAG, "收到连接测试响应")
                    lastMessageReceivedTime = System.currentTimeMillis()
                    mainHandler.post {
                        onConnectionHealthChange?.invoke(true)
                    }
                }
                else -> handleUnknownMessage(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理文本消息失败: ${e.message}", e)
        }
    }

    // 处理hello消息
    private fun handleHelloMessage(json: JSONObject) {
        Log.d(TAG, "服务器hello响应")
        currentSessionId = json.optString("session_id", "")
        Log.d(TAG, "获取到会话ID: $currentSessionId")

        // 如果服务器支持心跳，更新心跳间隔
        val keepalive = json.optInt("keepalive_interval", 0)
        if (keepalive > 0) {
            Log.d(TAG, "服务器建议心跳间隔: ${keepalive}ms")
        }
    }

    // 处理tts消息
    private fun handleTtsMessage(json: JSONObject) {
        val state = json.optString("state", "")
        val sessionId = json.optString("session_id", "")
        val text = json.optString("text", "")

        Log.d(TAG, "处理tts消息，状态: $state, 会话ID: $sessionId")

        when (state) {
            "start" -> {
                Log.d(TAG, "服务器开始发送语音")
                currentSessionId = sessionId
                isRemoteSpeaking = true

                mainHandler.post {
                    onSessionStateChange?.invoke(true)
                }

                // 确保AudioPlayer已启动
                if (!audioPlayer.isPlaying()) {
                    audioPlayer.start()
                }
            }
            "sentence_start" -> {
                Log.d(TAG, "服务器发送语音段")
                if (text.isNotEmpty()) {
                    // 延迟显示文字，等待音频开始播放
                    // 延迟时间：预缓冲3帧（180ms）+ 第一帧播放时间
                    mainHandler.postDelayed({
                        mainHandler.post {
                            onTextMessage?.invoke(text, false)
                        }
                    }, 200)
                }
            }
            "sentence_end" -> {
                Log.d(TAG, "语音段结束")
            }
            "stop" -> {
                Log.d(TAG, "服务器语音传输结束")
                isRemoteSpeaking = false

                mainHandler.post {
                    onSessionStateChange?.invoke(false)
                }

                // 停止音频播放
                audioPlayer.stop()
            }
        }
    }

    // 处理audio消息
    private fun handleAudioMessage(json: JSONObject) {
        Log.d(TAG, "收到音频控制消息")
    }

    // 处理stt消息
    private fun handleSttMessage(json: JSONObject) {
        val text = json.optString("text", "")
        Log.d(TAG, "识别结果: $text")

        if (text.isNotEmpty()) {
            // 检查是否为退出指令
            val exitKeywords = listOf("结束当前对话", "退下", "结束对话", "断开连接")
            val lowerText = text.lowercase()
            val isExitCommand = exitKeywords.any { keyword ->
                lowerText.contains(keyword.lowercase())
            }

            if (isExitCommand) {
                Log.d(TAG, "检测到退出指令，执行停止录音和断开连接操作")

                // 在主线程显示退出消息
                mainHandler.post {
                    onTextMessage?.invoke("检测到退出指令，正在断开连接...", false)
                }

                // 延迟执行断开连接操作，确保消息先显示
                mainHandler.postDelayed({
                    // 执行停止录音和断开连接操作
                    val mcpToolsManager = com.minrray.voicectl.core.mcp.McpToolsManager.getInstance()
                    mcpToolsManager.stopRecordingAndDisconnect()
                }, 500) // 延迟 500ms 执行

            } else {
                // 正常处理文本消息 - 在主线程更新 UI
                mainHandler.post {
                    // 显示用户说的话
                    onTextMessage?.invoke(text, true)
                }
            }
        } else {
            Log.d(TAG, "识别结果为空")
        }
    }

    // 处理llm消息
    private fun handleLlmMessage(json: JSONObject) {
        val text = json.optString("text", "")
        Log.d(TAG, "大模型回复: $text")

        // 如果包含表情，更新sessionStatus表情
        val emoji = extractEmoji(text)
        if (emoji != null) {
            mainHandler.post {
                onSessionEmotionChange?.invoke(emoji)
            }
        }

        // 只有当文本不仅仅是表情时，才添加到对话中
        val textWithoutEmoji = text.replace("[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]|[\\uD83F][\\uDC00-\\uDFFF]|[\\u2600-\\u26FF]|[\\u2700-\\u27BF]".toRegex(), "").trim()
        if (textWithoutEmoji.isNotEmpty()) {
            mainHandler.post {
                onTextMessage?.invoke(text, false)
            }
        }
    }

    // 处理mcp消息
    private fun handleMcpMessage(json: JSONObject) {
        Log.d(TAG, "收到MCP消息")
        try {
            val payload = json.getJSONObject("payload")
            val method = payload.optString("method", "")
            val params = payload.optJSONObject("params")
            val id = payload.optString("id", "")
            val sessionId = json.optString("session_id", "")

            when (method) {
                "tools/list" -> handleToolsListRequest(sessionId, id)
                "tools/call" -> handleToolsCallRequest(sessionId, id, params)
                "initialize" -> Log.d(TAG, "收到工具初始化请求")
                else -> Log.w(TAG, "未知的MCP方法: $method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理MCP消息失败: ${e.message}", e)
        }
    }

    // 处理工具列表请求
    private fun handleToolsListRequest(sessionId: String, id: String) {
        val mcpToolsManager = com.minrray.voicectl.core.mcp.McpToolsManager.getInstance()
        val toolsArray = mcpToolsManager.getMcpToolsAsJsonArray()

        Log.d(TAG, "回复MCP工具列表，工具数量: ${toolsArray.length()}")
        for (i in 0 until toolsArray.length()) {
            val tool = toolsArray.getJSONObject(i)
            Log.d(TAG, "工具 #${i+1}: ${tool.optString("name")}")
        }

        val replyMessage = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "mcp")
            put("payload", JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", JSONObject().apply {
                    put("tools", toolsArray)
                })
            })
        }

        webSocket?.send(replyMessage.toString())
    }

    // 处理工具调用请求
    private fun handleToolsCallRequest(sessionId: String, id: String, params: JSONObject?) {
        if (params == null) {
            Log.e(TAG, "工具调用请求缺少params参数")
            return
        }

        val toolName = params.optString("name", "")
        val toolArgs = params.optJSONObject("arguments")

        // 检测会议查询并触发联动效果
        if (toolName == "self.list_meeting_schedules") {
            val dateStr = toolArgs?.optString("date", "") ?: ""
            val targetDate = if (dateStr.isNotEmpty()) {
                parseNaturalLanguageDate(dateStr)
            } else {
                null
            }
            Log.d(TAG, "检测到会议查询工具调用，目标日期: $targetDate")
            onMeetingQuery?.invoke(targetDate)
        }

        val mcpToolsManager = com.minrray.voicectl.core.mcp.McpToolsManager.getInstance()
        
        // 保存 sessionId 和 id，用于异步返回结果
        mcpToolsManager.setCurrentMcpCallInfo(sessionId, id)
        
        val result = mcpToolsManager.executeMcpTool(toolName, toolArgs)

        val contentArray = org.json.JSONArray()
        val textContent = JSONObject().apply {
            put("type", "text")
            put("text", result.toString())
        }
        contentArray.put(textContent)

        val replyMessage = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "mcp")
            put("payload", JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", JSONObject().apply {
                    put("content", contentArray)
                    put("isError", false)
                })
            })
        }

        Log.d(TAG, "回复工具调用结果")
        webSocket?.send(replyMessage.toString())
    }

    // 处理未知消息
    private fun handleUnknownMessage(json: JSONObject) {
        Log.d(TAG, "未知消息类型: ${json.optString("type", "unknown")}")
        mainHandler.post {
            onTextMessage?.invoke(json.toString(), false)
        }
    }

    // 处理终端模式消息
    private fun handleTerminalModeMessage(json: JSONObject) {
        val mode = json.optString("mode", "")
        Log.d(TAG, "收到终端模式消息: $mode")

        val service = com.minrray.voicectl.core.service.FloatingWindowService.getInstance()
        if (service == null) {
            Log.e(TAG, "FloatingWindowService实例为空，无法处理终端模式消息")
            return
        }

        // 服务端发送的 terminal_mode 消息，用于确认客户端的模式状态
        // 客户端需要响应并更新回显消息界面显示
        service.handleTerminalMode(mode)
    }

    // 处理终端模式同步消息
    private fun handleTerminalModeSyncMessage(json: JSONObject) {
        val mode = json.optString("mode", "")
        Log.d(TAG, "收到终端模式同步消息: $mode，更新连接属性")

        // 服务端根据客户端同步的实际模式设置连接属性
        // 这里可以存储模式状态，供大模型判断使用
        // 实际的状态管理应该在服务端实现
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

    // 处理二进制消息
    private fun handleBinaryMessage(bytes: ByteString) {
        try {
            val dataSize = bytes.size

            // 数据完整性检查
            if (dataSize == 0) {
                Log.w(TAG, "忽略空的二进制消息")
                return
            }

            if (dataSize > 10240) { // 限制最大数据大小为10KB
                Log.w(TAG, "二进制消息过大，忽略: ${dataSize}字节")
                return
            }

            // 检查是否在会议中，会议中不播放TTS语音
            val isInMeeting = com.minrray.voicectl.core.service.FloatingWindowService.getInMeeting()
            if (isInMeeting) {
                if (shouldLog("skip_tts_in_meeting")) {
                    Log.d(TAG, "当前在会议中，跳过TTS语音播放")
                }
                return
            }

            // 将二进制数据转换为字节数组
            val data = bytes.toByteArray()

            // 只有在服务器正在发送语音流时才处理二进制语音数据
            if (isRemoteSpeaking) {
                // 确保AudioPlayer已启动
                if (!audioPlayer.isPlaying()) {
                    audioPlayer.start()
                }
                // 播放Opus数据
                audioPlayer.playOpusData(data)
            } else {
                // 使用日志限流，每2秒只输出一次
                if (shouldLog("ignore_binary_message")) {
                    Log.d(TAG, "当前非远程说话状态，忽略二进制消息")
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "二进制数据格式错误: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "处理二进制消息失败: ${e.message}", e)
        }
    }

    // 提取文本中的表情
    private fun extractEmoji(text: String): String? {
        val emojiRegex = "[\\uD83C-\\uD83E][\\uDC00-\\uDFFF]|[\\uD83F][\\uDC00-\\uDFFF]|[\\u2600-\\u26FF]|[\\u2700-\\u27BF]".toRegex()
        return emojiRegex.find(text)?.value
    }

    // 解析自然语言日期
    private fun parseNaturalLanguageDate(dateStr: String): java.util.Date? {
        val now = java.util.Calendar.getInstance()
        val targetDate = java.util.Calendar.getInstance()
        
        try {
            val trimmedStr = dateStr.trim()
            
            // 处理日期格式，如：2025-12-20
            val dateRegex = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
            val dateMatch = dateRegex.find(trimmedStr)
            if (dateMatch != null) {
                val year = dateMatch.groupValues[1].toInt()
                val month = dateMatch.groupValues[2].toInt() - 1
                val day = dateMatch.groupValues[3].toInt()
                
                targetDate.set(year, month, day, 0, 0, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理月份+日期格式，如：七月五号、7月5号、七月5号、7月5日
            val monthDayRegex = Regex("(\\d+)\\s*[月]\\s*(\\d+)\\s*[号日]")
            val monthDayMatch = monthDayRegex.find(trimmedStr)
            if (monthDayMatch != null) {
                val month = monthDayMatch.groupValues[1].toInt() - 1
                val day = monthDayMatch.groupValues[2].toInt()
                
                val currentYear = now.get(java.util.Calendar.YEAR)
                val currentMonth = now.get(java.util.Calendar.MONTH)
                val currentDay = now.get(java.util.Calendar.DAY_OF_MONTH)
                
                var targetYear = currentYear
                
                if (month < currentMonth || (month == currentMonth && day < currentDay)) {
                    targetYear = currentYear + 1
                }
                
                targetDate.set(targetYear, month, day, 0, 0, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理中文月份日期格式，如：七月五号
            val chineseMonthDayRegex = Regex("(一|二|三|四|五|六|七|八|九|十|十一|十二)\\s*月\\s*(\\d+)\\s*[号日]")
            val chineseMonthDayMatch = chineseMonthDayRegex.find(trimmedStr)
            if (chineseMonthDayMatch != null) {
                val monthMap = mapOf(
                    "一" to 1, "二" to 2, "三" to 3, "四" to 4,
                    "五" to 5, "六" to 6, "七" to 7, "八" to 8,
                    "九" to 9, "十" to 10, "十一" to 11, "十二" to 12
                )
                val month = (monthMap[chineseMonthDayMatch.groupValues[1]] ?: 1) - 1
                val day = chineseMonthDayMatch.groupValues[2].toInt()
                
                val currentYear = now.get(java.util.Calendar.YEAR)
                val currentMonth = now.get(java.util.Calendar.MONTH)
                val currentDay = now.get(java.util.Calendar.DAY_OF_MONTH)
                
                var targetYear = currentYear
                
                if (month < currentMonth || (month == currentMonth && day < currentDay)) {
                    targetYear = currentYear + 1
                }
                
                targetDate.set(targetYear, month, day, 0, 0, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理相对日期格式，如：昨天、今天、明天、后天
            if (trimmedStr.contains("昨天")) {
                targetDate.time = now.time
                targetDate.add(java.util.Calendar.DAY_OF_YEAR, -1)
                targetDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
                targetDate.set(java.util.Calendar.MINUTE, 0)
                targetDate.set(java.util.Calendar.SECOND, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            } else if (trimmedStr.contains("今天")) {
                targetDate.time = now.time
                targetDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
                targetDate.set(java.util.Calendar.MINUTE, 0)
                targetDate.set(java.util.Calendar.SECOND, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            } else if (trimmedStr.contains("明天")) {
                targetDate.time = now.time
                targetDate.add(java.util.Calendar.DAY_OF_YEAR, 1)
                targetDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
                targetDate.set(java.util.Calendar.MINUTE, 0)
                targetDate.set(java.util.Calendar.SECOND, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            } else if (trimmedStr.contains("后天")) {
                targetDate.time = now.time
                targetDate.add(java.util.Calendar.DAY_OF_YEAR, 2)
                targetDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
                targetDate.set(java.util.Calendar.MINUTE, 0)
                targetDate.set(java.util.Calendar.SECOND, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理星期几格式，如：周一、周二等
            val weekdayRegex = Regex("(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)")
            val weekdayMatch = weekdayRegex.find(trimmedStr)
            if (weekdayMatch != null && !trimmedStr.contains("上周") && !trimmedStr.contains("下周")) {
                val weekdayStr = weekdayMatch.groupValues[1]
                
                val weekdays = mapOf(
                    "周一" to 1, "星期一" to 1,
                    "周二" to 2, "星期二" to 2,
                    "周三" to 3, "星期三" to 3,
                    "周四" to 4, "星期四" to 4,
                    "周五" to 5, "星期五" to 5,
                    "周六" to 6, "星期六" to 6,
                    "周日" to 0, "星期日" to 0
                )
                
                val targetWeekday = weekdays[weekdayStr] ?: 0
                val currentWeekday = now.get(java.util.Calendar.DAY_OF_WEEK)
                val normalizedCurrentWeekday = if (currentWeekday == java.util.Calendar.SUNDAY) 0 else currentWeekday - 1
                
                val daysToAdd = if (targetWeekday > normalizedCurrentWeekday) {
                    targetWeekday - normalizedCurrentWeekday
                } else if (targetWeekday < normalizedCurrentWeekday) {
                    targetWeekday + 7 - normalizedCurrentWeekday
                } else {
                    0
                }
                
                targetDate.time = now.time
                targetDate.add(java.util.Calendar.DAY_OF_YEAR, daysToAdd)
                targetDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
                targetDate.set(java.util.Calendar.MINUTE, 0)
                targetDate.set(java.util.Calendar.SECOND, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理上周/下周的星期几
            val lastWeekRegex = Regex("上周\\s*(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)")
            val lastWeekMatch = lastWeekRegex.find(trimmedStr)
            if (lastWeekMatch != null || trimmedStr.startsWith("上周")) {
                val weekdayStr = if (lastWeekMatch != null) {
                    lastWeekMatch.groupValues[1]
                } else {
                    val weekdayPattern = Regex("(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)")
                    val match = weekdayPattern.find(trimmedStr)
                    match?.groupValues?.get(1) ?: "周一"
                }
                
                val weekdays = mapOf(
                    "周一" to 1, "星期一" to 1,
                    "周二" to 2, "星期二" to 2,
                    "周三" to 3, "星期三" to 3,
                    "周四" to 4, "星期四" to 4,
                    "周五" to 5, "星期五" to 5,
                    "周六" to 6, "星期六" to 6,
                    "周日" to 0, "星期日" to 0
                )
                
                val targetWeekday = weekdays[weekdayStr] ?: 0
                val currentWeekday = now.get(java.util.Calendar.DAY_OF_WEEK)
                val normalizedCurrentWeekday = if (currentWeekday == java.util.Calendar.SUNDAY) 0 else currentWeekday - 1
                
                val daysToAdd = if (targetWeekday > normalizedCurrentWeekday) {
                    targetWeekday - normalizedCurrentWeekday - 7
                } else if (targetWeekday < normalizedCurrentWeekday) {
                    targetWeekday + 7 - normalizedCurrentWeekday - 7
                } else {
                    -7
                }
                
                targetDate.time = now.time
                targetDate.add(java.util.Calendar.DAY_OF_YEAR, daysToAdd)
                targetDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
                targetDate.set(java.util.Calendar.MINUTE, 0)
                targetDate.set(java.util.Calendar.SECOND, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            val nextWeekRegex = Regex("下周\\s*(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)")
            val nextWeekMatch = nextWeekRegex.find(trimmedStr)
            if (nextWeekMatch != null || trimmedStr.startsWith("下周")) {
                val weekdayStr = if (nextWeekMatch != null) {
                    nextWeekMatch.groupValues[1]
                } else {
                    val weekdayPattern = Regex("(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)")
                    val match = weekdayPattern.find(trimmedStr)
                    match?.groupValues?.get(1) ?: "周一"
                }
                
                val weekdays = mapOf(
                    "周一" to 1, "星期一" to 1,
                    "周二" to 2, "星期二" to 2,
                    "周三" to 3, "星期三" to 3,
                    "周四" to 4, "星期四" to 4,
                    "周五" to 5, "星期五" to 5,
                    "周六" to 6, "星期六" to 6,
                    "周日" to 0, "星期日" to 0
                )
                
                val targetWeekday = weekdays[weekdayStr] ?: 0
                val currentWeekday = now.get(java.util.Calendar.DAY_OF_WEEK)
                val normalizedCurrentWeekday = if (currentWeekday == java.util.Calendar.SUNDAY) 0 else currentWeekday - 1
                
                val daysToAdd = if (targetWeekday > normalizedCurrentWeekday) {
                    targetWeekday - normalizedCurrentWeekday + 7
                } else if (targetWeekday < normalizedCurrentWeekday) {
                    targetWeekday + 7 - normalizedCurrentWeekday + 7
                } else {
                    14
                }
                
                targetDate.time = now.time
                targetDate.add(java.util.Calendar.DAY_OF_YEAR, daysToAdd)
                targetDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
                targetDate.set(java.util.Calendar.MINUTE, 0)
                targetDate.set(java.util.Calendar.SECOND, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理这个月/下个月
            val thisMonthRegex = Regex("这个月\\s*(\\d+)\\s*[号日]")
            val thisMonthMatch = thisMonthRegex.find(trimmedStr)
            if (thisMonthMatch != null) {
                val day = thisMonthMatch.groupValues[1].toInt()
                targetDate.set(java.util.Calendar.DAY_OF_MONTH, day)
                targetDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
                targetDate.set(java.util.Calendar.MINUTE, 0)
                targetDate.set(java.util.Calendar.SECOND, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            val nextMonthRegex = Regex("下个月\\s*(\\d+)\\s*[号日]")
            val nextMonthMatch = nextMonthRegex.find(trimmedStr)
            if (nextMonthMatch != null) {
                val day = nextMonthMatch.groupValues[1].toInt()
                targetDate.add(java.util.Calendar.MONTH, 1)
                targetDate.set(java.util.Calendar.DAY_OF_MONTH, day)
                targetDate.set(java.util.Calendar.HOUR_OF_DAY, 0)
                targetDate.set(java.util.Calendar.MINUTE, 0)
                targetDate.set(java.util.Calendar.SECOND, 0)
                targetDate.set(java.util.Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "解析日期失败: ${e.message}", e)
        }
        
        return null
    }

    // 检查连接状态
    fun isConnected(): Boolean {
        return isConnected.get()
    }

    // 获取是否正在发送实时音频
    fun isSendingRealTimeAudio(): Boolean {
        return isSendingRealTimeAudio
    }

    // 获取连接健康度信息
    fun getConnectionHealthInfo(): Map<String, Any> {
        val now = System.currentTimeMillis()
        return mapOf(
            "isConnected" to isConnected.get(),
            "lastMessageReceived" to (now - lastMessageReceivedTime),
            "lastHeartbeatSent" to (now - lastHeartbeatTime),
            "reconnectAttempts" to reconnectAttempts
        )
    }

    // 获取服务端URL
    fun getServerUrl(): String {
        return serverUrl
    }

    // 清理资源
    fun release() {
        disconnect()
        stopHeartbeat()
        stopHealthCheck()
        client = null
    }
}