package com.minrray.voicectl.core.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.Observer
import com.minrray.voicectl.R
import com.minrray.voicectl.core.audio.AudioEnergyHolder
import com.minrray.voicectl.core.audio.AudioRecorder
import com.minrray.voicectl.core.config.ConfigManager
import com.minrray.voicectl.core.network.WebSocketHandler
import com.minrray.voicectl.core.network.OtaConnector
import com.minrray.voicectl.core.view.CalendarView
import com.minrray.voicectl.core.view.ScrollScheduleView
import com.minrray.voicectl.core.view.VoiceWaveView
import com.minrray.voicectl.core.weather.WeatherManager
import com.minrray.voicectl.core.weather.WeatherNow
import com.minrray.voicectl.core.weather.WeatherType
import com.minrray.voicectl.core.weather.WeatherIconAnimator
import com.minrray.voicectl.core.face.FaceRecognitionManager
import com.minrray.voicectl.core.face.FaceRecognitionListener
import com.minrray.voicectl.core.speaker.SpeakerIdentificationManager
import com.minrray.voicectl.core.activechat.ActiveChatManager
import com.hexdeploy.facesdk.RecognitionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.GifImageView
import android.content.BroadcastReceiver
import android.content.IntentFilter

class FloatingWindowService : Service() {

    companion object {
        private var instance: FloatingWindowService? = null
        
        fun getInstance(): FloatingWindowService? = instance
        
        fun setInMeeting(inMeeting: Boolean) {
            instance?.let { service ->
                service.isInMeeting = inMeeting
                Log.d("FloatingWindowService", "设置会议状态: isInMeeting = $inMeeting")
                
                if (inMeeting) {
                    Log.d("FloatingWindowService", "进入会议模式，停止人脸检测并重启唤醒词服务")
                    
                    service.stopFaceRecognition()
                    
                    if (!service.isRecording) {
                        service.startWakeWordService("进入会议模式，启动唤醒词服务")
                    }
                    
                    // 发送会议开始消息到服务端
                    service.wsHandler.sendMeetingStartMessage()
                } else {
                    Log.d("FloatingWindowService", "退出会议模式，停止唤醒词服务并重启人脸检测")
                    
                    service.stopWakeWordService("退出会议模式，停止唤醒词服务")
                    service.startFaceRecognition()
                    
                    // 发送会议结束消息到服务端
                    service.wsHandler.sendMeetingEndMessage()
                }
            }
        }
        
        fun getInMeeting(): Boolean = instance?.isInMeeting ?: false
        
        fun sendFaceRecognitionControl(action: String) {
            instance?.let { service ->
                service.handleFaceRecognitionControl(action)
            }
        }
        
        fun getLastRegisteredFaceTime(): Long = instance?.lastRegisteredFaceTime ?: 0L
        
        fun getLastFacingCameraTime(): Long = instance?.lastFacingCameraTime ?: 0L
        
        fun getLastLipMovementTime(): Long = instance?.lastLipMovementTime ?: 0L
        
        fun getAutoListenTimeWindow(): Long = 2000L
        
        fun getTerminalSleepMode(): Boolean = instance?.isTerminalSleepMode ?: false
    }

    // 通知相关常量
    private val CHANNEL_ID = "floating_window_service_channel"
    private val NOTIFICATION_ID = 1001

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View

    private var isRecording = false

    private val audioRecorder = AudioRecorder.getInstance()
    private val wsHandler = WebSocketHandler.getInstance()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 文字显示相关
    lateinit var speechTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var localIpTextView: TextView
    private lateinit var serverIpTextView: TextView
    val speechMessages = mutableListOf<String>()
    private var currentMessageIndex = 0
    private var isAutoScrolling = true

    // 波浪动画相关
    private lateinit var voiceWaveView: VoiceWaveView
    private lateinit var energyLabel: TextView
    private lateinit var energyStateLabel: TextView
    private lateinit var waveToggleButton: TextView
    private lateinit var waveInfoButton: TextView
    private var energyObservation: Observer<Float>? = null
    private var energyStateObservation: Observer<AudioEnergyHolder.EnergyState>? = null

    // 会议查询结果相关
    private lateinit var meetingResultContainer: android.widget.LinearLayout
    private lateinit var meetingResultCurrentContainer: android.widget.LinearLayout
    private lateinit var meetingResultCurrentContent: android.widget.LinearLayout
    private lateinit var meetingResultCurrentScroll: android.widget.ScrollView
    private lateinit var meetingResultUpcomingContainer: android.widget.LinearLayout
    private lateinit var meetingResultUpcomingContent: android.widget.LinearLayout
    private lateinit var meetingResultUpcomingScroll: android.widget.ScrollView
    private lateinit var meetingResultPastContainer: android.widget.LinearLayout
    private lateinit var meetingResultPastContent: android.widget.LinearLayout
    private lateinit var meetingResultPastScroll: android.widget.ScrollView
    private lateinit var meetingResultRecommendationContainer: android.widget.LinearLayout
    private lateinit var meetingResultRecommendation: TextView
    private lateinit var meetingReportContainer: android.widget.LinearLayout
    private lateinit var meetingReportContent: android.widget.LinearLayout
    private lateinit var meetingReportScroll: android.widget.ScrollView
  private var platformMeetingQueryRunnable: Runnable? = null
  private var meetingReportUpdateRunnable: Runnable? = null
  private var meetingStatusCheckRunnable: Runnable? = null
  private var meetingUpdateRunnable: Runnable? = null // 合并后的统一定时器
  private var autoListenCheckRunnable: Runnable? = null // 自动监听检查定时器

  // 会议数据变化检测缓存
    private var lastCurrentMeetings: String = ""
    private var lastUpcomingMeetings: String = ""
    private var lastPastMeetings: String = ""
    private var lastRecommendation: String = ""
    private var lastIsInMeeting: Boolean = false
    private var isQueryingMeeting = false // 防止重复查询的标志

  // 天气相关
    private lateinit var weatherAnimation: ImageView
    private lateinit var weatherText: TextView
    private lateinit var weatherManager: WeatherManager
    private lateinit var weatherIconAnimator: WeatherIconAnimator

    // 唤醒词检测相关
    private var isWakeWordServiceRunning = false
    private var isWakeWordDetected = false
    private var isConnected = false
    private var isAssistantSpeaking = false
    private var isSpeakerRegistering = false
    private var lastConnectionTime = 0L
    private var lastWakeWordServiceStartTime = 0L
    private var isWakeWordServiceStarting = false // 标记唤醒词服务是否正在启动中
    private var isDisconnecting = false // 标记是否正在断开连接
    private val WAKE_WORD_SERVICE_START_COOLDOWN = 2000L // 2秒冷却时间
    private val otaConnector = OtaConnector.getInstance()

    
    private val configManager by lazy { ConfigManager.getInstance(this) }

    // 人脸识别相关
    private lateinit var faceRecognitionManager: FaceRecognitionManager
    private lateinit var speakerIdentificationManager: SpeakerIdentificationManager
    private var isFaceRecognitionRunning = false
    private var isFaceDetected = false
    private var facePreviewSurfaceView: SurfaceView? = null

    // 自动语音监听控制相关
    internal var lastRegisteredFaceTime = 0L
    internal var lastFacingCameraTime = 0L
    internal var lastLipMovementTime = 0L
    private var lastVoiceEnergyTime = 0L
    private var isAutoListening = false
    private var lastListenStateChangeTime = 0L
    private val AUTO_LISTEN_CHECK_INTERVAL = 200L
    private val AUTO_LISTEN_TIME_WINDOW = 1000L
    private val AUTO_LISTEN_STOP_TIME_WINDOW = 2000L
    private val AUTO_LISTEN_COOLDOWN = 2000L

    // 声纹识别相关
    private var lastIdentifiedSpeakerTime = 0L
    private val SPEAKER_IDENTIFICATION_TIME_WINDOW = 2000L
    private var lastIdentifiedSpeakerName: String? = null
    private var speakerIdentificationEnabled = false
    private val speakerIdentificationThreshold = 0.5f

    // 提示音相关
    private var listenStartPlayer: MediaPlayer? = null
    private var listenStopPlayer: MediaPlayer? = null
    private var audioManager: AudioManager? = null
    private var listenStartSoundId: Int = 0
    private var listenStopSoundId: Int = 0

    // 主动聊天管理器
    public lateinit var activeChatManager: ActiveChatManager

    // 终端模式相关
    var isTerminalSleepMode = false // 是否处于休息模式
    var savedSpeechMessages = mutableListOf<String>() // 保存休息模式前的消息记录

    // 会议查询联动相关
    private lateinit var calendarDate: TextView
    private lateinit var calendarView: CalendarView
    private lateinit var scheduleList: ScrollScheduleView
    private var originalScheduleTitle = "今日日程"
    private var restoreRunnable: Runnable? = null
    private var isInMeeting = false // 用户是否在会议中（入会成功置true，退会成功置false）

    // 日期更新定时器
    private var updateDateRunnable: Runnable? = null

    // IP更新定时器
    private var updateIpRunnable: Runnable? = null

    // 唤醒词检测广播接收器
    private val wakeWordBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.minrray.voicectl.WAKE_WORD_DETECTED") {
                Log.i("FloatingWindowService", "===>> 收到唤醒词检测事件")
                val keyword = intent.getStringExtra("keyword")
                if (keyword != null) {
                    addSpeechMessage("检测到唤醒词: $keyword")
                    Log.i("FloatingWindowService", "==> 检测到唤醒词: $keyword")
                    isWakeWordDetected = true

                    // 检查是否是"结束会议"唤醒词
                    if (keyword.contains("结束会议") || keyword.contains("exit_meeting")) {
                        Log.i("FloatingWindowService", "检测到结束会议唤醒词，调用退出会议工具")
                        addSpeechMessage("检测到结束会议唤醒词，正在退出会议...")
                        
                        // 调用退出会议工具
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val mcpToolsManager = com.minrray.voicectl.core.mcp.McpToolsManager.getInstance()
                                val toolArgs = org.json.JSONObject()
                                val result = mcpToolsManager.executeMcpTool("self.exit_meeting", toolArgs)
                                
                                if (result.optBoolean("success", false)) {
                                    addSpeechMessage("已成功退出会议")
                                    Log.i("FloatingWindowService", "退出会议成功")
                                } else {
                                    addSpeechMessage("退出会议失败: ${result.optString("error", "未知错误")}")
                                    Log.e("FloatingWindowService", "退出会议失败: ${result.optString("error", "未知错误")}")
                                }
                            } catch (e: Exception) {
                                addSpeechMessage("退出会议失败: ${e.message}")
                                Log.e("FloatingWindowService", "退出会议异常: ${e.message}", e)
                            }
                        }
                        
