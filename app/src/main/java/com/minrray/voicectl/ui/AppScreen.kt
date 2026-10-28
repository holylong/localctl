package com.minrray.voicectl.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k2fsa.sherpa.onnx.*
import com.minrray.voicectl.core.audio.AudioRecorder
import com.minrray.voicectl.core.audio.OpusCodec
import com.minrray.voicectl.core.config.ConfigManager
import com.minrray.voicectl.core.network.OtaConnector
import com.minrray.voicectl.core.network.WebSocketHandler
import com.minrray.voicectl.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import java.util.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import android.content.Intent
import android.os.Build

// 消息数据类
data class Message(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// 获取设备唯一ID
fun getDeviceId(context: android.content.Context): String {
    return try {
        val androidId = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
        android.util.Log.d("AppScreen", "获取Android ID: $androidId")
        if (androidId != null && androidId.isNotEmpty() && androidId != "9774d56d682e549c") {
            androidId
        } else {
            val fallbackId = "unknown_device_${System.currentTimeMillis()}"
            android.util.Log.w("AppScreen", "Android ID无效，使用备用ID: $fallbackId")
            fallbackId
        }
    } catch (e: Exception) {
        val fallbackId = "unknown_device_${System.currentTimeMillis()}"
        android.util.Log.e("AppScreen", "获取Android ID失败: ${e.message}, 使用备用ID: $fallbackId")
        fallbackId
    }
}

// 获取设备名称
fun getDeviceName(): String {
    return try {
        android.os.Build.MODEL
    } catch (e: Exception) {
        "Android设备"
    }
}

@Composable
fun AppScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isConnected by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var sessionStatus by remember { mutableStateOf("离线中") }
    var sessionEmoji by remember { mutableStateOf("😶") }
    var messageText by remember { mutableStateOf("") }
    var activeTab by remember { mutableStateOf("voice") }
    var isWakeUpEnabled by remember { mutableStateOf(true) } // 是否启用唤醒功能
    var showSettings by remember { mutableStateOf(false) } // 设置页面显示状态

    // 对话消息列表
    val messages = remember { mutableStateListOf<Message>() }
    val logEntries = remember { mutableStateListOf<String>() }

    // 设备配置
    var deviceId by remember { mutableStateOf(getDeviceId(context)) }
    var deviceName by remember { mutableStateOf(getDeviceName()) }
    var clientId by remember { mutableStateOf("android_test_client") }
    var token by remember { mutableStateOf("your-token1") }
    var otaUrl by remember { mutableStateOf("http://10.1.70.248:8003/xiaozhi/ota/") }
    var serverUrl by remember { mutableStateOf("") }
    var otaConnected by remember { mutableStateOf(false) }
    var isEditingConfig by remember { mutableStateOf(false) }
    var isMcpToolsExpanded by remember { mutableStateOf(false) }
    var mcpToolsCount by remember { mutableStateOf(0) }
    var testResult by remember { mutableStateOf("未测试") }
    var isTesting by remember { mutableStateOf(false) }

    // 从 ConfigManager 加载配置
    LaunchedEffect(Unit) {
        val configManager = ConfigManager.getInstance(context)
        val allConfigs = configManager.loadAllConfigs()
        val savedConfig = allConfigs.deviceConfig
        deviceId = savedConfig.deviceId
        deviceName = savedConfig.deviceName
        clientId = savedConfig.clientId
        token = savedConfig.token
        otaUrl = savedConfig.otaUrl
        serverUrl = savedConfig.serverUrl
        Log.d("AppScreen", "已加载配置: $savedConfig")
    }

    // 权限状态
    var hasRecordAudioPermission by remember { mutableStateOf(false) }
    var hasReadContactsPermission by remember { mutableStateOf(false) }
    var hasCallPhonePermission by remember { mutableStateOf(false) }

    // 唤醒词检测相关
    var isWakeWordDetected by remember { mutableStateOf(false) } // 唤醒词检测状态
    var isWakeWordServiceRunning by remember { mutableStateOf(false) } // 唤醒词服务运行状态

    // 创建协程作用域
    val coroutineScope = rememberCoroutineScope()
    // 获取网络连接器实例
    val otaConnector = OtaConnector.getInstance()
    val wsHandler = WebSocketHandler.getInstance()
    val audioRecorder = AudioRecorder.getInstance()

    // 权限请求启动器
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        isGranted ->
        hasRecordAudioPermission = isGranted
        if (isGranted) {
                    logEntries.add("录音权限已获得")
                    Log.i("VoiceCTL", "录音权限已获得")
                    // 权限获得后自动开始录音（启用Opus编码）
                    isRecording = true
                    audioRecorder.start(enableOpusEncoding = true)
                } else {
                    logEntries.add("录音权限被拒绝")
                    Log.i("VoiceCTL", "录音权限被拒绝")
                }
    }
    
    // 读取联系人权限请求启动器
    val readContactsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        isGranted ->
        hasReadContactsPermission = isGranted
        if (isGranted) {
            logEntries.add("读取联系人权限已获得")
            Log.i("VoiceCTL", "读取联系人权限已获得")
        } else {
            logEntries.add("读取联系人权限被拒绝")
            Log.i("VoiceCTL", "读取联系人权限被拒绝")
        }
    }
    
    // 拨打电话权限请求启动器
    val callPhonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        isGranted ->
        hasCallPhonePermission = isGranted
        if (isGranted) {
            logEntries.add("拨打电话权限已获得")
            Log.i("VoiceCTL", "拨打电话权限已获得")
        } else {
            logEntries.add("拨打电话权限被拒绝")
            Log.i("VoiceCTL", "拨打电话权限被拒绝")
        }
    }

    // 初始化WebSocket回调和权限检查
    LaunchedEffect(Unit) {
        // 检查并请求录音权限
        val recordPermission = android.Manifest.permission.RECORD_AUDIO
        hasRecordAudioPermission = android.content.pm.PackageManager.PERMISSION_GRANTED == 
            context.checkSelfPermission(recordPermission)
        if (!hasRecordAudioPermission) {
            // 主动请求录音权限
            recordAudioPermissionLauncher.launch(recordPermission)
        }
        
        // 检查并请求读取联系人权限
        val readContactsPermission = android.Manifest.permission.READ_CONTACTS
        hasReadContactsPermission = android.content.pm.PackageManager.PERMISSION_GRANTED == 
            context.checkSelfPermission(readContactsPermission)
        if (!hasReadContactsPermission) {
            // 主动请求读取联系人权限
            readContactsPermissionLauncher.launch(readContactsPermission)
        }
        
        // 检查并请求拨打电话权限
        val callPhonePermission = android.Manifest.permission.CALL_PHONE
        hasCallPhonePermission = android.content.pm.PackageManager.PERMISSION_GRANTED == 
            context.checkSelfPermission(callPhonePermission)
        if (!hasCallPhonePermission) {
            // 主动请求拨打电话权限
            callPhonePermissionLauncher.launch(callPhonePermission)
        }
        
        // 设置设备配置到WebSocketHandler
        val deviceConfig = WebSocketHandler.DeviceConfig(
            deviceId = deviceId,
            deviceName = deviceName,
            token = token,
            otaUrl = otaUrl,
            serverUrl = serverUrl
        )
        wsHandler.setDeviceConfig(deviceConfig)
        
        // 连接状态变化回调
        wsHandler.onConnectionStateChange = {
            connected ->
            isConnected = connected
            if (connected) {
                sessionStatus = "聆听中"

            } else {
                sessionStatus = "离线中"
                // 断开连接时，重置录音状态
                if (isRecording) {
                    isRecording = false
                    // 停止录音
                    audioRecorder.stop()
                    logEntries.add("连接断开，自动停止录音")
                    Log.i("VoiceCTL", "连接断开，自动停止录音")
                }
                // 重新启动唤醒词服务
                val hasPermission = android.content.pm.PackageManager.PERMISSION_GRANTED == 
                    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                
                if (hasPermission && !isWakeWordServiceRunning) {
                    val startIntent = Intent(context, com.minrray.voicectl.core.service.WakeWordService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(startIntent)
                    } else {
                        context.startService(startIntent)
                    }
                    isWakeWordServiceRunning = true
                    logEntries.add("断开连接，重新启动唤醒词服务")
                    Log.i("VoiceCTL", "断开连接，重新启动唤醒词服务")
                }
            }
            logEntries.add("${if (connected) "连接成功" else "连接断开"}")
            Log.i("VoiceCTL", "${if (connected) "连接成功" else "连接断开"}")
        }

        // 录音按钮状态变化回调
        wsHandler.onRecordButtonStateChange = {
            recording ->
            isRecording = recording
            if (recording) {
                // 开始录音时，停止唤醒词服务
                if (isWakeWordServiceRunning) {
                    logEntries.add("开始录音，停止唤醒词服务")
                    Log.i("VoiceCTL", "开始录音，停止唤醒词服务")
                    val stopIntent = Intent(context, com.minrray.voicectl.core.service.WakeWordService::class.java)
                    context.stopService(stopIntent)
                    isWakeWordServiceRunning = false
                }
            } else {
                // 当录音停止时，重置唤醒词检测状态
                isWakeWordDetected = false
                
                // 如果没有连接服务器，重新启动唤醒词服务
                if (!isConnected) {
                    logEntries.add("录音停止，断开连接，重新启动唤醒词服务")
                    Log.i("VoiceCTL", "录音停止，断开连接，重新启动唤醒词服务")
                    val hasPermission = android.content.pm.PackageManager.PERMISSION_GRANTED == 
                        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    
                    if (hasPermission && !isWakeWordServiceRunning) {
                        val startIntent = Intent(context, com.minrray.voicectl.core.service.WakeWordService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(startIntent)
                        } else {
                            context.startService(startIntent)
                        }
                        isWakeWordServiceRunning = true
                        logEntries.add("唤醒词服务已重新启动")
                        Log.i("VoiceCTL", "唤醒词服务已重新启动")
                    }
                }
            }
        }

        // 会话状态变化回调
        wsHandler.onSessionStateChange = {
            isSpeaking ->
            sessionStatus = if (isSpeaking == true) "说话中" else "聆听中"
        }

        // 会话表情变化回调
        wsHandler.onSessionEmotionChange = {
            emoji ->
            sessionEmoji = emoji
        }
        
        // 所有消息接收回调（用于调试）
        wsHandler.onMessageReceived = {
            message ->
            logEntries.add("收到完整消息: $message")
            Log.i("VoiceCTL", "收到完整消息: $message")
        }
        
        // 文本消息回调
/*        wsHandler.onTextMessage = {
            text, isUser ->
            messages.add(Message(text, isUser))
            logEntries.add("${if (isUser) "用户" else "服务器"}: $text")
            Log.i("VoiceCTL", "${if (isUser) "用户" else "服务器"}: $text")
        }
        */
        // 日志初始化
        logEntries.add("准备就绪，请连接服务器开始测试...")
        Log.i("VoiceCTL", "准备就绪，请连接服务器开始测试...")
        
        // 初始化唤醒词检测服务
        if (isWakeUpEnabled && !isWakeWordServiceRunning) {
            try {
                // 检查录音权限
                val hasPermission = android.content.pm.PackageManager.PERMISSION_GRANTED == 
                    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                
                if (hasPermission) {
                    // 启动唤醒词后台服务
                    val intent = Intent(context, com.minrray.voicectl.core.service.WakeWordService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    isWakeWordServiceRunning = true
                    logEntries.add("唤醒词服务已启动")
                    Log.i("VoiceCTL", "唤醒词服务已启动")
                } else {
                    logEntries.add("缺少录音权限，无法启动唤醒词服务")
                    Log.i("VoiceCTL", "缺少录音权限，无法启动唤醒词服务")
                }
            } catch (e: Exception) {
                logEntries.add("启动唤醒词服务失败: ${e.message}")
                Log.e("VoiceCTL", "启动唤醒词服务失败: ${e.message}")
            }
        }
    }
    
    // 设备配置变化时更新WebSocket配置
    LaunchedEffect(deviceId, deviceName, token, otaUrl, serverUrl) {
        val deviceConfig = WebSocketHandler.DeviceConfig(
            deviceId = deviceId,
            deviceName = deviceName,
            token = token,
            otaUrl = otaUrl,
            serverUrl = serverUrl
        )
        wsHandler.setDeviceConfig(deviceConfig)
        Log.d("AppScreen", "WebSocket配置已更新: $deviceConfig")
    }
    
    // 广播接收器，用于接收唤醒词检测事件
    val wakeWordBroadcastReceiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.minrray.voicectl.WAKE_WORD_DETECTED") {
                    Log.i("VoiceCTL", "===>> Get Detect Event")
                    val keyword = intent.getStringExtra("keyword")
                    if (keyword != null) {
                        logEntries.add("检测到唤醒词: $keyword")
                        Log.i("VoiceCTL", "==> 检测到唤醒词: $keyword")
                        isWakeWordDetected = true
                        
                        // 停止唤醒词服务，避免重复检测
                        if (isWakeWordServiceRunning) {
                            logEntries.add("停止唤醒词服务")
                            Log.i("VoiceCTL", "停止唤醒词服务")
                            val stopIntent = Intent(context, com.minrray.voicectl.core.service.WakeWordService::class.java)
                            context?.stopService(stopIntent)
                            isWakeWordServiceRunning = false
                        }
                        
                        // 自动连接服务器
                        if (!isConnected) {
                            logEntries.add("正在自动连接服务器...")
                            Log.i("VoiceCTL", "正在自动连接服务器...")
                            
                            // 确保deviceConfig已设置
                            val deviceConfig = WebSocketHandler.DeviceConfig(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                token = token,
                                otaUrl = otaUrl,
                                serverUrl = serverUrl
                            )
                            wsHandler.setDeviceConfig(deviceConfig)
                            Log.i("VoiceCTL", "已设置deviceConfig: $deviceConfig")
                            
                            // 在协程中调用挂起函数
                            coroutineScope.launch(Dispatchers.IO) {
                                val wsUrl = otaConnector.getWebSocketUrl(otaUrl, deviceId, clientId)
                                if (wsUrl != null) {
                                    otaConnected = true
                                    serverUrl = wsUrl
                                    wsHandler.connect(wsUrl)
                                    logEntries.add("WebSocket连接已建立")
                                    Log.i("VoiceCTL", "WebSocket连接中392")
                                } else {
                                    logEntries.add("获取WebSocket地址失败")
                                    Log.e("VoiceCTL", "获取WebSocket地址失败")
                                    // 如果连接失败，重新启动唤醒词服务
                                    if (!isRecording && !isWakeWordServiceRunning) {
                                        logEntries.add("连接服务器失败，重新启动唤醒词服务")
                                        Log.i("VoiceCTL", "连接服务器失败，重新启动唤醒词服务")
                                        val startIntent = Intent(context, com.minrray.voicectl.core.service.WakeWordService::class.java)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context?.startForegroundService(startIntent)
                                        } else {
                                            context?.startService(startIntent)
                                        }
                                        isWakeWordServiceRunning = true
                                    }
                                }
                            }
                        } else {
                            logEntries.add("WebSocket已连接")
                            Log.i("VoiceCTL", "WebSocket已连接")
                        }
                        
                        // 开始监听用户说话，相当于按了按住说话按钮
                        if (!isRecording && hasRecordAudioPermission) {
                            logEntries.add("开始监听用户说话...")
                            Log.i("VoiceCTL", "开始监听用户说话...")
                            isRecording = true
                            audioRecorder.start(enableOpusEncoding = true)
                            logEntries.add("AudioRecorder已启动，开始编码发送数据")
                            Log.i("VoiceCTL", "AudioRecorder已启动，开始编码发送数据")
                        }
                    }
                }else{
                    logEntries.add("未知广播事件")
                    Log.i("VoiceCTL", "未知广播事件")
                }
            }
        }
    }

    // 广播接收器，用于接收会议提醒事件
    val meetingReminderBroadcastReceiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == "com.minrray.voicectl.MEETING_REMINDER") {
                    Log.i("VoiceCTL", "===>> 收到会议提醒事件")
                    val meetingId = intent.getStringExtra("meeting_id") ?: return
                    val meetingType = intent.getStringExtra("meeting_type") ?: ""
                    val meetingNumber = intent.getStringExtra("meeting_number") ?: ""
                    val meetingTime = intent.getLongExtra("meeting_time", 0)
                    val meetingName = intent.getStringExtra("meeting_name")
                    val reminderText = intent.getStringExtra("reminder_text") ?: return

                    logEntries.add("收到会议提醒: $meetingNumber")
                    Log.i("VoiceCTL", "==> 收到会议提醒: $meetingNumber")

                    // 停止唤醒词服务，避免重复检测
                    if (isWakeWordServiceRunning) {
                        logEntries.add("停止唤醒词服务")
                        Log.i("VoiceCTL", "停止唤醒词服务")
                        val stopIntent = Intent(context, com.minrray.voicectl.core.service.WakeWordService::class.java)
                        context?.stopService(stopIntent)
                        isWakeWordServiceRunning = false
                    }

                    // 开始监听用户说话，相当于按了按住说话按钮
                    if (!isRecording && hasRecordAudioPermission) {
                        logEntries.add("开始监听用户说话...")
                        Log.i("VoiceCTL", "开始监听用户说话...")
                        isRecording = true
                        audioRecorder.start(enableOpusEncoding = true)
                        logEntries.add("AudioRecorder已启动，开始编码发送数据")
                        Log.i("VoiceCTL", "AudioRecorder已启动，开始编码发送数据")

                        // 自动连接服务器并发送消息
                        if (!isConnected) {
                            logEntries.add("正在自动连接服务器...")
                            Log.i("VoiceCTL", "正在自动连接服务器...")
                            
                            // 确保deviceConfig已设置
                            val deviceConfig = WebSocketHandler.DeviceConfig(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                token = token,
                                otaUrl = otaUrl,
                                serverUrl = serverUrl
                            )
                            wsHandler.setDeviceConfig(deviceConfig)
                            Log.i("VoiceCTL", "已设置deviceConfig: $deviceConfig")
                            
                            // 在协程中调用挂起函数
                            coroutineScope.launch(Dispatchers.IO) {
                                val wsUrl = otaConnector.getWebSocketUrl(otaUrl, deviceId, clientId)
                                if (wsUrl != null) {
                                    otaConnected = true
                                    serverUrl = wsUrl
                                    wsHandler.connect(wsUrl)
                                    logEntries.add("WebSocket连接已建立")
                                    Log.i("VoiceCTL", "WebSocket连接。。。 480")

                                    // 等待连接建立
                                    delay(1000)

                                    // 发送提醒文本消息
                                    val success = wsHandler.sendTextMessage(reminderText)
                                    if (success) {
                                        logEntries.add("已发送会议提醒消息到服务器")
                                        Log.i("VoiceCTL", "已发送会议提醒消息到服务器")
                                    } else {
                                        logEntries.add("发送会议提醒消息失败")
                                        Log.e("VoiceCTL", "发送会议提醒消息失败")
                                        // 发送失败，显示回退通知
                                        showMeetingFallbackNotification(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)
                                    }
                                } else {
                                    logEntries.add("获取WebSocket地址失败")
                                    Log.e("VoiceCTL", "获取WebSocket地址失败")
                                    // 如果连接失败，显示回退通知
                                    showMeetingFallbackNotification(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)
                                    return@launch
                                }
                            }
                        } else {
                            logEntries.add("WebSocket已连接")
                            Log.i("VoiceCTL", "WebSocket已连接")

                            // 立即发送提醒文本消息
                            val success = wsHandler.sendTextMessage(reminderText)
                            if (success) {
                                logEntries.add("已发送会议提醒消息到服务器")
                                Log.i("VoiceCTL", "已发送会议提醒消息到服务器")
                            } else {
                                logEntries.add("发送会议提醒消息失败")
                                Log.e("VoiceCTL", "发送会议提醒消息失败")
                                // 发送失败，显示回退通知
                                showMeetingFallbackNotification(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)
                            }
                        }
                    } else {
                        logEntries.add("无法启动录音，权限不足或正在录音")
                        Log.e("VoiceCTL", "无法启动录音，权限不足或正在录音")
                        // 显示回退通知
                        showMeetingFallbackNotification(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)
                    }
                } else {
                    logEntries.add("未知广播事件")
                    Log.i("VoiceCTL", "未知广播事件")
                }
            }

            // 显示回退通知
            private fun showMeetingFallbackNotification(
                context: android.content.Context?,
                meetingId: String,
                meetingType: String,
                meetingNumber: String,
                meetingTime: Long,
                meetingName: String?
            ) {
                // 创建通知标题和内容
                val title = if (meetingName.isNullOrEmpty()) {
                    "会议提醒"
                } else {
                    "$meetingName 会议提醒"
                }

                val content = "您有一个${meetingType}会议 (${meetingNumber}) 即将开始，是否参加？"

                // 创建Intent
                val confirmIntent = android.app.PendingIntent.getBroadcast(
                    context,
                    meetingId.hashCode() and 0xffff,
                    Intent(context, com.minrray.voicectl.core.meeting.MeetingConfirmBroadcastReceiver::class.java).apply {
                        putExtra("meeting_id", meetingId)
                        putExtra("confirm", true)
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                val declineIntent = android.app.PendingIntent.getBroadcast(
                    context,
                    (meetingId.hashCode() + 1) and 0xffff,
                    Intent(context, com.minrray.voicectl.core.meeting.MeetingConfirmBroadcastReceiver::class.java).apply {
                        putExtra("meeting_id", meetingId)
                        putExtra("confirm", false)
                    },
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                // 构建通知
                val builder = androidx.core.app.NotificationCompat.Builder(context!!, "meeting_reminder_channel")
                    .setSmallIcon(com.minrray.voicectl.R.drawable.icon)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .addAction(com.minrray.voicectl.R.drawable.icon, "参加", confirmIntent)
                    .addAction(com.minrray.voicectl.R.drawable.icon, "不参加", declineIntent)

                // 显示通知
                val notificationManager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                val notificationId = (1000 + meetingId.hashCode()) and 0xffff
                notificationManager.notify(notificationId, builder.build())

                Log.i("VoiceCTL", "显示回退通知: $meetingId")
            }
        }
    }

    // 注册广播接收器
    DisposableEffect(Unit) {
        Log.i("VoiceCTL", "注册广播接收器")
        val wakeWordIntentFilter = android.content.IntentFilter("com.minrray.voicectl.WAKE_WORD_DETECTED")
        val meetingReminderIntentFilter = android.content.IntentFilter("com.minrray.voicectl.MEETING_REMINDER")

        // 在Android 14+中，注册广播接收器需要明确指定RECEIVER_EXPORTED或RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                wakeWordBroadcastReceiver,
                wakeWordIntentFilter,
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
            context.registerReceiver(
                meetingReminderBroadcastReceiver,
                meetingReminderIntentFilter,
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(wakeWordBroadcastReceiver, wakeWordIntentFilter)
            context.registerReceiver(meetingReminderBroadcastReceiver, meetingReminderIntentFilter)
        }

        onDispose {
            // 取消注册广播接收器
            context.unregisterReceiver(wakeWordBroadcastReceiver)
            context.unregisterReceiver(meetingReminderBroadcastReceiver)

            // 停止唤醒词服务
            if (isWakeWordServiceRunning) {
                val intent = Intent(context, com.minrray.voicectl.core.service.WakeWordService::class.java)
                context.stopService(intent)
                isWakeWordServiceRunning = false
                logEntries.add("唤醒词服务已停止")
                Log.i("VoiceCTL", "唤醒词服务已停止")
            }
        }
    }
    
    // 当唤醒词检测状态重置时，只重置状态标记
    LaunchedEffect(isWakeWordDetected) {
        if (isWakeWordDetected) {
            // 延迟一段时间后重置唤醒词检测状态，以便再次检测
            kotlinx.coroutines.delay(3000)
            isWakeWordDetected = false
            logEntries.add("唤醒词检测状态已重置，准备再次检测")
            Log.i("VoiceCTL", "唤醒词检测状态已重置，准备再次检测")
        }
    }

    // 设置页面内容
    if (showSettings) {
        SettingsScreen(
            deviceId = deviceId,
            deviceName = deviceName,
            clientId = clientId,
            token = token,
            otaUrl = otaUrl,
            serverUrl = serverUrl,
            isConnected = isConnected,
            otaConnected = otaConnected,
            isEditingConfig = isEditingConfig,
            isMcpToolsExpanded = isMcpToolsExpanded,
            mcpToolsCount = mcpToolsCount,
            testResult = testResult,
            isTesting = isTesting,
            logEntries = logEntries,
            // 权限状态
            hasRecordAudioPermission = hasRecordAudioPermission,
            hasReadContactsPermission = hasReadContactsPermission,
            hasCallPhonePermission = hasCallPhonePermission,
            // 权限请求回调
            onRequestRecordAudioPermission = { 
                recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO) 
            },
            onRequestReadContactsPermission = { 
                readContactsPermissionLauncher.launch(android.Manifest.permission.READ_CONTACTS) 
            },
            onRequestCallPhonePermission = { 
                callPhonePermissionLauncher.launch(android.Manifest.permission.CALL_PHONE) 
            },
            onBackClick = { showSettings = false },
            onToggleEdit = { isEditingConfig = !isEditingConfig },
            onTestCodec = {
                coroutineScope.launch {
                    isTesting = true
                    testResult = "测试中..."
                    logEntries.add("开始编解码器测试...")
                    Log.i("VoiceCTL", "开始编解码器测试...")

                    val opusCodec = OpusCodec.getInstance()
                    val success = opusCodec.testCodec()

                    testResult = if (success) "✅ 测试成功" else "❌ 测试失败"
                    logEntries.add("编解码器测试结果: ${if (success) "成功" else "失败"}")
                    Log.i("VoiceCTL", "编解码器测试结果: ${if (success) "成功" else "失败"}")
                    logEntries.add("编解码器统计: ${opusCodec.getStats()}")
                    Log.i("VoiceCTL", "编解码器统计: ${opusCodec.getStats()}")

                    isTesting = false
                }
            },
            onConnectClick = {
                coroutineScope.launch {
                    if (isConnected) {
                        // 断开连接
                        wsHandler.disconnect()
                        otaConnected = false
                    } else {
                        // 连接到OTA服务器获取WebSocket地址
                        val wsUrl = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            otaConnector.getWebSocketUrl(otaUrl, deviceId, clientId)
                        }
                        if (wsUrl != null) {
                            otaConnected = true
                            serverUrl = wsUrl
                            
                            // 确保deviceConfig已设置
                            val deviceConfig = WebSocketHandler.DeviceConfig(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                token = token,
                                otaUrl = otaUrl,
                                serverUrl = serverUrl
                            )
                            wsHandler.setDeviceConfig(deviceConfig)
                            Log.i("VoiceCTL", "已设置deviceConfig: $deviceConfig")
                            
                            // 连接WebSocket服务器
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                wsHandler.connect(wsUrl)
                            }
                        }
                    }
                }
            },
            onToggleMcpTools = { isMcpToolsExpanded = !isMcpToolsExpanded },
            onDeviceIdChange = { deviceId = it },
            onDeviceNameChange = { deviceName = it },
            onClientIdChange = { clientId = it },
            onTokenChange = { token = it },
            onOtaUrlChange = { otaUrl = it }
        )
    } else {
        // 主聊天界面，复刻ChatGPT移动App设计
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "会会助手",
                            style = TextStyle(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    },
                    actions = {
                        // 设置按钮
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                                contentDescription = "设置"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = GptBackground,
                        titleContentColor = GptTextPrimary,
                        actionIconContentColor = GptTextSecondary
                    ),
                    modifier = Modifier.background(GptBackground)
                )
            },
            containerColor = GptBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(it)
                    .padding(16.dp)
            ) {
                // 会话状态指示器
                SessionStatusIndicator(
                    sessionEmoji = sessionEmoji,
                    sessionStatus = sessionStatus
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 对话消息区域 - 复刻ChatGPT设计
                val listState = rememberLazyListState()
                LaunchedEffect(messages.size) {
                    if (messages.isNotEmpty()) {
                        listState.animateScrollToItem(messages.size - 1)
                    }
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(GptBackground)
                        .padding(16.dp)
                ) {
                    if (messages.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "欢迎使用会会智能助手！",
                                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                                color = GptTextPrimary
                            )
                            Text(
                                text = "您可以通过语音或文本与我交流",
                                style = TextStyle(fontSize = 16.sp),
                                color = GptTextSecondary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(messages.size) { index ->
                                val message = messages[index]
                                val horizontalArrangement = if (message.isUser) 
                                    Arrangement.End 
                                else 
                                    Arrangement.Start
                                val backgroundColor = if (message.isUser) 
                                    GptUserMessage 
                                else 
                                    GptAssistantMessage
                                val contentColor = GptTextPrimary
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = horizontalArrangement
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                color = backgroundColor,
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                                    18.dp,
                                                    18.dp,
                                                    4.dp,
                                                    18.dp
                                                )
                                            )
                                            .padding(16.dp)
                                    ) {
                                        Text(
                                            text = message.text,
                                            color = contentColor,
                                            fontSize = 16.sp,
                                            lineHeight = 15.sp,
                                            maxLines = Int.MAX_VALUE
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 聊天模式切换 - 采用ChatGPT风格的简洁设计
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 简化的切换按钮，类似ChatGPT的设计
                    Row(
                        modifier = Modifier
                            .background(GptSurface)
                            .border(
                                width = 1.dp,
                                color = GptBorder,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
                            )
                            .padding(4.dp)
                    ) {
                        // 语音聊天按钮
                        TextButton(
                            onClick = { activeTab = "voice" },
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (activeTab == "voice") GptButton else Color.Transparent,
                                contentColor = if (activeTab == "voice") GptTextPrimary else GptTextSecondary
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                        ) {
                            Text("语音")
                        }
                        
                        // 文本聊天按钮
                        TextButton(
                            onClick = { activeTab = "text" },
                            colors = ButtonDefaults.textButtonColors(
                                containerColor = if (activeTab == "text") GptButton else Color.Transparent,
                                contentColor = if (activeTab == "text") GptTextPrimary else GptTextSecondary
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                        ) {
                            Text("文本")
                        }
                    }
                    
                    // 唤醒开关移到设置页面
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // 聊天输入区域 - 采用ChatGPT悬浮式设计
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    when (activeTab) {
                        "text" -> {
                            // 文本聊天输入 - ChatGPT风格
                            Row(
                                modifier = Modifier
                                    .background(GptSurface)
                                    .border(
                                        width = 1.dp,
                                        color = GptBorder,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                            28.dp
                                        )
                                    )
                                    .padding(8.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = messageText,
                                    onValueChange = { messageText = it },
                                    placeholder = { Text("输入消息...", color = GptTextSecondary) },
                                    modifier = Modifier.weight(1f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent,
                                        disabledContainerColor = Color.Transparent,
                                        focusedIndicatorColor = Color.Transparent,
                                        unfocusedIndicatorColor = Color.Transparent,
                                        disabledIndicatorColor = Color.Transparent,
                                        cursorColor = GptButton,
                                        focusedTextColor = GptTextPrimary,
                                        unfocusedTextColor = GptTextPrimary
                                    )
                                )
                                
                                Button(
                                    onClick = {
                                        // 发送文本消息
                                        if (messageText.isNotEmpty()) {
                                            wsHandler.sendTextMessage(messageText)
                                            messageText = ""
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = GptButton,
                                        contentColor = GptTextPrimary
                                    ),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Text(">", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        "voice" -> {
                            // 语音聊天输入 - 圆形动画空间设计
                            Column(
                                modifier = Modifier
                                    .background(GptSurface)
                                    .border(
                                        width = 1.dp,
                                        color = GptBorder,
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                            28.dp
                                        )
                                    )
                                    .padding(24.dp)
                                    .fillMaxWidth()
                                    .heightIn(max = 250.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                // 圆形动画空间 - 结合声纹动画和录音按钮
                                Box(
                                    modifier = Modifier
                                        .size(180.dp)
                                        .background(
                                            color = if (isRecording) GptError else GptButton,
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        )
                                        .clickable {
                                            val permission =
                                                android.Manifest.permission.RECORD_AUDIO
                                            if (!hasRecordAudioPermission) {
                                                // 请求录音权限
                                                recordAudioPermissionLauncher.launch(permission)
                                            } else {
                                                if (isRecording) {
                                                    // 停止录音（原有逻辑不变）
                                                    isRecording = false
                                                    audioRecorder.stop()
                                                    logEntries.add("停止录音")
                                                    Log.i("VoiceCTL", "停止录音")
                                                    // 重置唤醒词检测状态
                                                    isWakeWordDetected = false

                                                    // 如果没有连接服务器，重新启动唤醒词服务
                                                    if (!isConnected) {
                                                        logEntries.add("录音停止，断开连接，重新启动唤醒词服务")
                                                        Log.i(
                                                            "VoiceCTL",
                                                            "录音停止，断开连接，重新启动唤醒词服务"
                                                        )
                                                        val hasPermission =
                                                            android.content.pm.PackageManager.PERMISSION_GRANTED ==
                                                                    context.checkSelfPermission(
                                                                        android.Manifest.permission.RECORD_AUDIO
                                                                    )

                                                        if (hasPermission && !isWakeWordServiceRunning) {
                                                            val startIntent = Intent(
                                                                context,
                                                                com.minrray.voicectl.core.service.WakeWordService::class.java
                                                            )
                                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                                context.startForegroundService(
                                                                    startIntent
                                                                )
                                                            } else {
                                                                context.startService(startIntent)
                                                            }
                                                            isWakeWordServiceRunning = true
                                                            logEntries.add("唤醒词服务已重新启动")
                                                            Log.i(
                                                                "VoiceCTL",
                                                                "唤醒词服务已重新启动"
                                                            )
                                                        }
                                                    }
                                                } else {
                                                    // 开始录音流程 - 放在协程中确保时序正确
                                                    coroutineScope.launch {
                                                        // 1. 停止唤醒词服务
                                                        if (isWakeWordServiceRunning) {
                                                            logEntries.add("开始录音，停止唤醒词服务")
                                                            Log.i(
                                                                "VoiceCTL",
                                                                "开始录音，停止唤醒词服务"
                                                            )
                                                            val stopIntent = Intent(
                                                                context,
                                                                com.minrray.voicectl.core.service.WakeWordService::class.java
                                                            )
                                                            context.stopService(stopIntent)
                                                            isWakeWordServiceRunning = false
                                                            // 给系统一点时间释放麦克风资源
                                                            kotlinx.coroutines.delay(100)
                                                        }

                                                        // 2. 连接WebSocket
                                                        if (!isConnected) {
                                                            logEntries.add("正在自动连接服务器...")
                                                            Log.i(
                                                                "VoiceCTL",
                                                                "正在自动连接服务器..."
                                                            )
                                                            
                                                            // 确保deviceConfig已设置
                                                            val deviceConfig = WebSocketHandler.DeviceConfig(
                                                                deviceId = deviceId,
                                                                deviceName = deviceName,
                                                                token = token,
                                                                otaUrl = otaUrl,
                                                                serverUrl = serverUrl
                                                            )
                                                            wsHandler.setDeviceConfig(deviceConfig)
                                                            Log.i("VoiceCTL", "已设置deviceConfig: $deviceConfig")
                                                            
                                                            val wsUrl =
                                                                otaConnector.getWebSocketUrl(
                                                                    otaUrl,
                                                                    deviceId,
                                                                    clientId
                                                                )
                                                            if (wsUrl != null) {
                                                                otaConnected = true
                                                                serverUrl = wsUrl
                                                                wsHandler.connect(wsUrl)
                                                                logEntries.add("WebSocket连接已建立")
                                                                Log.i(
                                                                    "VoiceCTL",
                                                                    "WebSocket连接。。。1027"
                                                                )
                                                            } else {
                                                                logEntries.add("获取WebSocket地址失败")
                                                                Log.e(
                                                                    "VoiceCTL",
                                                                    "获取WebSocket地址失败"
                                                                )
                                                                // 连接失败，重新启动唤醒词服务
                                                                if (!isWakeWordServiceRunning) {
                                                                    logEntries.add("连接服务器失败，重新启动唤醒词服务")
                                                                    Log.i(
                                                                        "VoiceCTL",
                                                                        "连接服务器失败，重新启动唤醒词服务"
                                                                    )
                                                                    val startIntent = Intent(
                                                                        context,
                                                                        com.minrray.voicectl.core.service.WakeWordService::class.java
                                                                    )
                                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                                        context.startForegroundService(
                                                                            startIntent
                                                                        )
                                                                    } else {
                                                                        context.startService(
                                                                            startIntent
                                                                        )
                                                                    }
                                                                    isWakeWordServiceRunning = true
                                                                }
                                                                return@launch
                                                            }
                                                        }

                                                        // 3. 开始录音
                                                        isRecording = true
                                                        audioRecorder.start(enableOpusEncoding = true)
                                                        logEntries.add("开始录音")
                                                        Log.i("VoiceCTL", "开始录音")
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    // 声纹动画 - 根据录音状态和唤醒词检测状态显示不同效果
                                    VoiceWaveAnimation(
                                        modifier = Modifier.align(Alignment.Center),
                                        isActive = isRecording || isWakeWordDetected
                                    )
                                    
                                    // 录音按钮文字
                                    Text(
                                        text = if (isRecording) "停止" else "按住说话",
                                        color = GptTextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 会话状态指示器
@Composable
fun SessionStatusIndicator(
    sessionEmoji: String,
    sessionStatus: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = sessionEmoji,
            style = TextStyle(fontSize = 24.sp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = sessionStatus)
    }
}

// 声纹动画组件
@Composable
fun VoiceWaveAnimation(
    modifier: Modifier = Modifier,
    isActive: Boolean = true
) {
    val barsCount = 12
    val barWidth = 4.dp
    val barSpacing = 6.dp
    val maxBarHeight = 80.dp
    val minBarHeight = 10.dp

    // 使用 rememberInfiniteTransition 来管理无限循环动画
    val infiniteTransition = rememberInfiniteTransition()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(barSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barsCount) {
            // 为每个条创建独立的无限循环动画值
            val animatedHeight by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1000
                        // 为每个条设置不同的相位，创建波浪效果
                        0f at 0 with LinearEasing
                        1f at (i * 50) % 1000 with LinearEasing
                        0f at 1000 with LinearEasing
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "animatedHeight"
            )

            // 根据动画进度计算当前条的高度
            val barHeight = if (isActive) {
                // 激活状态：使用动画高度
                minBarHeight + (maxBarHeight - minBarHeight) * animatedHeight
            } else {
                // 非激活状态：静态高度
                minBarHeight
            }

            Box(
                modifier = Modifier
                    .width(barWidth)
                    .height(barHeight)
                    .background(
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    )
            )
        }
    }
}

// 设备配置面板
@Composable
fun DeviceConfigPanel(
    modifier: Modifier,
    isEditing: Boolean,
    deviceId: String,
    deviceName: String,
    clientId: String,
    token: String,
    testResult: String,
    isTesting: Boolean,
    onToggleEdit: () -> Unit,
    onTestCodec: () -> Unit,
    onDeviceIdChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onClientIdChange: (String) -> Unit,
    onTokenChange: (String) -> Unit
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "设备配置",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onTestCodec,
                        enabled = !isTesting
                    ) {
                        Text(if (isTesting) "测试中..." else "测试编解码器")
                    }
                    Button(onClick = onToggleEdit) {
                        Text(if (isEditing) "确定" else "编辑")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ConfigItem(
                        modifier = Modifier.weight(1f),
                        label = "设备ID",
                        value = deviceId,
                        enabled = isEditing,
                        onValueChange = onDeviceIdChange
                    )
                    ConfigItem(
                        modifier = Modifier.weight(1f),
                        label = "设备名称",
                        value = deviceName,
                        enabled = isEditing,
                        onValueChange = onDeviceNameChange
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ConfigItem(
                        modifier = Modifier.weight(1f),
                        label = "客户端ID",
                        value = clientId,
                        enabled = isEditing,
                        onValueChange = onClientIdChange
                    )
                    ConfigItem(
                        modifier = Modifier.weight(1f),
                        label = "认证令牌",
                        value = token,
                        enabled = isEditing,
                        onValueChange = onTokenChange
                    )
                }
            }

            // 测试结果显示
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        testResult.contains("成功") -> androidx.compose.ui.graphics.Color.Green.copy(alpha = 0.1f)
                        testResult.contains("失败") -> androidx.compose.ui.graphics.Color.Red.copy(alpha = 0.1f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "编解码器测试结果",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = testResult,
                        style = TextStyle(fontSize = 12.sp),
                        color = when {
                            testResult.contains("成功") -> androidx.compose.ui.graphics.Color.Green
                            testResult.contains("失败") -> androidx.compose.ui.graphics.Color.Red
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

// 配置项组件
@Composable
fun ConfigItem(
    modifier: Modifier,
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        TextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            placeholder = { Text("请输入$label") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

// 会议预约管理面板
@Composable
fun MeetingSchedulePanel(modifier: Modifier) {
    val context = LocalContext.current
    val meetingScheduleManager = com.minrray.voicectl.core.meeting.MeetingScheduleManager.getInstance()
    
    // 状态管理
    var meetingSchedules by remember {
        mutableStateOf(meetingScheduleManager.getAllMeetingSchedules())
    }
    var isExpanded by remember { mutableStateOf(false) }
    var selectedSchedule by remember {
        mutableStateOf<com.minrray.voicectl.core.meeting.MeetingSchedule?>(null)
    }
    var isEditing by remember {
        mutableStateOf(false)
    }
    
    // 表单状态
    var meetingType by remember {
        mutableStateOf("")
    }
    var meetingNumber by remember {
        mutableStateOf("")
    }
    var meetingName by remember {
        mutableStateOf("")
    }
    var meetingTimeStr by remember {
        mutableStateOf("")
    }
    var attendeesInput by remember {
        mutableStateOf("")
    }
    
    // 刷新会议列表
    fun refreshMeetingList() {
        meetingSchedules = meetingScheduleManager.getAllMeetingSchedules()
    }
    
    // 重置表单
    fun resetForm() {
        meetingType = ""
        meetingNumber = ""
        meetingName = ""
        meetingTimeStr = ""
        attendeesInput = ""
        selectedSchedule = null
        isEditing = false
    }
    
    // 加载会议数据到表单
    fun loadScheduleToForm(schedule: com.minrray.voicectl.core.meeting.MeetingSchedule) {
        meetingType = schedule.meetingType
        meetingNumber = schedule.meetingNumber
        meetingName = schedule.meetingName ?: ""
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        meetingTimeStr = dateFormat.format(java.util.Date(schedule.meetingTime))
        // 加载预约人员信息，使用逗号分隔
        attendeesInput = schedule.attendees.joinToString(", ")
        selectedSchedule = schedule
    }
    
    // 保存会议预约
    fun saveMeetingSchedule() {
        try {
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            val meetingTime = dateFormat.parse(meetingTimeStr)?.time ?: return
            
            // 固定提醒时间为会议开始前5分钟
            val reminderTime = meetingTime - 5 * 60 * 1000L
            
            // 处理预约人员输入，分割成列表
            val attendeesList = attendeesInput
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            
            if (selectedSchedule == null) {
                // 创建新的会议预约
                val newSchedule = com.minrray.voicectl.core.meeting.MeetingSchedule(
                    id = java.util.UUID.randomUUID().toString(),
                    cloudConfId = "",
                    cloudConfNumber = "",
                    cloudConfHostUrl = "",
                    meetingType = meetingType,
                    meetingNumber = meetingNumber,
                    meetingTime = meetingTime,
                    meetingName = meetingName,
                    reminderTime = reminderTime,
                    isConfirmed = false,
                    attendees = attendeesList
                )
                meetingScheduleManager.addMeetingSchedule(newSchedule)
            } else {
                // 更新现有会议预约
                val updatedSchedule = selectedSchedule!!.copy(
                    meetingType = meetingType,
                    meetingNumber = meetingNumber,
                    meetingTime = meetingTime,
                    meetingName = meetingName,
                    reminderTime = reminderTime,
                    attendees = attendeesList
                )
                meetingScheduleManager.updateMeetingSchedule(updatedSchedule)
            }
            
            // 刷新列表并重置表单
            refreshMeetingList()
            resetForm()
        } catch (e: Exception) {
            Log.e("MeetingSchedulePanel", "保存会议预约失败: ${e.message}", e)
        }
    }
    
    // 删除会议预约
    fun deleteMeetingSchedule(schedule: com.minrray.voicectl.core.meeting.MeetingSchedule) {
        meetingScheduleManager.cancelMeetingSchedule(schedule.id)
        refreshMeetingList()
    }
    
    // 确认参加会议
    fun confirmMeeting(schedule: com.minrray.voicectl.core.meeting.MeetingSchedule, confirm: Boolean) {
        meetingScheduleManager.confirmMeetingSchedule(schedule.id, confirm)
        refreshMeetingList()
    }
    
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "会议预约管理",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Button(onClick = { isExpanded = !isExpanded }) {
                    Text(if (isExpanded) "收起" else "展开")
                }
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                
                // 会议预约表单
                if (isEditing) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = if (selectedSchedule == null) "添加会议预约" else "编辑会议预约",
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    ConfigItem(
                                        modifier = Modifier.weight(1f),
                                        label = "会议类型",
                                        value = meetingType,
                                        enabled = true,
                                        onValueChange = { meetingType = it }
                                    )
                                    ConfigItem(
                                        modifier = Modifier.weight(1f),
                                        label = "会议号",
                                        value = meetingNumber,
                                        enabled = true,
                                        onValueChange = { meetingNumber = it }
                                    )
                                }
                                
                                ConfigItem(
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "会议主题",
                                    value = meetingName,
                                    enabled = true,
                                    onValueChange = { meetingName = it }
                                )
                                
                                ConfigItem(
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "会议时间 (yyyy-MM-dd HH:mm)",
                                    value = meetingTimeStr,
                                    enabled = true,
                                    onValueChange = { meetingTimeStr = it }
                                )
                                
                                ConfigItem(
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "预约人员 (逗号分隔)",
                                    value = attendeesInput,
                                    enabled = true,
                                    onValueChange = { attendeesInput = it }
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(onClick = { resetForm() }) {
                                    Text("取消")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(onClick = { saveMeetingSchedule() }) {
                                    Text("保存")
                                }
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    // 添加会议按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = { isEditing = true }) {
                            Text("添加会议")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                // 会议列表
                if (meetingSchedules.isEmpty()) {
                    Text(
                        text = "暂无会议预约",
                        style = TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                        modifier = Modifier.padding(16.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = GptBorder,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            meetingSchedules.forEach { schedule ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = schedule.meetingName ?: "无主题会议",
                                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                            )
                                            Text(
                                                text = if (schedule.isConfirmed) "已确认" else "未确认",
                                                style = TextStyle(
                                                    fontSize = 12.sp,
                                                    color = if (schedule.isConfirmed) androidx.compose.ui.graphics.Color.Green else MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "${schedule.meetingType} | ${schedule.meetingNumber}",
                                                style = TextStyle(fontSize = 12.sp)
                                            )
                                            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                                            Text(
                                                text = dateFormat.format(java.util.Date(schedule.meetingTime)),
                                                style = TextStyle(fontSize = 12.sp)
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(12.dp))
                                        
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = { loadScheduleToForm(schedule); isEditing = true },
                                                modifier = Modifier.height(32.dp),
                                                contentPadding = PaddingValues(8.dp)
                                            ) {
                                                Text("编辑", fontSize = 12.sp)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = { confirmMeeting(schedule, !schedule.isConfirmed) },
                                                modifier = Modifier.height(32.dp),
                                                contentPadding = PaddingValues(8.dp)
                                            ) {
                                                Text(
                                                    if (schedule.isConfirmed) "取消参加" else "确认参加",
                                                    fontSize = 12.sp
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = { deleteMeetingSchedule(schedule) },
                                                modifier = Modifier.height(32.dp),
                                                contentPadding = PaddingValues(8.dp)
                                            ) {
                                                Text("删除", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 连接信息面板
@Composable
fun ConnectionInfoPanel(
    modifier: Modifier,
    isConnected: Boolean,
    otaConnected: Boolean,
    otaUrl: String,
    serverUrl: String,
    onConnectClick: () -> Unit,
    onOtaUrlChange: (String) -> Unit
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "连接信息",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Button(onClick = onConnectClick) {
                    Log.i("AppScreen", "==>> connect")
                    Text(if (isConnected) "断开" else "连接")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusIndicator(isConnected = otaConnected)
                Text(
                    text = if (otaConnected) "OTA已连接" else "OTA未连接",
                    style = TextStyle(fontSize = 12.sp),
                    modifier = Modifier.padding(start = 4.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                StatusIndicator(isConnected = isConnected)
                Text(
                    text = if (isConnected) "WS已连接" else "WS未连接",
                    style = TextStyle(fontSize = 12.sp),
                    modifier = Modifier.padding(start = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column {
                TextField(
                    value = otaUrl,
                    onValueChange = onOtaUrlChange,
                    label = { Text("OTA服务器地址") },
                    placeholder = { Text("OTA服务器地址，如：http://127.0.0.1:8002/xiaozhi/ota/") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                TextField(
                    value = serverUrl,
                    onValueChange = {},
                    label = { Text("服务器地址") },
                    placeholder = { Text("点击连接按钮后，自动从OTA接口获取") },
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }
    }
}

// 状态指示器
@Composable
fun StatusIndicator(isConnected: Boolean) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .background(
                color = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                shape = androidx.compose.foundation.shape.CircleShape
            )
    )
}

// MCP工具管理面板
@Composable
fun McpToolsPanel(
    isExpanded: Boolean,
    toolsCount: Int,
    onToggleExpand: () -> Unit,
    onAddTool: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MCP 工具管理",
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                Text(text = "$toolsCount 个工具", style = TextStyle(fontSize = 12.sp))
                Button(onClick = onToggleExpand) {
                    Text(if (isExpanded) "收起" else "展开")
                }
            }

            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Column {
                    Text(text = "工具列表将动态插入这里", style = TextStyle(fontSize = 14.sp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onAddTool,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("➕ 添加新工具")
                    }
                }
            }
        }
    }
}

// 权限面板组件
@Composable
fun PermissionPanel(
    hasRecordAudioPermission: Boolean,
    hasReadContactsPermission: Boolean,
    hasCallPhonePermission: Boolean,
    onRequestRecordAudioPermission: () -> Unit,
    onRequestReadContactsPermission: () -> Unit,
    onRequestCallPhonePermission: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "权限管理",
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 录音权限
            PermissionItem(
                permissionName = "录音权限",
                isGranted = hasRecordAudioPermission,
                onRequestPermission = onRequestRecordAudioPermission
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 读取联系人权限
            PermissionItem(
                permissionName = "读取联系人权限",
                isGranted = hasReadContactsPermission,
                onRequestPermission = onRequestReadContactsPermission
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 拨打电话权限
            PermissionItem(
                permissionName = "拨打电话权限",
                isGranted = hasCallPhonePermission,
                onRequestPermission = onRequestCallPhonePermission
            )
        }
    }
}

// 单个权限项组件
@Composable
fun PermissionItem(
    permissionName: String,
    isGranted: Boolean,
    onRequestPermission: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = permissionName)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isGranted) "已授权" else "未授权",
                color = if (isGranted) androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red,
                fontSize = 12.sp
            )
            Button(
                onClick = onRequestPermission,
                enabled = !isGranted,
                modifier = Modifier.size(80.dp, 32.dp)
            ) {
                Text("授权", fontSize = 12.sp)
            }
        }
    }
}

// 设置页面
@Composable
fun SettingsScreen(
    deviceId: String,
    deviceName: String,
    clientId: String,
    token: String,
    otaUrl: String,
    serverUrl: String,
    isConnected: Boolean,
    otaConnected: Boolean,
    isEditingConfig: Boolean,
    isMcpToolsExpanded: Boolean,
    mcpToolsCount: Int,
    testResult: String,
    isTesting: Boolean,
    logEntries: List<String>, // 添加日志列表参数
    // 权限状态
    hasRecordAudioPermission: Boolean,
    hasReadContactsPermission: Boolean,
    hasCallPhonePermission: Boolean,
    // 权限请求回调
    onRequestRecordAudioPermission: () -> Unit,
    onRequestReadContactsPermission: () -> Unit,
    onRequestCallPhonePermission: () -> Unit,
    onBackClick: () -> Unit,
    onToggleEdit: () -> Unit,
    onTestCodec: () -> Unit,
    onConnectClick: () -> Unit,
    onToggleMcpTools: () -> Unit,
    onDeviceIdChange: (String) -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onClientIdChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onOtaUrlChange: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("设置")
                },
                navigationIcon = {
                    TextButton(onClick = onBackClick) {
                        Text("返回")
                    }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // 设备配置面板
            DeviceConfigPanel(
                modifier = Modifier.fillMaxWidth(),
                isEditing = isEditingConfig,
                deviceId = deviceId,
                deviceName = deviceName,
                clientId = clientId,
                token = token,
                testResult = testResult,
                isTesting = isTesting,
                onToggleEdit = onToggleEdit,
                onTestCodec = onTestCodec,
                onDeviceIdChange = onDeviceIdChange,
                onDeviceNameChange = onDeviceNameChange,
                onClientIdChange = onClientIdChange,
                onTokenChange = onTokenChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 连接信息面板
            ConnectionInfoPanel(
                modifier = Modifier.fillMaxWidth(),
                isConnected = isConnected,
                otaConnected = otaConnected,
                otaUrl = otaUrl,
                serverUrl = serverUrl,
                onConnectClick = onConnectClick,
                onOtaUrlChange = onOtaUrlChange
            )

            Spacer(modifier = Modifier.height(16.dp))

            // MCP工具管理面板
            McpToolsPanel(
                isExpanded = isMcpToolsExpanded,
                toolsCount = mcpToolsCount,
                onToggleExpand = onToggleMcpTools,
                onAddTool = { /* 添加工具逻辑 */ }
            )

            Spacer(modifier = Modifier.height(16.dp))
            
            // 会议预约管理面板
            MeetingSchedulePanel(
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 权限管理面板
            PermissionPanel(
                hasRecordAudioPermission = hasRecordAudioPermission,
                hasReadContactsPermission = hasReadContactsPermission,
                hasCallPhonePermission = hasCallPhonePermission,
                onRequestRecordAudioPermission = onRequestRecordAudioPermission,
                onRequestReadContactsPermission = onRequestReadContactsPermission,
                onRequestCallPhonePermission = onRequestCallPhonePermission
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 日志显示面板
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "系统日志",
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 日志内容区域
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = GptBorder,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column {
                            if (logEntries.isEmpty()) {
                                Text(
                                    text = "暂无日志",
                                    style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    modifier = Modifier
                                        .align(Alignment.CenterHorizontally)
                                        .padding(16.dp)
                                )
                            } else {
                                // 倒序显示日志，最新的日志在最下方
                                logEntries.forEach { log ->
                                    Text(
                                        text = log,
                                        style = TextStyle(fontSize = 12.sp),
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
