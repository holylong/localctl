package com.minrray.voicectl.core.meeting

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.minrray.voicectl.R
import com.minrray.voicectl.core.network.WebSocketHandler
import com.minrray.voicectl.core.audio.AudioRecorder
import com.minrray.voicectl.core.service.WakeWordService
import com.minrray.voicectl.core.network.OtaConnector
import org.json.JSONObject
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MeetingReminderBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MeetingReminderBR"
        private const val NOTIFICATION_CHANNEL_ID = "meeting_reminder_channel"
        private const val NOTIFICATION_ID_PREFIX = 1000
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "收到会议提醒广播")

        // 获取会议信息
        val meetingId = intent.getStringExtra("meeting_id") ?: return
        val meetingType = intent.getStringExtra("meeting_type") ?: ""
        val meetingNumber = intent.getStringExtra("meeting_number") ?: ""
        val meetingTime = intent.getLongExtra("meeting_time", 0)
        val meetingName = intent.getStringExtra("meeting_name")

        // 发送会议提醒广播到AppScreen，让UI处理状态更新
        sendMeetingReminderBroadcast(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)

        Log.d(TAG, "已发送会议提醒广播到AppScreen: $meetingId")

        // 直接发送会议提醒消息到服务端
        GlobalScope.launch(Dispatchers.IO) {
            sendMeetingReminderToServer(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)
        }
    }

    // 直接处理会议提醒，模仿唤醒词流程
    private suspend fun handleMeetingReminderDirectly(
        context: Context,
        meetingId: String,
        meetingType: String,
        meetingNumber: String,
        meetingTime: Long,
        meetingName: String?
    ) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "开始直接处理会议提醒: $meetingId")

                // 创建提醒消息文本
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                dateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                val meetingTimeStr = dateFormat.format(java.util.Date(meetingTime))
                val reminderText = "[会议提醒] 您有一个${meetingType}会议 (${meetingNumber}) 即将开始，会议主题：${meetingName ?: "无"}，会议时间：${meetingTimeStr}。是否参加、不参加还是延后？会议ID：${meetingId}"

                // 1. 停止唤醒词服务
                Log.d(TAG, "停止唤醒词服务")
                val stopIntent = Intent(context, WakeWordService::class.java)
                context.stopService(stopIntent)

                // 2. 获取AudioRecorder实例
                val audioRecorder = AudioRecorder.getInstance()
                val webSocketHandler = WebSocketHandler.getInstance()

                // 3. 检查录音权限（简化处理，假设有权限）
                // 在实际应用中，需要检查权限

                // 4. 启动录音（如果未在录音）
                if (!audioRecorder.isRecording) {
                    Log.d(TAG, "启动录音")
                    audioRecorder.start(enableOpusEncoding = true)
                    // 等待录音启动
                    kotlinx.coroutines.delay(1000)
                }

                // 5. 连接WebSocket（如果未连接）
                if (!webSocketHandler.isConnected()) {
                    Log.d(TAG, "WebSocket未连接，尝试连接")

                    // 获取设备信息（简化：从SharedPreferences读取）
                    val sharedPref = context.getSharedPreferences("device_config", Context.MODE_PRIVATE)
                    val deviceId = sharedPref.getString("device_id", "") ?: ""
                    val clientId = sharedPref.getString("client_id", "") ?: ""
                    val otaUrl = sharedPref.getString("ota_url", "http://10.10.20.155:9091") ?: "http://10.10.20.155:9091"

                    Log.d(TAG, "设备信息: deviceId=$deviceId, clientId=$clientId, otaUrl=$otaUrl")

                    if (deviceId.isNotEmpty() && clientId.isNotEmpty()) {
                        val otaConnector = OtaConnector.getInstance()
                        val wsUrl = otaConnector.getWebSocketUrl(otaUrl, deviceId, clientId)

                        if (wsUrl != null) {
                            Log.d(TAG, "获取到WebSocket地址: $wsUrl")
                            webSocketHandler.connect(wsUrl)
                            // 等待连接建立
                            kotlinx.coroutines.delay(2000)
                        } else {
                            Log.e(TAG, "获取WebSocket地址失败")
                            // 显示回退通知
                            withContext(Dispatchers.Main) {
                                showFallbackNotification(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)
                            }
                            return@withContext
                        }
                    } else {
                        Log.e(TAG, "设备信息不完整，无法连接WebSocket")
                        // 显示回退通知
                        withContext(Dispatchers.Main) {
                            showFallbackNotification(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)
                        }
                        return@withContext
                    }
                } else {
                    Log.d(TAG, "WebSocket已连接")
                }

                // 6. 发送会议提醒消息
                Log.d(TAG, "发送会议提醒消息: $reminderText")
                val success = webSocketHandler.sendTextMessage(reminderText)

                if (success) {
                    Log.d(TAG, "会议提醒消息发送成功: $meetingId")
                } else {
                    Log.e(TAG, "会议提醒消息发送失败: $meetingId")
                    // 显示回退通知
                    withContext(Dispatchers.Main) {
                        showFallbackNotification(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "处理会议提醒失败: ${e.message}", e)
                // 显示回退通知
                withContext(Dispatchers.Main) {
                    showFallbackNotification(context, meetingId, meetingType, meetingNumber, meetingTime, meetingName)
                }
            }
        }
    }

    // 发送会议提醒广播到AppScreen
    private fun sendMeetingReminderBroadcast(
        context: Context,
        meetingId: String,
        meetingType: String,
        meetingNumber: String,
        meetingTime: Long,
        meetingName: String?
    ) {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val meetingTimeStr = dateFormat.format(java.util.Date(meetingTime))
        val reminderText = "[会议提醒] 您有一个${meetingType}会议 (${meetingNumber}) 即将开始，会议主题：${meetingName ?: "无"}，会议时间：${meetingTimeStr}。是否参加、不参加还是延后？会议ID：${meetingId}"

        val broadcastIntent = Intent("com.minrray.voicectl.MEETING_REMINDER").apply {
            putExtra("meeting_id", meetingId)
            putExtra("meeting_type", meetingType)
            putExtra("meeting_number", meetingNumber)
            putExtra("meeting_time", meetingTime)
            putExtra("meeting_name", meetingName)
            putExtra("reminder_text", reminderText)
            // 添加FLAG_INCLUDE_STOPPED_PACKAGES确保应用未运行时也能接收
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            // 指定包名，确保广播只发送到当前应用，解决Android 8.0+隐式广播限制问题
            setPackage(context.packageName)
        }

        Log.d(TAG, "准备发送会议提醒广播: $meetingId, 包名: ${context.packageName}")

        // 发送广播，不需要权限标志，因为是应用内部广播
        try {
            context.sendBroadcast(broadcastIntent)
            Log.d(TAG, "已发送广播: $meetingId")
        } catch (e: Exception) {
            Log.e(TAG, "发送广播失败: ${e.message}", e)
        }
    }

    // 发送会议提醒消息到服务器，返回是否成功
    private suspend fun sendMeetingReminderToServer(
        context: Context,
        meetingId: String,
        meetingType: String,
        meetingNumber: String,
        meetingTime: Long,
        meetingName: String?
    ): Boolean {
        try {
            val webSocketHandler = WebSocketHandler.getInstance()
            val otaConnector = OtaConnector.getInstance()

            // 检查 WebSocket 是否连接
            if (!webSocketHandler.isConnected()) {
                Log.d(TAG, "WebSocket未连接，尝试连接")

                // 获取设备信息
                val sharedPref = context.getSharedPreferences("device_config", Context.MODE_PRIVATE)
                val deviceId = sharedPref.getString("device_id", "") ?: ""
                val clientId = sharedPref.getString("client_id", "") ?: ""
                val otaUrl = sharedPref.getString("ota_url", "http://10.10.20.155:9091") ?: "http://10.10.20.155:9091"

                Log.d(TAG, "设备信息: deviceId=$deviceId, clientId=$clientId, otaUrl=$otaUrl")

                if (deviceId.isNotEmpty() && clientId.isNotEmpty()) {
                    val wsUrl = otaConnector.getWebSocketUrl(otaUrl, deviceId, clientId)

                    if (wsUrl != null) {
                        Log.d(TAG, "获取到WebSocket地址: $wsUrl")
                        webSocketHandler.connect(wsUrl)
                        // 等待连接建立
                        kotlinx.coroutines.delay(2000)
                    } else {
                        Log.e(TAG, "获取WebSocket地址失败")
                        return false
                    }
                } else {
                    Log.e(TAG, "设备信息不完整，无法连接WebSocket")
                    return false
                }
            }

            // 再次检查连接状态
            if (!webSocketHandler.isConnected()) {
                Log.e(TAG, "WebSocket连接失败")
                return false
            }

            // 创建提醒消息文本
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            dateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            val meetingTimeStr = dateFormat.format(java.util.Date(meetingTime))
            val reminderText = "[会议提醒] 您有一个${meetingType}会议 (${meetingNumber}) 即将开始，会议主题：${meetingName ?: "无"}，会议时间：${meetingTimeStr}。是否参加、不参加还是延后？会议ID：${meetingId}"

            // 发送文本消息，WebSocketHandler会将其包装为listen消息
            val success = webSocketHandler.sendTextMessage(reminderText)
            if (success) {
                Log.d(TAG, "已发送会议提醒消息: $reminderText")
                return true
            } else {
                Log.e(TAG, "发送会议提醒消息失败")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "发送会议提醒消息失败: ${e.message}", e)
            return false
        }
    }

    // 回退方案：显示通知
    private fun showFallbackNotification(
        context: Context,
        meetingId: String,
        meetingType: String,
        meetingNumber: String,
        meetingTime: Long,
        meetingName: String?
    ) {
        Log.d(TAG, "使用回退方案：显示通知")

        // 创建通知标题和内容
        val title = if (meetingName.isNullOrEmpty()) {
            "会议提醒"
        } else {
            "$meetingName 会议提醒"
        }

        val content = "您有一个${meetingType}会议 (${meetingNumber}) 即将开始，是否参加？"

        // 创建参加和不参加的PendingIntent
        val confirmIntent = createConfirmIntent(context, meetingId, true)
        val declineIntent = createConfirmIntent(context, meetingId, false)

        // 构建通知
        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.icon) // 使用应用图标
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(R.drawable.icon, "参加", confirmIntent)
            .addAction(R.drawable.icon, "不参加", declineIntent)

        // 显示通知
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationId = (NOTIFICATION_ID_PREFIX + meetingId.hashCode()) and 0xffff
        notificationManager.notify(notificationId, builder.build())

        Log.d(TAG, "显示回退通知: $meetingId")
    }
    
    // 创建确认Intent
    private fun createConfirmIntent(context: Context, meetingId: String, confirm: Boolean): PendingIntent {
        val intent = Intent(context, MeetingConfirmBroadcastReceiver::class.java).apply {
            putExtra("meeting_id", meetingId)
            putExtra("confirm", confirm)
        }
        
        return PendingIntent.getBroadcast(
            context,
            (meetingId.hashCode() + (if (confirm) 1 else 0)) and 0xffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

// 会议确认广播接收器
class MeetingConfirmBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MeetingConfirmBR"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "收到会议确认广播")
        
        // 获取会议信息和确认状态
        val meetingId = intent.getStringExtra("meeting_id") ?: return
        val confirm = intent.getBooleanExtra("confirm", false)
        
        // 更新会议确认状态
        val meetingManager = MeetingScheduleManager.getInstance()
        val schedule = meetingManager.getMeetingScheduleById(meetingId)
        
        if (schedule != null) {
            if (confirm) {
                // 用户确认参加
                meetingManager.confirmMeetingSchedule(meetingId, true)
                Log.d(TAG, "用户确认参加会议: $meetingId")
            } else {
                // 用户不参加
                Log.d(TAG, "用户不参加会议: $meetingId, confStatus: ${schedule.confStatus}, isConfirmed: ${schedule.isConfirmed}")
                
                // 只有当confStatus=0且未确认参加时才删除会议
                if (schedule.confStatus == 0 && !schedule.isConfirmed) {
                    Log.d(TAG, "会议未召开且未确认参加，准备删除会议: $meetingId")
                    
                    // 先取消云平台预约会议
                    if (schedule.cloudConfId.isNotEmpty()) {
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                val bizConfApi = com.minrray.voicectl.core.mcp.BizConfApi(context)
                                val result = bizConfApi.cancelMeeting(schedule.cloudConfId)
                                if (result.first) {
                                    Log.d(TAG, "云平台预约会议取消成功: ${schedule.cloudConfId}")
                                } else {
                                    Log.e(TAG, "云平台预约会议取消失败: ${result.second}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "取消云平台预约会议异常: ${e.message}", e)
                            }
                        }
                    }
                    
                    // 删除本地会议预约记录
                    meetingManager.cancelMeetingSchedule(meetingId)
                    Log.d(TAG, "会议预约记录已删除: $meetingId")
                } else {
                    Log.d(TAG, "会议不符合删除条件，仅更新确认状态: $meetingId")
                    meetingManager.confirmMeetingSchedule(meetingId, false)
                }
            }
        } else {
            Log.e(TAG, "未找到会议: $meetingId")
        }
    }
}