                        // 停止唤醒词服务，避免重复检测
                        stopWakeWordService("停止唤醒词服务")
                        return
                    }

                    // 停止唤醒词服务，避免重复检测
                    stopWakeWordService("停止唤醒词服务")

                    // 自动连接服务器
                    if (!isConnected) {
                        addSpeechMessage("正在自动连接服务器...")
                        Log.i("FloatingWindowService", "正在自动连接服务器...")
                        // 重置重连计数，允许重新连接
                        wsHandler.resetReconnectAttempts()
                        // 在协程中调用挂起函数
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val allConfigs = configManager.loadAllConfigs()
                            val wsUrl = otaConnector.getWebSocketUrl(allConfigs.deviceConfig.otaUrl, allConfigs.deviceConfig.deviceId, allConfigs.deviceConfig.clientId)
                            if (wsUrl != null) {
                                configManager.updateServerUrl(wsUrl)
                                val updatedConfigs = configManager.loadAllConfigs()
                                Log.i("FloatingWindowService", "配置已保存: ${updatedConfigs.deviceConfig}")
                                // 确保deviceConfig已设置
                                val wsDeviceConfig = WebSocketHandler.DeviceConfig(
                                    deviceId = updatedConfigs.deviceConfig.deviceId,
                                    deviceName = updatedConfigs.deviceConfig.deviceName,
                                    token = updatedConfigs.deviceConfig.token,
                                    otaUrl = updatedConfigs.deviceConfig.otaUrl,
                                    serverUrl = updatedConfigs.deviceConfig.serverUrl
                                )
                                wsHandler.setDeviceConfig(wsDeviceConfig)
                                Log.i("FloatingWindowService", "已设置deviceConfig: $wsDeviceConfig")
                                wsHandler.connect(wsUrl)
                                addSpeechMessage("WebSocket连接已建立")
                                Log.i("FloatingWindowService", "WebSocket连接已建立")
                            } else {
                                addSpeechMessage("获取WebSocket地址失败")
                                Log.e("FloatingWindowService", "获取WebSocket地址失败")
                                // 如果连接失败，重新启动唤醒词服务
                                if (!isRecording) {
                                    startWakeWordService("连接服务器失败，重新启动唤醒词服务")
                                }
                            }
                        }
                    } else {
                        addSpeechMessage("WebSocket已连接")
                        Log.i("FloatingWindowService", "WebSocket已连接")
                    }

                    // 开始监听用户说话，相当于按了按住说话按钮
                    if (!isRecording) {
                      addSpeechMessage("开始监听用户说话...")
                      Log.i("FloatingWindowService", "开始监听用户说话...")
                      isRecording = true
                      
                      // 重新设置PCM数据回调，确保能量计算正常
                      setupAudioRecorderCallbacks()
                      
                      // 启用 opus 编码，开始发送数据到服务端
                      audioRecorder.start(enableOpusEncoding = true)
                      addSpeechMessage("开始发送音频数据到服务端")
                      Log.i("FloatingWindowService", "开始发送音频数据到服务端")
                  }
                }
            }
        }
    }

    // 会议查询结果广播接收器
    private val meetingResultBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.minrray.voicectl.MEETING_RESULT") {
                Log.i("FloatingWindowService", "===>> 收到会议查询结果事件")
                val currentMeetings = intent.getStringExtra("current_meetings") ?: ""
                val upcomingMeetings = intent.getStringExtra("upcoming_meetings") ?: ""
                val pastMeetings = intent.getStringExtra("past_meetings") ?: ""
                val recommendation = intent.getStringExtra("recommendation") ?: ""
                
                showMeetingResult(currentMeetings, upcomingMeetings, pastMeetings, recommendation)
            }
        }
    }

    // 人脸识别控制广播接收器
    private val faceRecognitionControlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.minrray.voicectl.FACE_RECOGNITION_CONTROL") {
                val action = intent.getStringExtra("action")
                handleFaceRecognitionControl(action)
            }
        }
    }

    private fun handleFaceRecognitionControl(action: String?) {
        when (action) {
            "stop" -> {
                Log.i("FloatingWindowService", "收到停止人脸识别命令")
                stopFaceRecognition()
            }
            "start" -> {
                Log.i("FloatingWindowService", "收到启动人脸识别命令")
                startFaceRecognition()
            }
        }
    }
    
    fun handleTerminalMode(mode: String) {
        mainHandler.post {
            try {
                // 检查是否可以改变工作模式
                if (mode == "sleep" && isInMeeting) {
                    Log.d("FloatingWindowService", "正在开会中，不能进入休息模式")
                    return@post
                }
                
                val assistantImage = floatingView.findViewById<ImageView>(R.id.assistant_image)

                if (mode == "sleep") {
                    isTerminalSleepMode = true
                    assistantImage.setImageResource(R.drawable.ollo_sleep)
                    Log.d("FloatingWindowService", "已切换到睡觉图片")

                    savedSpeechMessages.clear()
                    savedSpeechMessages.addAll(speechMessages)
                    speechMessages.clear()
                    speechTextView.text = "睡觉休息中"
                    speechTextView.isEnabled = false
                    Log.d("FloatingWindowService", "已清空消息记录并禁用消息界面")
                } else if (mode == "work") {
                    isTerminalSleepMode = false
                    assistantImage.setImageResource(R.drawable.ollo)
                    Log.d("FloatingWindowService", "已切换回正常图片")

                    speechMessages.clear()
                    speechMessages.addAll(savedSpeechMessages)
                    savedSpeechMessages.clear()
                    speechTextView.isEnabled = true
                    updateSpeechDisplay()
                    Log.d("FloatingWindowService", "已恢复消息记录并启用消息界面")
                }
            } catch (e: Exception) {
                Log.e("FloatingWindowService", "处理终端模式失败: ${e.message}", e)
            }
        }
    }
    
    fun setTerminalMode(mode: String): String {
        // 返回实际设置的模式
        val actualMode = if (mode == "sleep" && isInMeeting) {
            Log.d("FloatingWindowService", "正在开会中，不能进入休息模式，保持当前模式")
            if (isTerminalSleepMode) "sleep" else "work"
        } else {
            handleTerminalMode(mode)
            mode
        }
        
        // 发送同步消息给服务端
        wsHandler.sendTerminalModeSyncMessage(actualMode)
        
        return actualMode
    }

    // 获取设备唯一ID
    private fun getAndroidDeviceId(): String {
        return try {
            val androidId = android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            Log.d("FloatingWindowService", "获取Android ID: $androidId")
            if (androidId != null && androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
                androidId
            } else {
                val fallbackId = "unknown_device_${System.currentTimeMillis()}"
                Log.w("FloatingWindowService", "Android ID无效，使用备用ID: $fallbackId")
                fallbackId
            }
        } catch (e: Exception) {
            val fallbackId = "unknown_device_${System.currentTimeMillis()}"
            Log.e("FloatingWindowService", "获取Android ID失败: ${e.message}, 使用备用ID: $fallbackId")
            fallbackId
        }
    }

    // 获取设备名称
    private fun getDeviceName(): String {
        return try {
            android.os.Build.MODEL
        } catch (e: Exception) {
            "Android设备"
        }
    }

    // 悬浮窗参数
    private val params by lazy {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val layoutParams = WindowManager.LayoutParams(
            (screenWidth * 0.4).toInt(),  // 宽度为屏幕的40%
            screenHeight,                  // 高度为屏幕高度
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        // 设置悬浮窗位置为屏幕右侧边缘
        layoutParams.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        layoutParams.x = 0  // 贴着屏幕右边缘
        layoutParams.y = 0  // 垂直居中

        // 设置窗口透明度
        layoutParams.alpha = 1.0f

        layoutParams
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("FloatingWindowService", "onCreate: 服务开始创建")

        // 初始化AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.d("FloatingWindowService", "onCreate: AudioManager已初始化")

        // 初始化WeatherManager
        weatherManager = WeatherManager.getInstance(this)
        Log.d("FloatingWindowService", "onCreate: WeatherManager已初始化")

        // 初始化天气图标动画器
        weatherIconAnimator = WeatherIconAnimator(this)
        Log.d("FloatingWindowService", "onCreate: WeatherIconAnimator已初始化")

        // 初始化FaceRecognitionManager
        faceRecognitionManager = FaceRecognitionManager.getInstance(this)
        Log.d("FloatingWindowService", "onCreate: FaceRecognitionManager已初始化")

        // 初始化人脸识别组件（包括数据库）
        faceRecognitionManager.initialize()
        Log.d("FloatingWindowService", "onCreate: FaceRecognitionManager组件已初始化")

        // 初始化SpeakerIdentificationManager
        speakerIdentificationManager = SpeakerIdentificationManager.getInstance(this)
        speakerIdentificationManager.initialize()
        speakerIdentificationEnabled = speakerIdentificationManager.isReady()
        Log.d("FloatingWindowService", "onCreate: SpeakerIdentificationManager已初始化,speakerIdentificationEnabled=$speakerIdentificationEnabled, 已注册说话人数量: ${speakerIdentificationManager.getSpeakerCount()}")

        // 设置声纹注册进度监听器
        com.minrray.voicectl.core.mcp.McpToolsManager.setSpeakerRegisterProgressListener(object : com.minrray.voicectl.core.mcp.SpeakerRegisterProgressListener {
            override fun onSpeakerRegisterProgress(progress: Int, maxProgress: Int) {
                mainHandler.post {
                    isSpeakerRegistering = true
                    val progressText = "声纹注册中，请数数123... ($progress/$maxProgress)"
                    speechTextView.text = progressText
                    Log.d("FloatingWindowService", "声纹注册进度: $progress/$maxProgress")
                }
            }

            override fun onSpeakerRegisterComplete(success: Boolean, message: String) {
                mainHandler.post {
                    isSpeakerRegistering = false
                    Log.d("FloatingWindowService", "声纹注册完成: success=$success, message=$message")
                    // 恢复消息回显界面
                    updateSpeechDisplay()
                }
            }
        })
        Log.d("FloatingWindowService", "onCreate: 声纹注册进度监听器已设置")

        // 检查是否已注册人脸
        val hasRegisteredFaces = faceRecognitionManager.hasRegisteredFaces()
        Log.d("FloatingWindowService", "onCreate: 已注册人脸 = $hasRegisteredFaces")
        wsHandler.updateFaceRegistrationStatus(hasRegisteredFaces)

        // 初始化ActiveChatManager
        activeChatManager = ActiveChatManager(this, weatherManager, wsHandler, faceRecognitionManager)
        Log.d("FloatingWindowService", "onCreate: ActiveChatManager已初始化")

        // 初始化MeetingNumberManager
        com.minrray.voicectl.core.meeting.MeetingNumberManager.initialize(this)
        Log.d("FloatingWindowService", "onCreate: MeetingNumberManager已初始化")

        // 创建通知渠道
        createNotificationChannel()
        Log.d("FloatingWindowService", "onCreate: 通知渠道已创建")

        // 初始化WindowManager
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Log.d("FloatingWindowService", "onCreate: WindowManager已初始化")

        // 加载新的悬浮窗布局
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window_expanded, null)
        Log.d("FloatingWindowService", "onCreate: 布局已加载")

        // 初始化视图
        initViews()
        Log.d("FloatingWindowService", "onCreate: 视图已初始化")

        // 设置WebSocket回调以接收文字消息
        setupWebSocketCallbacks()

        // 设置半透明背景，确保悬浮窗可见
        floatingView.setBackgroundColor(0x00000000)

        // 添加悬浮窗到窗口管理器
        try {
            windowManager.addView(floatingView, params)
            val displayMetrics = resources.displayMetrics
            Log.d("FloatingWindowService", "onCreate: 悬浮窗已添加到窗口管理器")
            Log.d("FloatingWindowService", "屏幕尺寸: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
            Log.d("FloatingWindowService", "悬浮窗尺寸: ${params.width}x${params.height}")
            Log.d("FloatingWindowService", "悬浮窗位置: x=${params.x}, y=${params.y}")
            Log.d("FloatingWindowService", "悬浮窗背景色: 0xCC000000 (75%不透明黑色)")
        } catch (e: Exception) {
            Log.e("FloatingWindowService", "onCreate: 添加悬浮窗失败", e)
            e.printStackTrace()
        }

        // 启动前台服务
        startForegroundService()
        Log.d("FloatingWindowService", "onCreate: 前台服务已启动")

        // 注册唤醒词检测广播接收器
        registerWakeWordBroadcastReceiver()
        registerMeetingResultBroadcastReceiver()
        registerFaceRecognitionControlReceiver()

        // 初始化并启动人脸识别
        initFaceRecognition()

        // 启动 AudioRecorder，但不进行 opus 编码
        // 整个服务运行期间录音一直打开，只有需要发送数据到服务端时才进行 opus 编码
        audioRecorder.start(enableOpusEncoding = false)
        Log.d("FloatingWindowService", "onCreate: AudioRecorder已启动（不进行opus编码）")

        // 使用post延迟执行，确保视图完全附加
        floatingView.post {
            // 设置悬浮窗大小为屏幕的1/4
            setupWindowSize()

            // 初始化波浪动画
            initWaveAnimation()
            
            // 初始化提示音
            initSoundPool()
            
            // 显示会议报告总结
            showMeetingReports()
            
            // 启动统一定时器更新会议信息（替代原来的三个定时器）
            startUnifiedMeetingUpdateTimer()
            
            // 启动自动监听检查定时器
            startAutoListenCheckTimer()
        }
    }

    /**
     * 注册唤醒词检测广播接收器
     */
    private fun registerWakeWordBroadcastReceiver() {
        val wakeWordIntentFilter = IntentFilter("com.minrray.voicectl.WAKE_WORD_DETECTED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeWordBroadcastReceiver, wakeWordIntentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wakeWordBroadcastReceiver, wakeWordIntentFilter)
        }
        Log.d("FloatingWindowService", "唤醒词检测广播接收器已注册")
    }

    /**
     * 注册会议查询结果广播接收器
     */
    private fun registerMeetingResultBroadcastReceiver() {
        val meetingResultIntentFilter = IntentFilter("com.minrray.voicectl.MEETING_RESULT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(meetingResultBroadcastReceiver, meetingResultIntentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(meetingResultBroadcastReceiver, meetingResultIntentFilter)
        }
        Log.d("FloatingWindowService", "会议查询结果广播接收器已注册")
    }

    /**
     * 注册人脸识别控制广播接收器
     */
    private fun registerFaceRecognitionControlReceiver() {
        val faceRecognitionIntentFilter = IntentFilter("com.minrray.voicectl.FACE_RECOGNITION_CONTROL")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(faceRecognitionControlReceiver, faceRecognitionIntentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(faceRecognitionControlReceiver, faceRecognitionIntentFilter)
        }
        Log.d("FloatingWindowService", "人脸识别控制广播接收器已注册")
    }

    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "悬浮窗服务"
            val descriptionText = "提供语音控制悬浮窗功能"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                setShowBadge(false)
            }
            // 注册通知渠道
            val notificationManager: NotificationManager =
                getSystemService(NotificationManager::class.java) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * 创建通知
     */
    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.icon)
            .setPriority(Notification.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun setupWindowSize() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        // 设置为屏幕右侧边栏
        params.width = (screenWidth * 0.4).toInt()  // 宽度为屏幕的40%
        params.height = screenHeight                  // 高度为屏幕高度

        // 位置在屏幕右侧边缘，垂直居中
        params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        params.x = 0  // 贴着屏幕右边缘
        params.y = 0  // 垂直居中

        // 确保视图已经完全初始化后再更新布局
        if (::windowManager.isInitialized && ::floatingView.isInitialized) {
            try {
                // 检查视图是否已经附加到窗口
                if (floatingView.windowToken != null) {
                    windowManager.updateViewLayout(floatingView, params)
                    Log.d("FloatingWindowService", "setupWindowSize: 悬浮窗布局已更新")
                    Log.d("FloatingWindowService", "屏幕尺寸: ${screenWidth}x${screenHeight}")
                    Log.d("FloatingWindowService", "悬浮窗尺寸: ${params.width}x${params.height}")
                } else {
                    // 如果还没有附加，等待一下再尝试
                    floatingView.postDelayed({
                        if (floatingView.windowToken != null) {
                            windowManager.updateViewLayout(floatingView, params)
                            Log.d("FloatingWindowService", "setupWindowSize: 延迟更新悬浮窗布局")
                        }
                    }, 100)
                }
            } catch (e: Exception) {
                // 捕获异常，避免应用崩溃
                e.printStackTrace()
                Log.e("FloatingWindowService", "setupWindowSize: 更新布局失败", e)
            }
        }
    }

    private fun initViews() {
        // 获取视图组件
        val assistantImage = floatingView.findViewById<ImageView>(R.id.assistant_image)
        weatherAnimation = floatingView.findViewById(R.id.weather_animation)
        weatherText = floatingView.findViewById(R.id.weather_text)
        calendarDate = floatingView.findViewById<TextView>(R.id.calendar_date)
        calendarView = floatingView.findViewById<CalendarView>(R.id.calendar_view)
        scheduleList = floatingView.findViewById<ScrollScheduleView>(R.id.schedule_list)
        speechTextView = floatingView.findViewById(R.id.speech_text)
        statusTextView = floatingView.findViewById(R.id.status_text)
        localIpTextView = floatingView.findViewById(R.id.local_ip)
        serverIpTextView = floatingView.findViewById(R.id.server_ip)

        // 初始化人脸识别预览SurfaceView
        facePreviewSurfaceView = floatingView.findViewById(R.id.face_preview_surface)
        val facePreviewStatus = floatingView.findViewById<TextView>(R.id.face_preview_status)
        facePreviewStatus.text = "人脸识别预览"

        // 初始化波浪相关视图
        voiceWaveView = floatingView.findViewById(R.id.voice_wave_view)
        energyLabel = floatingView.findViewById(R.id.energy_label)
        energyStateLabel = floatingView.findViewById(R.id.energy_state)
        waveToggleButton = floatingView.findViewById(R.id.wave_toggle)
        waveInfoButton = floatingView.findViewById(R.id.wave_info)

        // 初始化会议查询结果相关视图
        meetingResultContainer = floatingView.findViewById(R.id.meeting_result_container)
        meetingResultCurrentContainer = floatingView.findViewById(R.id.meeting_result_current_container)
        meetingResultCurrentContent = floatingView.findViewById(R.id.meeting_result_current_content)
        meetingResultCurrentScroll = floatingView.findViewById(R.id.meeting_result_current_scroll)
        meetingResultUpcomingContainer = floatingView.findViewById(R.id.meeting_result_upcoming_container)
        meetingResultUpcomingContent = floatingView.findViewById(R.id.meeting_result_upcoming_content)
        meetingResultUpcomingScroll = floatingView.findViewById(R.id.meeting_result_upcoming_scroll)
        meetingResultPastContainer = floatingView.findViewById(R.id.meeting_result_past_container)
        meetingResultPastContent = floatingView.findViewById(R.id.meeting_result_past_content)
        meetingResultPastScroll = floatingView.findViewById(R.id.meeting_result_past_scroll)
        meetingResultRecommendationContainer = floatingView.findViewById(R.id.meeting_result_recommendation_container)
        meetingResultRecommendation = floatingView.findViewById(R.id.meeting_result_recommendation)
        meetingReportContainer = floatingView.findViewById(R.id.meeting_report_container)
        meetingReportContent = floatingView.findViewById(R.id.meeting_report_content)
        meetingReportScroll = floatingView.findViewById(R.id.meeting_report_scroll)

        // 初始化时确保会议查询结果容器可见
        meetingResultContainer.visibility = View.VISIBLE

        // 初始化时隐藏所有会议查询结果子栏内容
        meetingResultCurrentContainer.visibility = View.GONE
        meetingResultUpcomingContainer.visibility = View.GONE
        meetingResultPastContainer.visibility = View.GONE
        meetingResultRecommendationContainer.visibility = View.GONE
        meetingReportContainer.visibility = View.GONE

        // 设置Q版女秘书小人图片 - 保持原比例
        assistantImage.setImageResource(R.drawable.ollo)
        assistantImage.scaleType = ImageView.ScaleType.FIT_CENTER
        assistantImage.adjustViewBounds = true

        // 确保图片背景透明
        assistantImage.setBackgroundColor(0x00000000)
        assistantImage.clearColorFilter()
        assistantImage.background = null

        // 设置当前日期
        val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm", Locale.CHINA)
        dateFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val currentDate = dateFormat.format(Date())
        calendarDate.text = currentDate

        // 设置日历视图为当前月份
        val calendar = Calendar.getInstance()
        calendarView.setMonth(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)

        // 启动日期更新定时器，每分钟更新一次
        startDateUpdateTimer()

        // 统一加载所有配置
        val allConfigs = configManager.loadAllConfigs()
        val deviceConfig = allConfigs.deviceConfig
        val weatherConfig = allConfigs.weatherConfig
        val bizConfConfig = allConfigs.bizConfConfig
        
        Log.d("FloatingWindowService", "加载设备配置: deviceId=${deviceConfig.deviceId}, deviceName=${deviceConfig.deviceName}, serverUrl=${deviceConfig.serverUrl}")
        Log.d("FloatingWindowService", "加载天气配置: location=${weatherConfig.location}, host=${weatherConfig.host}")
        Log.d("FloatingWindowService", "加载BizConf配置: userId=${bizConfConfig.userId}, userName=${bizConfConfig.userName}")
        
        // 初始化QWeather SDK
        if (weatherConfig.privateKey.isNotEmpty() && weatherConfig.projectId.isNotEmpty() && weatherConfig.kid.isNotEmpty()) {
            // 使用JWT认证
            weatherManager.initializeWithJWT(
                host = weatherConfig.host,
                privateKey = weatherConfig.privateKey,
                projectId = weatherConfig.projectId,
                kid = weatherConfig.kid
            )
            Log.d("FloatingWindowService", "WeatherManager使用JWT认证初始化完成")
        } else {
            // 使用普通初始化
            weatherManager.initialize(weatherConfig.host)
            Log.d("FloatingWindowService", "WeatherManager使用普通初始化完成")
        }
        weatherManager.setLocation(weatherConfig.location)
        Log.d("FloatingWindowService", "WeatherManager配置完成, isConfigured=${weatherManager.isConfigured()}")

        // 初始化天气显示
        Log.d("FloatingWindowService", "开始初始化天气显示")
        initWeatherDisplay()

        // 设置文字区域样式
        speechTextView.isSelected = true
        speechTextView.text = "您好！我是会会，竭诚为您服务..."

        // 设置文字滚动动画
        setupTextScrolling()

        // 设置波浪相关事件
        setupWaveEvents()

        // 设置能量观察
        setupEnergyObservation()

        // 设置AudioRecorder的PCM数据回调，用于计算能量
        setupAudioRecorderCallbacks()

        // 设置点击监听器
        val closeButton = floatingView.findViewById<ImageView>(R.id.close_button)
        closeButton.setOnClickListener {
            stopSelf()
        }

        // 设置整个悬浮窗可拖动
        floatingView.setOnTouchListener(FloatingOnTouchListener())

        // 初始化IP显示
        updateIpDisplay()
        
        // 启动IP更新定时器，确保程序启动后能尽快显示本地IP
        startIpUpdateTimer()
    }

    /**
     * 初始化天气显示
     */
    private fun initWeatherDisplay() {
        Log.d("FloatingWindowService", "initWeatherDisplay: 开始初始化")
        
        if (!weatherManager.isConfigured()) {
            Log.w("FloatingWindowService", "initWeatherDisplay: Weather API未配置")
            weatherText.text = "未配置天气API"
            return
        }
        
        Log.d("FloatingWindowService", "initWeatherDisplay: Weather API已配置，设置监听器")
        weatherManager.setWeatherUpdateListener { weather ->
            Log.d("FloatingWindowService", "收到天气更新: ${weather.text} ${weather.temp}°C")
            updateWeatherDisplay(weather)
        }

        Log.d("FloatingWindowService", "initWeatherDisplay: 开始刷新天气数据")
        weatherManager.refreshWeather()

        Log.d("FloatingWindowService", "initWeatherDisplay: 启动自动刷新（30分钟）")
        weatherManager.startAutoRefresh(30 * 60 * 1000L)

        weatherText.text = "加载中..."
        Log.d("FloatingWindowService", "initWeatherDisplay: 初始化完成")
    }

    /**
     * 更新天气显示
     */
    private fun updateWeatherDisplay(weather: WeatherNow) {
        mainHandler.post {
            Log.d("FloatingWindowService", "updateWeatherDisplay: weather.icon=${weather.icon}, weather.text=${weather.text}, weather.temp=${weather.temp}")
            
            val cityName = weatherManager.getCityName()
            val cityPrefix = if (cityName.isNotEmpty()) "$cityName " else ""
            
            weatherText.text = "${cityPrefix}${weather.text} ${weather.temp}°C"

            Log.d("FloatingWindowService", "updateWeatherDisplay: setting icon code ${weather.icon}")
            weatherIconAnimator.setIconCode(weather.icon)
            weatherIconAnimator.setOnFrameUpdateListener { drawable ->
                weatherAnimation.setImageDrawable(drawable)
            }
            weatherIconAnimator.startAnimation()
        }
    }

    /**
     * 更新天气动画
     */
    private fun updateWeatherAnimation(weatherType: WeatherType) {
        try {
            val gifDrawable = GifDrawable(resources, R.drawable.sunshine)
            weatherAnimation.setImageDrawable(gifDrawable)
            gifDrawable.loopCount = 0
            gifDrawable.start()
        } catch (e: Exception) {
            e.printStackTrace()
            weatherAnimation.setImageResource(R.drawable.sunshine)
        }
    }

    /**
     * 初始化波浪动画
     */
    private fun initWaveAnimation() {
        // 启动波浪动画
        voiceWaveView.startWaveAnimation()

        // 添加初始欢迎消息
        addSpeechMessage("会会正在为您服务，有什么需要吩咐...")

        Log.d("FloatingWindowService", "波浪动画初始化完成")
    }

    /**
     * 设置波浪相关事件
     */
    private fun setupWaveEvents() {
        // 波浪视图点击事件
        voiceWaveView.setOnClickListener {
            // 切换波浪主题
            voiceWaveView.toggleTheme()

            // 显示主题切换提示
            val theme = voiceWaveView.getCurrentTheme()
            val themeName = when (theme) {
                VoiceWaveView.WaveTheme.BLUE_OCEAN -> "蓝色海洋"
                VoiceWaveView.WaveTheme.GREEN_FOREST -> "绿色森林"
                VoiceWaveView.WaveTheme.PURPLE_NIGHT -> "紫色夜晚"
                VoiceWaveView.WaveTheme.GOLDEN_SUNSET -> "金色日落"
                VoiceWaveView.WaveTheme.ICE_COLD -> "冰蓝冷色"
            }

            addSpeechMessage("波浪主题已切换为: $themeName")
            waveInfoButton.text = "当前主题: $themeName"
        }

        // 主题切换按钮
        waveToggleButton.setOnClickListener {
            voiceWaveView.toggleTheme()
            val theme = voiceWaveView.getCurrentTheme()
            val themeName = when (theme) {
                VoiceWaveView.WaveTheme.BLUE_OCEAN -> "蓝色海洋"
                VoiceWaveView.WaveTheme.GREEN_FOREST -> "绿色森林"
                VoiceWaveView.WaveTheme.PURPLE_NIGHT -> "紫色夜晚"
                VoiceWaveView.WaveTheme.GOLDEN_SUNSET -> "金色日落"
                VoiceWaveView.WaveTheme.ICE_COLD -> "冰蓝冷色"
            }
            waveToggleButton.text = "主题: $themeName"
        }

        // 信息按钮
        waveInfoButton.setOnClickListener {
            val stats = AudioEnergyHolder.getEnergyStats()
            val info = "能量统计: 当前${(stats["current"]!! * 100).toInt()}%, " +
                    "峰值${(stats["peak"]!! * 100).toInt()}%, " +
                    "平均${(stats["average"]!! * 100).toInt()}%"
            addSpeechMessage(info)
        }
    }

    /**
     * 设置能量观察
     */
    private fun setupEnergyObservation() {
        // 移除现有的观察者（如果有）
        energyObservation?.let { observer ->
            AudioEnergyHolder.currentEnergy.removeObserver(observer)
        }

        energyStateObservation?.let { observer ->
            AudioEnergyHolder.energyState.removeObserver(observer)
        }

        // 创建能量值观察者
        energyObservation = Observer { energy ->
            // 在主线程更新波浪视图
            mainHandler.post {
                voiceWaveView.updateEnergy(energy)
                updateEnergyLabel(energy)
            }
        }

        // 创建能量状态观察者
        energyStateObservation = Observer { state ->
            mainHandler.post {
                updateEnergyStateLabel(state)
                adjustUIByEnergyState(state)
            }
        }

        // 开始观察音频能量
        energyObservation?.let { observer ->
            AudioEnergyHolder.currentEnergy.observeForever(observer)
        }

        energyStateObservation?.let { observer ->
            AudioEnergyHolder.energyState.observeForever(observer)
        }

        Log.d("FloatingWindowService", "音频能量观察已设置")
    }

    /**
     * 设置AudioRecorder的PCM数据回调，用于计算能量和声纹识别
     */
    private fun setupAudioRecorderCallbacks() {
        audioRecorder.onPcmDataAvailable = { pcmData ->
            try {
                // 将PCM数据转换为float数组（范围[-1.0, 1.0]）
                val floatData = FloatArray(pcmData.size) {
                    pcmData[it] / 32768.0f
                }

                // 计算音频能量值（RMS - 均方根）
                var sumOfSquares = 0.0
                for (sample in floatData) {
                    sumOfSquares += sample * sample
                }
                val rms = kotlin.math.sqrt(sumOfSquares / floatData.size).toFloat()

                // 更新音频能量（用于波浪动画）
                AudioEnergyHolder.updateEnergy(rms)
                
                // 更新语音能量时间戳（如果能量超过阈值）
                if (rms > 0.01f) {
                    lastVoiceEnergyTime = System.currentTimeMillis()
                }

                // 声纹识别
                if (speakerIdentificationEnabled) {
                    identifySpeaker(floatData)
                }
            } catch (e: Exception) {
                Log.e("FloatingWindowService", "计算能量异常: ${e.message}")
            }
        }

        Log.d("FloatingWindowService", "AudioRecorder回调已设置")
    }

    /**
     * 声纹识别方法
     */
    private fun identifySpeaker(floatData: FloatArray) {
        try {
            if (!speakerIdentificationManager.isReady()) {
                return
            }

            val speakerName = speakerIdentificationManager.processAudio(floatData)

            if (speakerName != null && speakerName.isNotEmpty()) {
                lastIdentifiedSpeakerTime = System.currentTimeMillis()
                lastIdentifiedSpeakerName = speakerName
               // Log.d("FloatingWindowService", "识别到说话人: $speakerName")
            }
        } catch (e: Exception) {
            Log.e("FloatingWindowService", "声纹识别异常: ${e.message}")
        }
    }

    /**
     * 更新能量标签显示
     */
    private fun updateEnergyLabel(energy: Float) {
        val percentage = (energy * 100).toInt()
        energyLabel.text = "音频能量: ${percentage}%"

        // 根据能量级别设置标签颜色
        when {
            percentage > 70 -> energyLabel.setTextColor(Color.parseColor("#FF6B6B")) // 红色（高能量）
            percentage > 40 -> energyLabel.setTextColor(Color.parseColor("#FFD166")) // 黄色（中能量）
            else -> energyLabel.setTextColor(Color.parseColor("#06D6A0")) // 绿色（低能量）
        }
    }

    /**
     * 更新能量状态标签
     */
    private fun updateEnergyStateLabel(state: AudioEnergyHolder.EnergyState) {
        val stateText = when (state) {
            AudioEnergyHolder.EnergyState.SILENT -> "安静"
            AudioEnergyHolder.EnergyState.BACKGROUND -> "背景噪音"
            AudioEnergyHolder.EnergyState.TALKING -> "说话中"
            AudioEnergyHolder.EnergyState.LOUD -> "大声说话"
        }

        energyStateLabel.text = "状态: $stateText"

        // 根据状态设置颜色
        when (state) {
            AudioEnergyHolder.EnergyState.SILENT ->
                energyStateLabel.setTextColor(Color.parseColor("#888888"))
            AudioEnergyHolder.EnergyState.BACKGROUND ->
                energyStateLabel.setTextColor(Color.parseColor("#4ECDC4"))
            AudioEnergyHolder.EnergyState.TALKING ->
                energyStateLabel.setTextColor(Color.parseColor("#FFD166"))
            AudioEnergyHolder.EnergyState.LOUD ->
                energyStateLabel.setTextColor(Color.parseColor("#FF6B6B"))
        }
    }

    /**
     * 根据能量状态调整其他UI元素
     */
    private fun adjustUIByEnergyState(state: AudioEnergyHolder.EnergyState) {
        // 根据状态调整波浪样式
        when (state) {
            AudioEnergyHolder.EnergyState.LOUD -> {
                voiceWaveView.setSpeakingMode(true)
                voiceWaveView.setWaveStyle(
                    amplitudeMultiplier = 1.8f,
                    waveCount = 4,
                    flowSpeed = 0.25f
                )
            }
            AudioEnergyHolder.EnergyState.TALKING -> {
                voiceWaveView.setSpeakingMode(true)
                voiceWaveView.setWaveStyle(
                    amplitudeMultiplier = 1.5f,
                    waveCount = 3,
                    flowSpeed = 0.2f
                )
            }
            AudioEnergyHolder.EnergyState.BACKGROUND -> {
                voiceWaveView.setSpeakingMode(false)
                voiceWaveView.setWaveStyle(
                    amplitudeMultiplier = 1.0f,
                    waveCount = 3,
                    flowSpeed = 0.15f
                )
            }
            AudioEnergyHolder.EnergyState.SILENT -> {
                voiceWaveView.setSilentMode(true)
                voiceWaveView.setWaveStyle(
                    amplitudeMultiplier = 0.5f,
                    waveCount = 2,
                    flowSpeed = 0.1f
                )
            }
        }
    }

    private var assistantAnimator: ValueAnimator? = null

    /**
     * 开始小秘书说话动画
     */
    private fun startAssistantSpeakingAnimation() {
        Log.d("FloatingWindowService", "startAssistantSpeakingAnimation 被调用")
        val assistantImage = floatingView.findViewById<ImageView?>(R.id.assistant_image)
        assistantImage?.let {
            Log.d("FloatingWindowService", "找到小秘书图片，开始动画")
            
            // 停止之前的动画
            stopAssistantSpeakingAnimation()
            
            // 使用ValueAnimator实现持续动画，避免重复创建动画
            assistantAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 400
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Float
                    val translationX = if (value < 0.5f) -10f else 0f
                    val translationY = if (value < 0.5f) 10f else 0f
                    it.translationX = translationX
                    it.translationY = translationY
                }
                
                start()
            }
        } ?: run {
            Log.e("FloatingWindowService", "未找到小秘书图片！")
        }
    }

    /**
     * 停止小秘书说话动画
     */
    private fun stopAssistantSpeakingAnimation() {
        Log.d("FloatingWindowService", "stopAssistantSpeakingAnimation 被调用")
        val assistantImage = floatingView.findViewById<ImageView?>(R.id.assistant_image)
        assistantImage?.let {
            // 停止动画
            assistantAnimator?.cancel()
            assistantAnimator = null
            
            // 恢复正常位置
            it.translationX = 0f
            it.translationY = 0f
            Log.d("FloatingWindowService", "小秘书动画已停止")
        }
    }

    private fun startWakeWordService(reason: String) {
        // 如果正在启动中，跳过
        if (isWakeWordServiceStarting) {
            Log.d("FloatingWindowService", "唤醒词服务正在启动中，跳过启动 ($reason)")
            return
        }
        
        val currentTime = System.currentTimeMillis()
        
        // 检查冷却时间，避免短时间内重复启动
        if (currentTime - lastWakeWordServiceStartTime < WAKE_WORD_SERVICE_START_COOLDOWN) {
            Log.d("FloatingWindowService", "唤醒词服务启动冷却中，跳过启动 ($reason)")
            return
        }
        
        if (!isWakeWordServiceRunning) {
            isWakeWordServiceStarting = true // 标记为正在启动
            addSpeechMessage(reason)
            Log.d("FloatingWindowService", reason)
            val startIntent = Intent(this, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            isWakeWordServiceRunning = true
            lastWakeWordServiceStartTime = currentTime
            
            // 延迟重置启动标志，确保服务已完全启动
            mainHandler.postDelayed({
                isWakeWordServiceStarting = false
            }, WAKE_WORD_SERVICE_START_COOLDOWN)
        }
    }

    private fun stopWakeWordService(reason: String) {
        if (isWakeWordServiceRunning) {
            addSpeechMessage(reason)
            Log.d("FloatingWindowService", reason)
            val stopIntent = Intent(this, WakeWordService::class.java)
            stopService(stopIntent)
            isWakeWordServiceRunning = false
            isWakeWordServiceStarting = false // 重置启动标志
        }
    }

    private fun setupWebSocketCallbacks() {
        // 连接状态变化回调
        wsHandler.onConnectionStateChange = { connected ->
            isConnected = connected
            if (connected) {
                isDisconnecting = false
                lastConnectionTime = System.currentTimeMillis()
                addSpeechMessage("连接成功")
                Log.d("FloatingWindowService", "连接成功")

                // 重置能量数据，清除旧的数据
                AudioEnergyHolder.reset()
                Log.d("FloatingWindowService", "能量数据已重置")

                // 重启波浪动画，确保重连后波浪动画能正常跟随人说话变化
/*                mainHandler.post {
                    voiceWaveView.restart()
                }*/

                // 重新开始观察音频能量
                energyObservation?.let { observer ->
                    AudioEnergyHolder.currentEnergy.observeForever(observer)
                    Log.d("FloatingWindowService", "重新注册能量值观察者")
                }
                energyStateObservation?.let { observer ->
                    AudioEnergyHolder.energyState.observeForever(observer)
                    Log.d("FloatingWindowService", "重新注册能量状态观察者")
                }

              // 如果之前检测过唤醒词，确保启用 opus 编码
              if (isWakeWordDetected) {
                  if (!isRecording) {
                      addSpeechMessage("恢复录音状态")
                      Log.d("FloatingWindowService", "恢复录音状态")
                      isRecording = true
                      
                      // 重新设置PCM数据回调，确保能量计算正常
                      setupAudioRecorderCallbacks()
                  }
                  
                  // 无论录音状态如何，都启用 opus 编码，开始发送数据到服务端
                  audioRecorder.start(enableOpusEncoding = true)
                  addSpeechMessage("开始发送音频数据到服务端")
                  Log.d("FloatingWindowService", "开始发送音频数据到服务端")

                  // 停止唤醒词服务
                  stopWakeWordService("停止唤醒词服务")
              } else {
                  // 如果之前没有检测过唤醒词，也要启用Opus编码以确保语音唤醒正常工作
                  audioRecorder.start(enableOpusEncoding = true)
                  addSpeechMessage("连接成功，已启用Opus编码")
                  Log.d("FloatingWindowService", "连接成功，已启用Opus编码")
              }
          } else {
              isDisconnecting = true
              addSpeechMessage("连接断开")
              Log.d("FloatingWindowService", "连接断开")

              // 断开连接时，移除能量观察者，避免干扰
              energyObservation?.let { observer ->
                  try {
                      AudioEnergyHolder.currentEnergy.removeObserver(observer)
                      Log.d("FloatingWindowService", "移除能量值观察者")
                  } catch (e: Exception) {
                      Log.e("FloatingWindowService", "移除能量观察者异常: ${e.message}")
                  }
              }

              energyStateObservation?.let { observer ->
                  try {
                      AudioEnergyHolder.energyState.removeObserver(observer)
                      Log.d("FloatingWindowService", "移除能量状态观察者")
                  } catch (e: Exception) {
                      Log.e("FloatingWindowService", "移除能量状态观察者异常: ${e.message}")
                  }
              }

              // 断开连接时，重置录音状态
              if (isRecording) {
                  isRecording = false
                  // 停止 opus 编码，但保持录音继续
                  audioRecorder.start(enableOpusEncoding = false)
                  addSpeechMessage("连接断开，停止发送音频数据")
                  Log.d("FloatingWindowService", "连接断开，停止发送音频数据")
              }
              // 重新启动唤醒词服务
              startWakeWordService("断开连接，重新启动唤醒词服务")
          }
      }

      // 录音按钮状态变化回调
      wsHandler.onRecordButtonStateChange = { recording ->
          isRecording = recording
          if (recording) {
              isDisconnecting = false
              // 开始录音时，停止唤醒词服务
              stopWakeWordService("开始录音，停止唤醒词服务")
          } else {
              // 只有在非断开连接的情况下才重置唤醒词检测状态
              if (!isDisconnecting) {
                  isWakeWordDetected = false
              }
              // 如果没有连接服务器，重新启动唤醒词服务
              if (!isConnected) {
                  addSpeechMessage("录音停止，断开连接，重新启动唤醒词服务")
                  Log.d("FloatingWindowService", "录音停止，断开连接，重新启动唤醒词服务")
                  startWakeWordService("唤醒词服务已重新启动")
              }
          }
      }

      wsHandler.onTextMessage = { text, isUser ->
          Log.d("FloatingWindowService", "收到文本消息: $text, isUser: $isUser")

          if (isUser) {
              // 用户说的话，显示在文字区域
              addSpeechMessage("您: $text")

              // 用户说话时增强波浪效果
              mainHandler.post {
                  voiceWaveView.setSpeakingMode(true)
              }
          } else {
              // 检查是否在休息模式
              if (isTerminalSleepMode) {
                  Log.d("FloatingWindowService", "休息模式下，忽略助手消息: $text")
              } else {
                  // 检查是否在会议中，会议中不回显助手消息
                  val isInMeeting = FloatingWindowService.getInMeeting()
                  if (!isInMeeting) {
                      // 助手说的话
                      addSpeechMessage("会会: $text")

                      // 助手回复时使用不同的波浪模式
                      mainHandler.post {
                        //  Log.d("FloatingWindowService", "收到助手消息，设置波浪模式")
                          voiceWaveView.setAssistantSpeakingMode(true)

                          // 5秒后恢复普通模式
                          mainHandler.postDelayed({
                              //Log.d("FloatingWindowService", "5秒后恢复波浪模式")
                              voiceWaveView.setAssistantSpeakingMode(false)
                          }, 5000)
                      }
                  } else {
                      Log.d("FloatingWindowService", "当前在会议中，跳过助手消息回显: $text")
                  }
              }
          }
      }

      wsHandler.onSessionStateChange = { isSpeaking ->
          Log.d("FloatingWindowService", "会话状态改变: $isSpeaking")

          // 如果是重连后3秒内的会话状态变化，忽略它
          val timeSinceConnection = System.currentTimeMillis() - lastConnectionTime
          if (timeSinceConnection >= 3000) {
              if (isSpeaking == true) {
                  // 助手正在说话
                  mainHandler.post {
                      statusTextView.text = "正在回复..."
                  }

                  // 助手说话时增强波浪效果
                  mainHandler.post {
                      voiceWaveView.setAssistantSpeakingMode(true)

                      // 开始小秘书说话动画
                      isAssistantSpeaking = true
                      startAssistantSpeakingAnimation()
                  }
              } else {
                  // 停止说话时恢复正常模式
                  mainHandler.post {
                      voiceWaveView.setSpeakingMode(false)
                      voiceWaveView.setAssistantSpeakingMode(false)
                      statusTextView.text = ""

                      // 停止小秘书说话动画
                      isAssistantSpeaking = false
                      stopAssistantSpeakingAnimation()
                  }
              }
          } else {
              Log.d("FloatingWindowService", "忽略重连后3秒内的会话状态变化")
          }
      }

      // 会议查询回调
      wsHandler.onMeetingQuery = { targetDate ->
          Log.d("FloatingWindowService", "收到会议查询回调，目标日期: $targetDate")
          handleMeetingQuery(targetDate)
      }
  }

  private fun handleMeetingQuery(targetDate: java.util.Date?) {
      mainHandler.post {
          if (targetDate != null) {
              // 保存原始标题
              originalScheduleTitle = "今日日程"

              // 更新标题为"查询目标日行程"
              val scheduleTitle = floatingView.findViewById<TextView>(R.id.schedule_title)
              scheduleTitle.text = "查询目标日日程"

              // 更新日历日期显示
              val targetCalendar = java.util.Calendar.getInstance()
              targetCalendar.time = targetDate
              val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm", Locale.CHINA)
              dateFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
              val targetDateStr = dateFormat.format(targetDate)
              calendarDate.text = targetDateStr

              // 在日历中高亮目标日期
              calendarView.queryDate(
                  targetCalendar.get(java.util.Calendar.YEAR),
                  targetCalendar.get(java.util.Calendar.MONTH) + 1,
                  targetCalendar.get(java.util.Calendar.DAY_OF_MONTH)
              )

              // 加载目标日期的会议
              scheduleList.loadSchedulesForDate(targetDate)

              Log.d("FloatingWindowService", "已更新UI显示目标日期会议: $targetDateStr")

              // 设置定时器，30秒后恢复原始状态
              restoreRunnable?.let { mainHandler.removeCallbacks(it) }
              restoreRunnable = Runnable {
                  restoreOriginalUI()
              }
              mainHandler.postDelayed(restoreRunnable!!, 30000)
          } else {
              Log.d("FloatingWindowService", "目标日期为空，不更新UI")
          }
      }
  }

  private fun restoreOriginalUI() {
      mainHandler.post {
          // 恢复标题为"今日行程"
          val scheduleTitle = floatingView.findViewById<TextView>(R.id.schedule_title)
          scheduleTitle.text = originalScheduleTitle

          // 恢复日历日期显示为今天
          val today = Date()
          val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm", Locale.CHINA)
          dateFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
          val todayStr = dateFormat.format(today)
          calendarDate.text = todayStr

          // 刷新日历视图（会自动取消红色高亮）
          val calendar = java.util.Calendar.getInstance()
          calendarView.setMonth(calendar.get(java.util.Calendar.YEAR), calendar.get(java.util.Calendar.MONTH) + 1)

          // 加载今天的会议
          scheduleList.loadSchedulesForDate(today)

          Log.d("FloatingWindowService", "已恢复原始UI状态")
      }
  }

  /**
   * 启动日期更新定时器
   * 每分钟更新一次日期和日历显示，确保长时间挂机后日期信息正确
   * 只有在显示"今日行程"时才更新，避免干扰用户查询其他日期
   */
  private fun startDateUpdateTimer() {
      updateDateRunnable?.let { mainHandler.removeCallbacks(it) }
      
      updateDateRunnable = object : Runnable {
          override fun run() {
              try {
                  // 检查当前是否显示"今日行程"
                  val scheduleTitle = floatingView.findViewById<TextView>(R.id.schedule_title)
                  val isTodaySchedule = scheduleTitle.text == "今日日程"
                  
                  if (isTodaySchedule) {
                      // 更新日期显示
                      val dateFormat = SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm", Locale.CHINA)
                      dateFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
                      val currentDate = dateFormat.format(Date())
                      calendarDate.text = currentDate
                      
                      // 更新日历视图为当前月份
                      val calendar = Calendar.getInstance()
                      calendarView.setMonth(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1)
                      
                      // 加载今天的会议
                      scheduleList.loadSchedulesForDate(Date())
                      
                      Log.d("FloatingWindowService", "日期和日历已更新: $currentDate")
                  } else {
                      Log.d("FloatingWindowService", "当前显示查询目标日行程，跳过日期更新")
                  }
              } catch (e: Exception) {
                  Log.e("FloatingWindowService", "更新日期和日历失败: ${e.message}", e)
              }
              
              // 每分钟执行一次
              mainHandler.postDelayed(this, 60000)
          }
      }
      
      mainHandler.postDelayed(updateDateRunnable!!, 60000)
      Log.d("FloatingWindowService", "日期更新定时器已启动")
  }

  /**
   * 启动IP更新定时器
   * 每5秒尝试更新一次本地IP，直到成功获取到IP为止
   * 确保程序启动后能尽快显示本地IP
   */
  private fun startIpUpdateTimer() {
      updateIpRunnable?.let { mainHandler.removeCallbacks(it) }
      
      updateIpRunnable = object : Runnable {
          override fun run() {
              try {
                  val currentLocalIp = getLocalIpAddress()
                  val currentDisplay = localIpTextView.text.toString()
                  val expectedPrefix = "本地IP: "
                  
                  if (currentDisplay.startsWith(expectedPrefix)) {
                      val currentIpValue = currentDisplay.substring(expectedPrefix.length)
                      
                      if (currentIpValue == "未知" || currentIpValue.isEmpty()) {
                          updateIpDisplay()
                          Log.d("FloatingWindowService", "IP更新定时器: 尝试更新IP显示")
                      } else {
                          Log.d("FloatingWindowService", "IP更新定时器: 本地IP已获取: $currentIpValue，停止定时器")
                          return
                      }
                  } else {
                      updateIpDisplay()
                      Log.d("FloatingWindowService", "IP更新定时器: 初始化IP显示")
                  }
              } catch (e: Exception) {
                  Log.e("FloatingWindowService", "IP更新定时器执行失败: ${e.message}", e)
              }
              
              // 每5秒执行一次
              mainHandler.postDelayed(this, 5000)
          }
      }
      
      mainHandler.postDelayed(updateIpRunnable!!, 5000)
      Log.d("FloatingWindowService", "IP更新定时器已启动")
  }

  private fun addSpeechMessage(message: String) {
      mainHandler.post {
          // 添加到消息列表
          speechMessages.add(message)

          // 如果消息太多，保留最近10条
          if (speechMessages.size > 10) {
              speechMessages.removeAt(0)
          }

          // 更新显示
          updateSpeechDisplay()
      }
  }

  /**
   * 显示会议查询结果
   * @param currentMeetings 正在进行的会议信息
   * @param upcomingMeetings 即将开始的会议信息
   * @param pastMeetings 已过时的会议信息
   * @param recommendation 推荐信息
   */
  private fun showMeetingResult(currentMeetings: String, upcomingMeetings: String, pastMeetings: String, recommendation: String) {
      // 检查数据是否发生变化（包括 isInMeeting 状态）
      if (currentMeetings == lastCurrentMeetings && 
          upcomingMeetings == lastUpcomingMeetings && 
          pastMeetings == lastPastMeetings && 
          recommendation == lastRecommendation &&
          isInMeeting == lastIsInMeeting) {
          //Log.d("FloatingWindowService", "会议数据未变化，跳过刷新")
          return
      }
      
      // 更新缓存
      lastCurrentMeetings = currentMeetings
      lastUpcomingMeetings = upcomingMeetings
      lastPastMeetings = pastMeetings
      lastRecommendation = recommendation
      lastIsInMeeting = isInMeeting
      
      Log.d("FloatingWindowService", "会议数据已变化，开始刷新界面")
      
      mainHandler.post {
          // 根据用户是否在会议中决定是否显示会议查询结果
          if (isInMeeting) {
              Log.d("FloatingWindowService", "用户正在会议中，隐藏会议查询结果区域")
              meetingResultContainer.visibility = View.INVISIBLE
              return@post
          }
          
          Log.d("FloatingWindowService", "用户不在会议中，显示会议查询结果区域")
          meetingResultContainer.visibility = View.VISIBLE

          meetingResultCurrentContainer.visibility = View.VISIBLE
          meetingResultCurrentScroll.visibility = View.VISIBLE
          meetingResultCurrentContent.removeAllViews()
          
          if (currentMeetings.isNotEmpty()) {
              try {
                  val jsonArray = JSONArray(currentMeetings)
                  if (jsonArray.length() > 0) {
                      for (i in 0 until jsonArray.length()) {
                          val meetingObj = jsonArray.getJSONObject(i)
                          val cardView = createMeetingCard(meetingObj, true)
                          meetingResultCurrentContent.addView(cardView)
                      }
                  } else {
                      val emptyCard = android.widget.LinearLayout(this).apply {
                          orientation = android.widget.LinearLayout.VERTICAL
                          setPadding(8, 8, 8, 8)
                          layoutParams = android.widget.LinearLayout.LayoutParams(
                              android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                              android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                          ).apply {
                              bottomMargin = 6
                          }
                          background = android.graphics.drawable.GradientDrawable().apply {
                              setColor(android.graphics.Color.parseColor("#E8F5E9"))
                              cornerRadius = 8f
                              setStroke(1, android.graphics.Color.parseColor("#4CAF50"))
                          }
                      }
                      emptyCard.addView(TextView(this).apply {
                          text = "暂无当前会议"
                          textSize = 10f
                          setTextColor(android.graphics.Color.parseColor("#757575"))
                          gravity = android.view.Gravity.CENTER
                          setPadding(0, 16, 0, 16)
                      })
                      meetingResultCurrentContent.addView(emptyCard)
                  }
              } catch (e: Exception) {
                  Log.e("FloatingWindowService", "解析当前会议信息失败: ${e.message}")
                  val emptyCard = android.widget.LinearLayout(this).apply {
                      orientation = android.widget.LinearLayout.VERTICAL
                      setPadding(8, 8, 8, 8)
                      layoutParams = android.widget.LinearLayout.LayoutParams(
                          android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                          android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                      ).apply {
                          bottomMargin = 6
                      }
                      background = android.graphics.drawable.GradientDrawable().apply {
                          setColor(android.graphics.Color.parseColor("#E8F5E9"))
                          cornerRadius = 8f
                          setStroke(1, android.graphics.Color.parseColor("#4CAF50"))
                      }
                  }
                  emptyCard.addView(TextView(this).apply {
                      text = "暂无当前会议"
                      textSize = 10f
                      setTextColor(android.graphics.Color.parseColor("#757575"))
                      gravity = android.view.Gravity.CENTER
                      setPadding(0, 16, 0, 16)
                  })
                  meetingResultCurrentContent.addView(emptyCard)
              }
          } else {
              val emptyCard = android.widget.LinearLayout(this).apply {
                  orientation = android.widget.LinearLayout.VERTICAL
                  setPadding(8, 8, 8, 8)
                  layoutParams = android.widget.LinearLayout.LayoutParams(
                      android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                      android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                  ).apply {
                      bottomMargin = 6
                  }
                  background = android.graphics.drawable.GradientDrawable().apply {
                      setColor(android.graphics.Color.parseColor("#E8F5E9"))
                      cornerRadius = 8f
                      setStroke(1, android.graphics.Color.parseColor("#4CAF50"))
                  }
              }
              emptyCard.addView(TextView(this).apply {
                  text = "暂无当前会议"
                  textSize = 10f
                  setTextColor(android.graphics.Color.parseColor("#757575"))
                  gravity = android.view.Gravity.CENTER
                  setPadding(0, 16, 0, 16)
              })
              meetingResultCurrentContent.addView(emptyCard)
          }

          meetingResultUpcomingContainer.visibility = View.VISIBLE
          meetingResultUpcomingScroll.visibility = View.VISIBLE
          meetingResultUpcomingContent.removeAllViews()
          
          if (upcomingMeetings.isNotEmpty()) {
              try {
                  val jsonArray = JSONArray(upcomingMeetings)
                  if (jsonArray.length() > 0) {
                      for (i in 0 until jsonArray.length()) {
                          val meetingObj = jsonArray.getJSONObject(i)
                          val cardView = createMeetingCard(meetingObj, false)
                          meetingResultUpcomingContent.addView(cardView)
                      }
                  } else {
                      val emptyCard = android.widget.LinearLayout(this).apply {
                          orientation = android.widget.LinearLayout.VERTICAL
                          setPadding(8, 8, 8, 8)
                          layoutParams = android.widget.LinearLayout.LayoutParams(
                              android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                              android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                          ).apply {
                              bottomMargin = 6
                          }
                          background = android.graphics.drawable.GradientDrawable().apply {
                              setColor(android.graphics.Color.parseColor("#FFF3E0"))
                              cornerRadius = 8f
                              setStroke(1, android.graphics.Color.parseColor("#FF9800"))
                          }
                      }
                      emptyCard.addView(TextView(this).apply {
                          text = "暂无即将开始的会议"
                          textSize = 10f
                          setTextColor(android.graphics.Color.parseColor("#757575"))
                          gravity = android.view.Gravity.CENTER
                          setPadding(0, 16, 0, 16)
                      })
                      meetingResultUpcomingContent.addView(emptyCard)
                  }
              } catch (e: Exception) {
                  Log.e("FloatingWindowService", "解析即将开始的会议信息失败: ${e.message}")
                  val emptyCard = android.widget.LinearLayout(this).apply {
                      orientation = android.widget.LinearLayout.VERTICAL
                      setPadding(8, 8, 8, 8)
                      layoutParams = android.widget.LinearLayout.LayoutParams(
                          android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                          android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                      ).apply {
                          bottomMargin = 6
                      }
                      background = android.graphics.drawable.GradientDrawable().apply {
                          setColor(android.graphics.Color.parseColor("#FFF3E0"))
                          cornerRadius = 8f
                          setStroke(1, android.graphics.Color.parseColor("#FF9800"))
                      }
                  }
                  emptyCard.addView(TextView(this).apply {
                      text = "暂无即将开始的会议"
                      textSize = 10f
                      setTextColor(android.graphics.Color.parseColor("#757575"))
                      gravity = android.view.Gravity.CENTER
                      setPadding(0, 16, 0, 16)
                  })
                  meetingResultUpcomingContent.addView(emptyCard)
              }
          } else {
              val emptyCard = android.widget.LinearLayout(this).apply {
                  orientation = android.widget.LinearLayout.VERTICAL
                  setPadding(8, 8, 8, 8)
                  layoutParams = android.widget.LinearLayout.LayoutParams(
                      android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                      android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                  ).apply {
                      bottomMargin = 6
                  }
                  background = android.graphics.drawable.GradientDrawable().apply {
                      setColor(android.graphics.Color.parseColor("#FFF3E0"))
                      cornerRadius = 8f
                      setStroke(1, android.graphics.Color.parseColor("#FF9800"))
                  }
              }
              emptyCard.addView(TextView(this).apply {
                  text = "暂无即将开始的会议"
                  textSize = 10f
                  setTextColor(android.graphics.Color.parseColor("#757575"))
                  gravity = android.view.Gravity.CENTER
                  setPadding(0, 16, 0, 16)
              })
              meetingResultUpcomingContent.addView(emptyCard)
          }

          meetingResultPastContainer.visibility = View.VISIBLE
          meetingResultPastScroll.visibility = View.VISIBLE
          meetingResultPastContent.removeAllViews()
          
          if (pastMeetings.isNotEmpty()) {
              try {
                  val jsonArray = JSONArray(pastMeetings)
                  if (jsonArray.length() > 0) {
                      for (i in 0 until jsonArray.length()) {
                          val meetingObj = jsonArray.getJSONObject(i)
                          val cardView = createMeetingCard(meetingObj, false)
                          meetingResultPastContent.addView(cardView)
                      }
                  } else {
                      val emptyCard = android.widget.LinearLayout(this).apply {
                          orientation = android.widget.LinearLayout.VERTICAL
                          setPadding(8, 8, 8, 8)
                          layoutParams = android.widget.LinearLayout.LayoutParams(
                              android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                              android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                          ).apply {
                              bottomMargin = 6
                          }
                          background = android.graphics.drawable.GradientDrawable().apply {
                              setColor(android.graphics.Color.parseColor("#FFF3E0"))
                              cornerRadius = 8f
                              setStroke(1, android.graphics.Color.parseColor("#FF9800"))
                          }
                      }
                      emptyCard.addView(TextView(this).apply {
                          text = "暂无已过时的会议"
                          textSize = 10f
                          setTextColor(android.graphics.Color.parseColor("#757575"))
                          gravity = android.view.Gravity.CENTER
                          setPadding(0, 16, 0, 16)
                      })
                      meetingResultPastContent.addView(emptyCard)
                  }
              } catch (e: Exception) {
                  Log.e("FloatingWindowService", "解析已过时的会议信息失败: ${e.message}")
                  val emptyCard = android.widget.LinearLayout(this).apply {
                      orientation = android.widget.LinearLayout.VERTICAL
                      setPadding(8, 8, 8, 8)
                      layoutParams = android.widget.LinearLayout.LayoutParams(
                          android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                          android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                      ).apply {
                          bottomMargin = 6
                      }
                      background = android.graphics.drawable.GradientDrawable().apply {
                          setColor(android.graphics.Color.parseColor("#FFF3E0"))
                          cornerRadius = 8f
                          setStroke(1, android.graphics.Color.parseColor("#FF9800"))
                      }
                  }
                  emptyCard.addView(TextView(this).apply {
                      text = "暂无已过时的会议"
                      textSize = 10f
                      setTextColor(android.graphics.Color.parseColor("#757575"))
                      gravity = android.view.Gravity.CENTER
                      setPadding(0, 16, 0, 16)
                  })
                  meetingResultPastContent.addView(emptyCard)
              }
          } else {
              val emptyCard = android.widget.LinearLayout(this).apply {
                  orientation = android.widget.LinearLayout.VERTICAL
                  setPadding(8, 8, 8, 8)
                  layoutParams = android.widget.LinearLayout.LayoutParams(
                      android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                      android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                  ).apply {
                      bottomMargin = 6
                  }
                  background = android.graphics.drawable.GradientDrawable().apply {
                      setColor(android.graphics.Color.parseColor("#FFF3E0"))
                      cornerRadius = 8f
                      setStroke(1, android.graphics.Color.parseColor("#FF9800"))
                  }
              }
              emptyCard.addView(TextView(this).apply {
                  text = "暂无已过时的会议"
                  textSize = 10f
                  setTextColor(android.graphics.Color.parseColor("#757575"))
                  gravity = android.view.Gravity.CENTER
                  setPadding(0, 16, 0, 16)
              })
              meetingResultPastContent.addView(emptyCard)
          }

          if (recommendation.isNotEmpty()) {
              meetingResultRecommendationContainer.visibility = View.VISIBLE
              meetingResultRecommendation.text = recommendation
          } else {
              meetingResultRecommendationContainer.visibility = View.GONE
          }
      }
  }

  /**
   * 创建会议卡片视图
   */
  private fun createMeetingCard(meetingObj: JSONObject, isCurrent: Boolean): View {
      val confStatus = meetingObj.optInt("conf_status", 0)
      val isEnded = confStatus == 3
      val isOngoing = confStatus == 2
      
      val card = android.widget.LinearLayout(this).apply {
          orientation = android.widget.LinearLayout.VERTICAL
          setPadding(8, 8, 8, 8)
          layoutParams = android.widget.LinearLayout.LayoutParams(
              android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
              android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
          ).apply {
              bottomMargin = 6
          }
          background = when {
              isOngoing || isEnded -> android.graphics.drawable.GradientDrawable().apply {
                  setColor(android.graphics.Color.parseColor("#E8F5E9"))
                  cornerRadius = 8f
                  setStroke(1, android.graphics.Color.parseColor("#4CAF50"))
              }
              else -> android.graphics.drawable.GradientDrawable().apply {
                  setColor(android.graphics.Color.parseColor("#FFF3E0"))
                  cornerRadius = 8f
                  setStroke(1, android.graphics.Color.parseColor("#FF9800"))
              }
          }
      }

      val titleText = meetingObj.optString("conf_name", "未知会议")
      val startTime = meetingObj.optString("start_time", "")
      val duration = meetingObj.optInt("duration", 0)
      val confNumber = meetingObj.optString("conf_number", "")

      card.addView(TextView(this).apply {
          text = titleText
          textSize = 11f
          setTypeface(null, android.graphics.Typeface.BOLD)
          setTextColor(android.graphics.Color.parseColor("#212121"))
          layoutParams = android.widget.LinearLayout.LayoutParams(
              android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
              android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
          ).apply {
              bottomMargin = 4
          }
      })

      card.addView(TextView(this).apply {
          val durationText = if (duration > 0) "时长: ${duration}分钟" else ""
          val timeText = if (startTime.isNotEmpty()) {
              try {
                  val inputFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                  inputFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
                  val date = inputFormat.parse(startTime)
                  
                  if (date != null) {
                      val outputFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                      outputFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                      "时间: ${outputFormat.format(date)}"
                  } else {
                      "时间: $startTime"
                  }
              } catch (e: Exception) {
                  "时间: $startTime"
              }
          } else ""
          text = listOfNotNull(timeText, durationText, if (confNumber.isNotEmpty()) "会议号: $confNumber" else null)
              .joinToString(" | ")
          textSize = 10f
          setTextColor(android.graphics.Color.parseColor("#757575"))
      })

      return card
  }

  /**
   * 显示会议报告总结
   */
  private fun showMeetingReports() {
      mainHandler.post {
          try {
              val reportManager = com.minrray.voicectl.core.meeting.MeetingReportManager.getInstance()
              val reports = reportManager.getAllReports(5)
              
              meetingReportContainer.visibility = View.VISIBLE
              meetingReportScroll.visibility = View.VISIBLE
              meetingReportContent.removeAllViews()
              
              val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
              dateFormat.timeZone = TimeZone.getTimeZone("Asia/Shanghai")
              
              if (reports.isNotEmpty()) {
                  reports.forEach { report ->
                      val cardView = android.widget.LinearLayout(this).apply {
                          orientation = android.widget.LinearLayout.VERTICAL
                          setPadding(8, 8, 8, 8)
                          layoutParams = android.widget.LinearLayout.LayoutParams(
                              android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                              android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                          ).apply {
                              bottomMargin = 6
                          }
                          background = android.graphics.drawable.GradientDrawable().apply {
                              setColor(android.graphics.Color.parseColor("#FFFFFF"))
                              cornerRadius = 8f
                              setStroke(1, android.graphics.Color.parseColor("#E0E0E0"))
                          }
                          
                          val meetingTimeText = if (report.meetingTime > 0) {
                              dateFormat.format(Date(report.meetingTime))
                          } else {
                              "未知时间"
                          }
                          
                          val summaryText = if (report.files.isNotEmpty()) {
                              "查看摘要 (${report.files.size}个文件)"
                          } else {
                              "暂无摘要"
                          }
                          
                          addView(TextView(this@FloatingWindowService).apply {
                              text = "时间: $meetingTimeText"
                              textSize = 10f
                              setTextColor(Color.parseColor("#757575"))
                          })
                          
                          addView(TextView(this@FloatingWindowService).apply {
                              text = "主题: ${report.topic}"
                              textSize = 11f
                              setTextColor(Color.parseColor("#212121"))
                              setTypeface(null, Typeface.BOLD)
                              setPadding(0, 4, 0, 4)
                          })
                          
                          addView(TextView(this@FloatingWindowService).apply {
                              text = summaryText
                              textSize = 10f
                              setTextColor(Color.parseColor("#2196F3"))
                              isClickable = true
                              setOnClickListener {
                                  if (report.files.isNotEmpty()) {
                                      val fileUrls = report.files.map { it.downloadUrl }
                                      addSpeechMessage("会议摘要链接: ${fileUrls.joinToString(", ")}")
                                  } else {
                                      addSpeechMessage("该会议暂无摘要内容")
                                  }
                              }
                          })
                      }
                      
                      meetingReportContent.addView(cardView)
                  }
              } else {
                  val emptyView = TextView(this).apply {
                      text = "暂无会议报告"
                      textSize = 10f
                      setTextColor(Color.parseColor("#757575"))
                      gravity = android.view.Gravity.CENTER
                      setPadding(16, 16, 16, 16)
                  }
                  meetingReportContent.addView(emptyView)
              }
          } catch (e: Exception) {
              Log.e("FloatingWindowService", "显示会议报告失败: ${e.message}", e)
              meetingReportContainer.visibility = View.VISIBLE
              meetingReportScroll.visibility = View.VISIBLE
              meetingReportContent.removeAllViews()
              val errorView = TextView(this).apply {
                  text = "加载会议报告失败"
                  textSize = 10f
                  setTextColor(Color.parseColor("#757575"))
                  gravity = android.view.Gravity.CENTER
                  setPadding(16, 16, 16, 16)
              }
              meetingReportContent.addView(errorView)
          }
      }
  }

  /**
   * 启动统一定时器更新会议信息
   * 合并了原来的三个定时器功能：
   * 1. 查询平台会议信息
   * 2. 检查会议状态
   * 3. 更新会议报告
   */
  private fun startUnifiedMeetingUpdateTimer() {
      meetingUpdateRunnable?.let {
          mainHandler.removeCallbacks(it)
      }
      
      meetingUpdateRunnable = object : Runnable {
          override fun run() {
              try {
                  // 1. 查询平台会议信息
                  queryPlatformMeetingInfo()
                  
                  // 2. 更新会议报告（本地数据，不需要防重复查询）
                  showMeetingReports()
              } catch (e: Exception) {
                  Log.e("FloatingWindowService", "统一定时器更新失败: ${e.message}", e)
              }
              // 间隔10秒更新一次
              mainHandler.postDelayed(this, 10 * 1000L)
          }
      }
      
      mainHandler.postDelayed(meetingUpdateRunnable!!, 0)
      Log.d("FloatingWindowService", "已启动统一定时器更新会议信息，间隔10s")
  }

  /**
   * 启动自动监听检查定时器
   */
  private fun startAutoListenCheckTimer() {
      autoListenCheckRunnable?.let {
          mainHandler.removeCallbacks(it)
      }
      
      autoListenCheckRunnable = object : Runnable {
          override fun run() {
              try {
                  checkAutoListenConditions()
              } catch (e: Exception) {
                  Log.e("FloatingWindowService", "自动监听检查失败: ${e.message}", e)
              }
              // 间隔500ms检查一次
              mainHandler.postDelayed(this, AUTO_LISTEN_CHECK_INTERVAL)
          }
      }
      
      mainHandler.postDelayed(autoListenCheckRunnable!!, 0)
      Log.d("FloatingWindowService", "已启动自动监听检查定时器，间隔${AUTO_LISTEN_CHECK_INTERVAL}ms")
  }

  /**
   * 启动定时查询平台会议信息
   * @deprecated 使用 startUnifiedMeetingUpdateTimer 替代
   */
  @Deprecated("使用 startUnifiedMeetingUpdateTimer 替代")
  private fun startPlatformMeetingQueryTimer() {
      platformMeetingQueryRunnable?.let {
          mainHandler.removeCallbacks(it)
      }
      
      platformMeetingQueryRunnable = object : Runnable {
          override fun run() {
              try {
                  queryPlatformMeetingInfo()
              } catch (e: Exception) {
                  Log.e("FloatingWindowService", "定时查询平台会议信息失败: ${e.message}", e)
              }
              // 间隔10秒查询一次
              mainHandler.postDelayed(this, 10 * 1000L)
          }
      }
      
      mainHandler.postDelayed(platformMeetingQueryRunnable!!, 0)
      Log.d("FloatingWindowService", "已启动定时查询平台会议信息，间隔10s")
  }

  /**
   * 启动定时更新会议报告
   * @deprecated 使用 startUnifiedMeetingUpdateTimer 替代
   */
  @Deprecated("使用 startUnifiedMeetingUpdateTimer 替代")
  private fun startMeetingReportUpdateTimer() {
      meetingReportUpdateRunnable?.let {
          mainHandler.removeCallbacks(it)
      }
      
      meetingReportUpdateRunnable = object : Runnable {
          override fun run() {
              try {
                  Log.d("FloatingWindowService", "定时更新会议报告")
                  showMeetingReports()
              } catch (e: Exception) {
                  Log.e("FloatingWindowService", "定时更新会议报告失败: ${e.message}", e)
              }
              // 间隔10秒更新一次
              mainHandler.postDelayed(this, 10 * 1000L)
          }
      }
      
      // 延迟2秒启动，错开查询时间
      mainHandler.postDelayed(meetingReportUpdateRunnable!!, 2 * 1000L)
      Log.d("FloatingWindowService", "已启动定时更新会议报告，间隔10s，延迟2s启动")
  }

  /**
   * 查询平台会议信息
   */
  private fun queryPlatformMeetingInfo() {
      // 防止重复查询
      if (isQueryingMeeting) {
          Log.d("FloatingWindowService", "正在查询会议信息，跳过本次查询")
          return
      }
      
      isQueryingMeeting = true
      
      kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
          try {
              val mcpToolsManager = com.minrray.voicectl.core.mcp.McpToolsManager.getInstance()
              val toolArgs = org.json.JSONObject().apply {
                  put("site_sign", "kexinyun")
                  put("page_no", 1)
                  put("page_size", 10)
              }
              val result = mcpToolsManager.executeMcpTool("self.get_site_meeting_info", toolArgs)
              
              if (result.optBoolean("success", false)) {
                  val currentMeetings = result.optJSONArray("current_meetings") ?: org.json.JSONArray()
                  val upcomingMeetings = result.optJSONArray("upcoming_meetings") ?: org.json.JSONArray()
                  val pastMeetings = result.optJSONArray("past_meetings") ?: org.json.JSONArray()
                  val recommendation = result.optString("recommendation", "")
                  
                  Log.d("FloatingWindowService", "查询到${currentMeetings.length()}个当前会议，${upcomingMeetings.length()}个即将开始的会议，${pastMeetings.length()}个已过时的会议")
                  
                  if (currentMeetings.length() == 0) {
                      val meetingScheduleManager = com.minrray.voicectl.core.meeting.MeetingScheduleManager.getInstance()
                      val allSchedules = meetingScheduleManager.getAllMeetingSchedules()
                      val ongoingMeetings = allSchedules.filter { it.confStatus == 2 }
                      
                      if (ongoingMeetings.isNotEmpty()) {
                          Log.d("FloatingWindowService", "平台无正在召开的会议，但本地有${ongoingMeetings.size}个状态为进行中的会议，将更新为已结束")
                          ongoingMeetings.forEach { schedule ->
                              meetingScheduleManager.updateMeetingStatus(schedule.cloudConfNumber, 3)
                              Log.d("FloatingWindowService", "会议状态已更新为已结束: ${schedule.meetingNumber}")
                          }
                      }
                  }
                  
                  showMeetingResult(currentMeetings.toString(), upcomingMeetings.toString(), pastMeetings.toString(), recommendation)
              } else {
                  Log.w("FloatingWindowService", "查询平台会议信息失败: ${result.optString("error")}")
              }
          } catch (e: Exception) {
              Log.e("FloatingWindowService", "查询平台会议信息异常: ${e.message}", e)
          } finally {
              isQueryingMeeting = false
          }
      }
  }

  fun updateSpeechDisplay() {
      // 如果正在声纹注册中，不更新显示
      if (isSpeakerRegistering) {
          return
      }
      
      // 如果消息列表为空，显示默认消息
      if (speechMessages.isEmpty()) {
          speechTextView.text = "您好！我是会会，竭诚为您服务..."
          return
      }

      // 使用SpannableStringBuilder来为不同消息设置不同样式
      val spannableBuilder = SpannableStringBuilder()

      for ((index, message) in speechMessages.withIndex()) {
          val spannableMessage = SpannableString("$message\n")

          // 根据消息类型设置不同样式
          if (message.startsWith("您:")) {
              // 用户消息：橙黄色，加粗，字体稍大
              spannableMessage.setSpan(
                  ForegroundColorSpan(Color.parseColor("#FFB74D")),
                  0,
                  message.length,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
              )
              spannableMessage.setSpan(
                  RelativeSizeSpan(1.1f),
                  0,
                  message.length,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
              )
              spannableMessage.setSpan(
                  StyleSpan(Typeface.BOLD),
                  0,
                  message.length,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
              )
          } else if (message.startsWith("会会:")) {
              // 助手消息：青绿色，正常字体
              spannableMessage.setSpan(
                  ForegroundColorSpan(Color.parseColor("#4DB6AC")),
                  0,
                  message.length,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
              )
          } else if (message.contains("连接") || message.contains("录音") || message.contains("唤醒")) {
              // 系统消息：蓝色
              spannableMessage.setSpan(
                  ForegroundColorSpan(Color.parseColor("#64B5F6")),
                  0,
                  message.length,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
              )
          } else if (message.contains("自动滚动")) {
              // 提示消息：浅灰色
              spannableMessage.setSpan(
                  ForegroundColorSpan(Color.parseColor("#B0BEC5")),
                  0,
                  message.length,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
              )
          } else {
              // 其他消息：浅灰色
              spannableMessage.setSpan(
                  ForegroundColorSpan(Color.parseColor("#B0BEC5")),
                  0,
                  message.length,
                  Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
              )
          }

          spannableBuilder.append(spannableMessage)
      }

      speechTextView.text = spannableBuilder

      // 滚动到底部，确保最新消息可见
      if (isAutoScrolling) {
          speechTextView.post {
              // 等待布局完成后再滚动
              speechTextView.post {
                  try {
                      // 方法1：使用scrollTo
                      val lastLine = speechTextView.lineCount - 1
                      if (lastLine >= 0 && speechTextView.layout != null) {
                          val lineBottom = speechTextView.layout.getLineBottom(lastLine)
                          val scrollY = lineBottom - speechTextView.height
                          if (scrollY > 0) {
                              speechTextView.scrollTo(0, scrollY)
                          }
                      }
                      
                      // 方法2：使用requestLayout强制刷新
                      speechTextView.requestLayout()
                      
                      // 方法3：延迟再次滚动，确保布局完全更新
                      speechTextView.postDelayed({
                          val lastLineRetry = speechTextView.lineCount - 1
                          if (lastLineRetry >= 0 && speechTextView.layout != null) {
                              val lineBottomRetry = speechTextView.layout.getLineBottom(lastLineRetry)
                              val scrollYRetry = lineBottomRetry - speechTextView.height
                              if (scrollYRetry > 0) {
                                  speechTextView.scrollTo(0, scrollYRetry)
                              } else {
                                  // 如果内容小于视图高度，滚动到顶部
                                  speechTextView.scrollTo(0, 0)
                              }
                          }
                      }, 50)
                  } catch (e: Exception) {
                      Log.e("FloatingWindowService", "滚动到底部失败", e)
                  }
              }
          }
      }
  }

  private fun setupTextScrolling() {
      // 自动滚动到最新消息
      isAutoScrolling = true

      // 用户可以触摸文字区域暂停/恢复自动滚动
      speechTextView.setOnClickListener {
          isAutoScrolling = !isAutoScrolling
          if (isAutoScrolling) {
              updateSpeechDisplay()
              addSpeechMessage("自动滚动已启用")
          } else {
              addSpeechMessage("自动滚动已暂停")
          }
      }
  }

  private inner class FloatingOnTouchListener : View.OnTouchListener {
      private var initialX = 0
      private var initialY = 0
      private var initialTouchX = 0f
      private var initialTouchY = 0f

      override fun onTouch(v: View, event: MotionEvent): Boolean {
          when (event.action) {
              MotionEvent.ACTION_DOWN -> {
                  // 记录初始位置
                  initialX = params.x
                  initialY = params.y
                  initialTouchX = event.rawX
                  initialTouchY = event.rawY
                  return true
              }
              MotionEvent.ACTION_MOVE -> {
                  // 计算移动距离
                  val dx = (initialTouchX-event.rawX ).toInt()
                  val dy = (event.rawY - initialTouchY).toInt()

                  // 更新悬浮窗位置
                  params.x = initialX + dx
                  params.y = initialY + dy

                  // 刷新悬浮窗
                  windowManager.updateViewLayout(floatingView, params)
                  return true
              }
              MotionEvent.ACTION_UP -> {
                  // 可以在这里添加释放时的逻辑
                  return true
              }
          }
          return false
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
      return START_STICKY
  }

  override fun onDestroy() {
      super.onDestroy()
      instance = null

      // 注销唤醒词检测广播接收器
      try {
          unregisterReceiver(wakeWordBroadcastReceiver)
          Log.d("FloatingWindowService", "唤醒词检测广播接收器已注销")
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "注销广播接收器异常: ${e.message}")
      }

      // 注销会议查询结果广播接收器
      try {
          unregisterReceiver(meetingResultBroadcastReceiver)
          Log.d("FloatingWindowService", "会议查询结果广播接收器已注销")
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "注销会议查询结果广播接收器异常: ${e.message}")
      }

      // 注销人脸识别控制广播接收器
      try {
          unregisterReceiver(faceRecognitionControlReceiver)
          Log.d("FloatingWindowService", "人脸识别控制广播接收器已注销")
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "注销人脸识别控制广播接收器异常: ${e.message}")
      }

      // 移除能量观察者
      energyObservation?.let { observer ->
          try {
              AudioEnergyHolder.currentEnergy.removeObserver(observer)
          } catch (e: Exception) {
              Log.e("FloatingWindowService", "移除能量观察者异常: ${e.message}")
          }
          energyObservation = null
      }

      energyStateObservation?.let { observer ->
          try {
              AudioEnergyHolder.energyState.removeObserver(observer)
          } catch (e: Exception) {
              Log.e("FloatingWindowService", "移除能量状态观察者异常: ${e.message}")
          }
          energyStateObservation = null
      }

      // 停止天气自动刷新
      weatherManager.stopAutoRefresh()

      // 停止日期更新定时器
      updateDateRunnable?.let { mainHandler.removeCallbacks(it) }
      updateDateRunnable = null
      Log.d("FloatingWindowService", "日期更新定时器已停止")

      // 停止IP更新定时器
      updateIpRunnable?.let { mainHandler.removeCallbacks(it) }
      updateIpRunnable = null
      Log.d("FloatingWindowService", "IP更新定时器已停止")

      // 停止会议状态检查定时器
      meetingStatusCheckRunnable?.let { mainHandler.removeCallbacks(it) }
      meetingStatusCheckRunnable = null
      Log.d("FloatingWindowService", "会议状态检查定时器已停止")

      // 停止自动监听检查定时器
      autoListenCheckRunnable?.let { mainHandler.removeCallbacks(it) }
      autoListenCheckRunnable = null
      Log.d("FloatingWindowService", "自动监听检查定时器已停止")

      // 停止人脸识别
      stopFaceRecognition()

      // 释放声纹识别资源
      try {
          speakerIdentificationManager.release()
          Log.d("FloatingWindowService", "声纹识别资源已释放")
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "释放声纹识别资源异常: ${e.message}")
      }

      // 释放MediaPlayer资源
      listenStartPlayer?.release()
      listenStartPlayer = null
      listenStopPlayer?.release()
      listenStopPlayer = null
      Log.d("FloatingWindowService", "MediaPlayer资源已释放")

      // 不重置能量数据，由 WakeWordService 负责

      // 停止波浪动画
      if (::voiceWaveView.isInitialized) {
          voiceWaveView.stopWaveAnimation()
      }

      // 停止前台服务
      stopForeground(true)

      // 移除悬浮窗
      if (::floatingView.isInitialized && ::windowManager.isInitialized) {
          try {
              windowManager.removeView(floatingView)
          } catch (e: Exception) {
              Log.e("FloatingWindowService", "移除悬浮窗异常: ${e.message}")
          }
      }

      Log.d("FloatingWindowService", "服务销毁，已清理波浪动画资源")
  }

  /**
   * 初始化提示音
   */
  private fun initSoundPool() {
      listenStartSoundId = R.raw.listen_start
      listenStopSoundId = R.raw.listen_stop
      Log.d("FloatingWindowService", "提示音资源ID已设置: start=$listenStartSoundId, stop=$listenStopSoundId")
  }

  /**
   * 播放监听开始提示音
   */
  private fun playListenStartSound() {
      try {
          audioManager?.requestAudioFocus(
              null,
              AudioManager.STREAM_NOTIFICATION,
              AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
          )
          
          val player = MediaPlayer.create(applicationContext, listenStartSoundId)
          player?.setOnCompletionListener {
              audioManager?.abandonAudioFocus(null)
              player.release()
          }
          player?.setOnErrorListener { _, what, extra ->
              Log.e("FloatingWindowService", "listen_start播放错误: what=$what, extra=$extra")
              audioManager?.abandonAudioFocus(null)
              player.release()
              true
          }
          player?.start()
          Log.d("FloatingWindowService", "播放监听开始提示音成功")
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "播放监听开始提示音异常: ${e.message}")
      }
  }

  /**
   * 播放监听停止提示音
   */
  private fun playListenStopSound() {
      try {
          audioManager?.requestAudioFocus(
              null,
              AudioManager.STREAM_NOTIFICATION,
              AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
          )
          
          val player = MediaPlayer.create(applicationContext, listenStopSoundId)
          player?.setOnCompletionListener {
              audioManager?.abandonAudioFocus(null)
              player.release()
          }
          player?.setOnErrorListener { _, what, extra ->
              Log.e("FloatingWindowService", "listen_stop播放错误: what=$what, extra=$extra")
              audioManager?.abandonAudioFocus(null)
              player.release()
              true
          }
          player?.start()
          Log.d("FloatingWindowService", "播放监听停止提示音成功")
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "播放监听停止提示音异常: ${e.message}")
      }
  }

  /**
   * 初始化人脸识别
   */
  private fun initFaceRecognition() {
      Log.d("FloatingWindowService", "初始化人脸识别")

      faceRecognitionManager.setListener(object : FaceRecognitionListener {
          override fun onFaceDetected(result: RecognitionResult, isFacing: Boolean, lipMovementDetected: Boolean, faceRect: Rect?, faceAngle: Float?) {
             // if((System.currentTimeMillis()/100)%50L==0L)
            //  Log.i("FloatingWindowService", "检测到人脸: ${result.name}, 相似度: ${result.similarity}, 正对镜头: $isFacing, 唇动: $lipMovementDetected, 人脸角度: ${faceAngle?.let { String.format("%.2f", it) + "°" } ?: "未知"}")
              
              val currentTime = System.currentTimeMillis()
              
              // 更新WebSocketHandler中的人脸检测时间
              wsHandler.updateFaceDetectedTime()
              
              // 更新自动监听相关时间戳
              lastRegisteredFaceTime = currentTime
              if (isFacing) {
                  lastFacingCameraTime = currentTime
              }
              if (lipMovementDetected) {
                  lastLipMovementTime = currentTime
              }
              
              // 检查是否应该启动自动监听
              checkAutoListenConditions()
              
              if (!isFaceDetected) {
                  isFaceDetected = true
                  
                  // 检查是否是master权限用户
                  val role = faceRecognitionManager.getUserRole(result.name)
                  val isMaster = role == 0
                  
                  if (isMaster) {
                      Log.i("FloatingWindowService", "识别到master权限用户: ${result.name}")
                  } else {
                      Log.i("FloatingWindowService","检测到普通用户人脸: ${result.name}")
                  }
                  
                  // 自动连接服务器
                  if (!isConnected) {
                      addSpeechMessage("正在自动连接服务器...")
                      Log.i("FloatingWindowService", "检测到人脸，正在自动连接服务器...")
                      // 重置重连计数，允许重新连接
                      wsHandler.resetReconnectAttempts()
                      kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                          val allConfigs = configManager.loadAllConfigs()
                          val wsUrl = otaConnector.getWebSocketUrl(allConfigs.deviceConfig.otaUrl, allConfigs.deviceConfig.deviceId, allConfigs.deviceConfig.clientId)
                          if (wsUrl != null) {
                              configManager.updateServerUrl(wsUrl)
                              val updatedConfigs = configManager.loadAllConfigs()
                              Log.i("FloatingWindowService", "配置已保存: ${updatedConfigs.deviceConfig}")
                              val wsDeviceConfig = WebSocketHandler.DeviceConfig(
                                  deviceId = updatedConfigs.deviceConfig.deviceId,
                                  deviceName = updatedConfigs.deviceConfig.deviceName,
                                  token = updatedConfigs.deviceConfig.token,
                                  otaUrl = updatedConfigs.deviceConfig.otaUrl,
                                  serverUrl = updatedConfigs.deviceConfig.serverUrl
                              )
                              wsHandler.setDeviceConfig(wsDeviceConfig)
                              Log.i("FloatingWindowService", "已设置deviceConfig: $wsDeviceConfig")
                              wsHandler.connect(wsUrl)
                              addSpeechMessage("WebSocket连接已建立")
                              addSpeechMessage("你好，${result.name}！欢迎回来")
                              Log.i("FloatingWindowService", "WebSocket连接已建立")

                              // 停止唤醒词服务
                              stopWakeWordService("检测到人脸，停止唤醒词服务")
                          } else {
                              addSpeechMessage("获取WebSocket地址失败")
                              Log.e("FloatingWindowService", "获取WebSocket地址失败")
                          }
                      }
                  } else {
                      //addSpeechMessage("WebSocket已连接")
                      Log.i("FloatingWindowService", "WebSocket已连接")
                  }
              }
          }

          override fun onFaceNotDetected() {
              isFaceDetected = false
              // 人脸未检测到，检查是否应该停止监听
              checkAutoListenConditions()
          }

          override fun onActiveTrigger(name: String, count: Int) {
              //Log.i("FloatingWindowService", "主动触发: $name, 识别次数: $count")
              handleActiveTrigger(name)
          }
      })

      // 设置预览SurfaceView
      setupFacePreviewSurfaceView()
  }

  private var surfaceCreated = false

  private fun setupFacePreviewSurfaceView() {
      try {
          val surfaceView = facePreviewSurfaceView
          if (surfaceView == null) {
              Log.e("FloatingWindowService", "FacePreviewSurfaceView is null")
              return
          }

          surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
              override fun surfaceCreated(holder: SurfaceHolder) {
                  Log.d("FloatingWindowService", "FacePreviewSurface created, size: ${surfaceView.width}x${surfaceView.height}")
                  faceRecognitionManager.setPreviewSurface(holder.surface)
                  faceRecognitionManager.setTestFGIviewEnabled(true)
                  surfaceCreated = true
                  
                  // Surface创建完成后，启动人脸识别
                  startFaceRecognition()
                  
                  Log.d("FloatingWindowService", "FacePreviewSurface set to FaceRecognitionManager, testFGIviewEnabled=true")
              }

              override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                  Log.d("FloatingWindowService", "FacePreviewSurface changed: ${width}x${height}")
              }

              override fun surfaceDestroyed(holder: SurfaceHolder) {
                  Log.d("FloatingWindowService", "FacePreviewSurface destroyed")
                  faceRecognitionManager.setPreviewSurface(null)
                  surfaceCreated = false
              }
          })
          
          Log.d("FloatingWindowService", "FacePreviewSurfaceView callback registered")
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "Failed to setup face preview surface view", e)
      }
  }

  /**
   * 启动人脸识别
   */
  fun startFaceRecognition() {
      if (isFaceRecognitionRunning) {
          Log.w("FloatingWindowService", "人脸识别已在运行")
          return
      }

      try {
          faceRecognitionManager.startRecognition()
          isFaceRecognitionRunning = true
          Log.d("FloatingWindowService", "人脸识别已启动")
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "启动人脸识别失败", e)
      }
  }

  /**
   * 停止人脸识别
   */
  fun stopFaceRecognition() {
      if (!isFaceRecognitionRunning) {
          return
      }

      try {
          faceRecognitionManager.stopRecognition()
          val released = faceRecognitionManager.waitForCameraRelease(3000)
          if (released) {
              Log.d("FloatingWindowService", "相机资源已完全释放")
          } else {
              Log.w("FloatingWindowService", "相机资源释放超时")
          }
          isFaceRecognitionRunning = false
          Log.d("FloatingWindowService", "人脸识别已停止")
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "停止人脸识别失败", e)
      }
  }

  private fun handleActiveTrigger(name: String) {
      val chatTopic = activeChatManager.handleActiveTrigger(name)
      
      if (chatTopic != null) {
          Log.i("FloatingWindowService", "主动触发聊天: $chatTopic")
      }
  }

  /**
   * 检查自动监听条件并执行相应操作
   */
  private fun checkAutoListenConditions() {
      val currentTime = System.currentTimeMillis()
      
      // 检查是否应该启动监听
      val shouldStart = shouldStartListening(currentTime)
      if (shouldStart && !isAutoListening && isConnected) {
          Log.i("FloatingWindowService", "满足启动监听条件，发送listen start")
          wsHandler.sendListenStartMessage()
          isAutoListening = true
          lastListenStateChangeTime = currentTime
          addSpeechMessage("自动开始监听...")
         // playListenStartSound()
      }
      
      // 检查是否应该停止监听（需要冷却时间，防止频繁切换）
      val shouldStop = shouldStopListening(currentTime)
      val isCooldownOver = (currentTime - lastListenStateChangeTime) > AUTO_LISTEN_COOLDOWN
      if (shouldStop && isAutoListening && isCooldownOver) {
          Log.i("FloatingWindowService", "满足停止监听条件，发送listen stop")
          wsHandler.sendListenStopMessage()
          isAutoListening = false
          lastListenStateChangeTime = currentTime
          addSpeechMessage("自动停止监听...")
         // playListenStopSound()
      }
  }

  /**
   * 判断是否应该启动监听
   * 条件：最近3s内有识别到注册过的人脸 + 正对镜头 + 唇动识别为true + 声纹识别为true（如果已启用）
   */
  private fun shouldStartListening(currentTime: Long): Boolean {
      val hasRegisteredFace = (currentTime - lastRegisteredFaceTime) <= AUTO_LISTEN_TIME_WINDOW
      val isFacingCamera = (currentTime - lastFacingCameraTime) <= AUTO_LISTEN_TIME_WINDOW
      val hasLipMovement = (currentTime - lastLipMovementTime) <= AUTO_LISTEN_TIME_WINDOW
      val hasVoiceEnergy = (currentTime - lastVoiceEnergyTime) <= AUTO_LISTEN_TIME_WINDOW
      val hasIdentifiedSpeaker = (currentTime - lastIdentifiedSpeakerTime) <= SPEAKER_IDENTIFICATION_TIME_WINDOW
      
      var result = isFacingCamera && hasLipMovement && hasVoiceEnergy
      if(faceRecognitionManager.hasRegisteredFaces())
      {
          result  = result && hasRegisteredFace
      }

      // 如果启用了声纹识别且有注册的说话人，则增加声纹识别判断
      if (speakerIdentificationEnabled && speakerIdentificationManager.hasRegisteredSpeakers()) {
          result = result && hasIdentifiedSpeaker
      }
     // Log.d("FloatingWindowService", "shouldStartListening:numSpeaker=${speakerIdentificationManager.hasRegisteredSpeakers()} registeredFace=$hasRegisteredFace, facing=$isFacingCamera, lipMovement=$hasLipMovement, voiceEnergy=$hasVoiceEnergy, identifiedSpeaker=$hasIdentifiedSpeaker")

      if (result) {
    //      Log.d("FloatingWindowService", "shouldStartListening: registeredFace=$hasRegisteredFace, facing=$isFacingCamera, lipMovement=$hasLipMovement, voiceEnergy=$hasVoiceEnergy, identifiedSpeaker=$hasIdentifiedSpeaker")
      }
      
      return result
  }

  /**
   * 判断是否应该停止监听
   * 条件：2s内没有检测到唇动 + 2s内语音能量为静音
   */
  private fun shouldStopListening(currentTime: Long): Boolean {
      val noLipMovement = (currentTime - lastLipMovementTime) > AUTO_LISTEN_STOP_TIME_WINDOW
      val noVoiceEnergy = (currentTime - lastVoiceEnergyTime) > AUTO_LISTEN_STOP_TIME_WINDOW

      val noRegisteredFace = (currentTime - lastRegisteredFaceTime) > AUTO_LISTEN_STOP_TIME_WINDOW
      val noFacingCamera = (currentTime - lastFacingCameraTime) > AUTO_LISTEN_STOP_TIME_WINDOW
      val hasIdentifiedSpeaker = (currentTime - lastIdentifiedSpeakerTime) > AUTO_LISTEN_STOP_TIME_WINDOW
      
      var result = noLipMovement || noVoiceEnergy ||noFacingCamera
      if(faceRecognitionManager.hasRegisteredFaces())
      {
          result  = result || noRegisteredFace
      }
      if (speakerIdentificationEnabled && speakerIdentificationManager.hasRegisteredSpeakers()) {
          result = result || hasIdentifiedSpeaker
      }
      if (result) {
          //Log.d("FloatingWindowService", "shouldStopListening: noLipMovement=$noLipMovement, noVoiceEnergy=$noVoiceEnergy,hasRegi,steredFace=$hasRegisteredFace,isFacingCamera=$isFacingCamera")
      }
      
      return result
  }

  private fun getLocalIpAddress(): String {
      try {
          val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
          
          for (netInterface in interfaces) {
              val interfaceName = netInterface.name.lowercase()
              
              if (interfaceName.startsWith("wlan") || interfaceName.startsWith("eth") || 
                  interfaceName.startsWith("wifi") || interfaceName.startsWith("en")) {
                  
                  Log.d("FloatingWindowService", "检查网络接口: $interfaceName, isUp=${netInterface.isUp}, isLoopback=${netInterface.isLoopback}")
                  
                  if (netInterface.isUp && !netInterface.isLoopback) {
                      val addresses = java.util.Collections.list(netInterface.inetAddresses)
                      for (address in addresses) {
                          if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                              val ipAddress = address.hostAddress
                              if (ipAddress != null && ipAddress.isNotEmpty()) {
                                  val parts = ipAddress.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                                  if (parts.size == 4) {
                                      Log.d("FloatingWindowService", "找到有效IP: $ipAddress, 接口: $interfaceName")
                                      return ipAddress
                                  }
                              }
                          }
                      }
                  }
              }
          }
          
          Log.w("FloatingWindowService", "未找到有效的网络接口IP，尝试获取任意非回环IP")
          
          for (netInterface in interfaces) {
              if (netInterface.isUp && !netInterface.isLoopback) {
                  val addresses = java.util.Collections.list(netInterface.inetAddresses)
                  for (address in addresses) {
                      if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                          val ipAddress = address.hostAddress
                          if (ipAddress != null && ipAddress.isNotEmpty()) {
                              val parts = ipAddress.split("\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                              if (parts.size == 4) {
                                  Log.d("FloatingWindowService", "找到备用IP: $ipAddress, 接口: ${netInterface.name}")
                                  return ipAddress
                              }
                          }
                      }
                  }
              }
          }
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "获取本地IP失败", e)
      }
      return "未知"
  }

  private fun updateIpDisplay() {
      try {
          val localIp = getLocalIpAddress()
          var serverIp = "未配置"
          
          try {
              val allConfigs = configManager.loadAllConfigs()
              val otaUrl = allConfigs.deviceConfig.otaUrl
              if (otaUrl.isNotEmpty()) {
                  try {
                      val uri = java.net.URI(otaUrl)
                      serverIp = uri.host ?: "未知"
                  } catch (e: Exception) {
                      Log.e("FloatingWindowService", "解析OTA URL失败: $otaUrl", e)
                      serverIp = "未知"
                  }
              }
          } catch (e: Exception) {
              Log.e("FloatingWindowService", "获取OTA配置失败", e)
          }

          mainHandler.post {
              localIpTextView.text = "本地IP: $localIp"
              serverIpTextView.text = "服务端IP: $serverIp"
              Log.d("FloatingWindowService", "更新IP显示 - 本地: $localIp, 服务端: $serverIp")
          }
      } catch (e: Exception) {
          Log.e("FloatingWindowService", "更新IP显示失败", e)
      }
  }
}