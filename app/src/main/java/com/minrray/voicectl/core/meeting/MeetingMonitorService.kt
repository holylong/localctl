package com.minrray.voicectl.core.meeting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.minrray.voicectl.core.mcp.McpToolsManager
import org.json.JSONObject
import java.util.*
import kotlin.concurrent.schedule

class MeetingMonitorService : Service() {
    companion object {
        private const val TAG = "MeetingMonitorService"
        private const val CHANNEL_ID = "meeting_monitor_channel"
        private const val NOTIFICATION_ID = 3000
        private const val MONITOR_INTERVAL = 60 * 1000L // 监控间隔，1分钟
    }
    
    private var timer: Timer? = null
    private val meetingScheduleManager by lazy {
        MeetingScheduleManager.getInstance()
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "会议监控服务创建")

        // 确保在服务进程独立启动时也能初始化
        MeetingScheduleManager.initialize(applicationContext)
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 启动监控
        startMonitoring()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "会议监控服务启动")
        return START_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "会议监控服务销毁")
        
        // 停止监控
        stopMonitoring()
    }
    
    // 创建通知渠道
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "会议监控服务"
            val descriptionText = "监控会议时间，自动加入已确认的会议"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            
            val notificationManager: NotificationManager = 
                getSystemService(NotificationManager::class.java) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    // 创建通知
    private fun createNotification(): android.app.Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.minrray.voicectl.R.drawable.icon)
                .setContentTitle("会议监控服务")
                .setContentText("正在监控即将开始的会议")
                .setPriority(android.app.Notification.PRIORITY_LOW)
                .build()
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
                .setSmallIcon(com.minrray.voicectl.R.drawable.icon)
                .setContentTitle("会议监控服务")
                .setContentText("正在监控即将开始的会议")
                .setPriority(android.app.Notification.PRIORITY_LOW)
                .build()
        }
    }
    
    // 启动监控
    private fun startMonitoring() {
        timer = Timer().apply {
            schedule(0, MONITOR_INTERVAL) { // 立即开始，然后每隔MONITOR_INTERVAL执行一次
                checkUpcomingMeetings()
            }
        }
        Log.d(TAG, "会议监控已启动，监控间隔: ${MONITOR_INTERVAL / 1000}秒")
    }
    
    // 停止监控
    private fun stopMonitoring() {
        timer?.cancel()
        timer = null
        Log.d(TAG, "会议监控已停止")
    }
    
    // 检查即将开始的会议
    private fun checkUpcomingMeetings() {
        Log.d(TAG, "开始检查即将开始的会议")
        
        // 先获取并输出所有会议，便于调试
        val allMeetings = meetingScheduleManager.getAllMeetingSchedules()
        
        // 获取即将开始的会议
        val now = System.currentTimeMillis()
        val upcomingMeetings = meetingScheduleManager.getUpcomingMeetingSchedules()
        
        Log.d(TAG, "当前共有 ${upcomingMeetings.size} 个即将开始的会议")
        
        // 输出每个即将开始会议的详细信息
      /*  upcomingMeetings.forEachIndexed { index, schedule ->
            val meetingTime = Date(schedule.meetingTime)
            val timeDiff = schedule.meetingTime - now
            val timeDiffMinutes = timeDiff / 1000 / 60
            
            Log.d(TAG, "即将开始的会议 ${index + 1}: ")
            Log.d(TAG, "  ID: ${schedule.id}")
            Log.d(TAG, "  会议类型: ${schedule.meetingType}")
            Log.d(TAG, "  会议号: ${schedule.meetingNumber}")
            Log.d(TAG, "  会议名称: ${schedule.meetingName ?: "无"}")
            Log.d(TAG, "  会议时间: $meetingTime")
            Log.d(TAG, "  距离开始时间: ${timeDiffMinutes} 分钟")
            Log.d(TAG, "  已确认参加: ${schedule.isConfirmed}")
        }*/
        
        // 检查是否有3分钟内开始且已确认参加的会议
        val meetingsToJoin = upcomingMeetings.filter { schedule ->
            val timeDiff = schedule.meetingTime - now
            timeDiff in 0..3*60*1000L && schedule.confStatus==0/*schedule.isConfirmed*/
        }
        
        Log.d(TAG, "共有 ${meetingsToJoin.size} 个会议将在3分钟内开始且已确认参加")
        
        meetingsToJoin.forEach { schedule ->
            val timeDiff = schedule.meetingTime - now
            Log.d(TAG, "会议 ${schedule.id} (${schedule.meetingNumber}) 将在 ${timeDiff / 1000} 秒后开始，用户已确认参加，准备自动加入")
            
            // 自动加入会议
            autoJoinMeeting(schedule)
            
            // 加入会议后，取消该会议的后续监控
            meetingScheduleManager.cancelMeetingSchedule(schedule.id)
        }
    }
    
    // 自动加入会议
    private fun autoJoinMeeting(schedule: MeetingSchedule) {
        Log.d(TAG, "正在自动加入会议: ${schedule.meetingNumber}")
        
        try {
            if(schedule.confStatus!=0)
                return;
            // 调用MCP工具加入会议
            val mcpToolsManager = McpToolsManager.getInstance()
            val params = JSONObject().apply {
                put("meeting_type", schedule.meetingType)
                put("meeting_number", schedule.meetingNumber)
            }
            
            val result = mcpToolsManager.executeMcpTool("self.join_meeting", params)
            Log.d(TAG, "自动加入会议结果: $result")
            
        } catch (e: Exception) {
            Log.e(TAG, "自动加入会议失败: ${e.message}", e)
        }
    }
}
