package com.minrray.voicectl.core.meeting

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

// 会议预约数据类
data class MeetingSchedule(
    val id: String, //agent 自己标定会议的id
    val cloudConfId: String, //存储不同平台实际生成的会议id，用于预约创建取消更新会议
    val cloudConfNumber: String, // 云平台会议号码,用于加入会议
    val cloudConfHostUrl: String,
    val meetingType: String,
    val meetingNumber: String,// 终端和大模型服务器间的meetingNumber，方便用户随意number来创建会议，不用背云平台的一大串会议号码
    val meetingTime: Long,
    val meetingName: String? = null,
    val reminderTime: Long = 0, // 提醒时间，默认0表示不提醒
    val duration: Int = 60, // 会议持续时间（分钟），默认60分钟
    var isConfirmed: Boolean = false, // 是否确认参加
    var attendees: List<String> = emptyList(), // 预约人员列表
    var confStatus: Int = 0 // 会议状态：0-已预约，2-进行中，3-已结束
)

// 会议数据变化监听器接口
interface OnMeetingScheduleChangeListener {
    fun onMeetingScheduleChanged()
}

class MeetingScheduleManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "MeetingScheduleManager"
        private lateinit var instance: MeetingScheduleManager
        private const val SCHEDULE_FILE_NAME = "meeting_schedules.json"
        private const val NOTIFICATION_CHANNEL_ID = "meeting_reminder_channel"
        private const val NOTIFICATION_ID_PREFIX = 1000
        private const val REQUEST_CODE_PREFIX = 2000
        
        fun initialize(context: Context) {
            if (!::instance.isInitialized) {
                instance = MeetingScheduleManager(context)
                instance.createNotificationChannel()
            }
        }
        
        fun getInstance(): MeetingScheduleManager {
            return instance
        }
    }
    
    private val meetingSchedules: MutableList<MeetingSchedule> = mutableListOf()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager: NotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val listeners = mutableListOf<OnMeetingScheduleChangeListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    init {
        // 加载已存储的会议预约
        loadSchedules()
    }
    
    // 创建通知渠道
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "会议提醒"
            val descriptionText = "用于提醒即将开始的会议"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    // 添加监听器
    fun addListener(listener: OnMeetingScheduleChangeListener) {
        listeners.add(listener)
    }
    
    // 移除监听器
    fun removeListener(listener: OnMeetingScheduleChangeListener) {
        listeners.remove(listener)
    }
    
    // 通知所有监听器
    private fun notifyListeners() {
        mainHandler.post {
            listeners.forEach { it.onMeetingScheduleChanged() }
        }
    }
    
    // 保存会议预约
    fun addMeetingSchedule(schedule: MeetingSchedule) {
        // 添加到列表
        meetingSchedules.add(schedule)
        
        // 保存到文件
        saveSchedules()
        
        // 设置提醒
        if (schedule.reminderTime > 0) {
            setMeetingReminder(schedule)
        }
        
        Log.d(TAG, "添加会议预约成功: ${schedule.meetingNumber}")
        notifyListeners()
    }
    
    // 获取所有会议预约
    fun getAllMeetingSchedules(): List<MeetingSchedule> {
        val result = meetingSchedules.toList()
        Log.d(TAG, "getAllMeetingSchedules() - 总会议数量: ${result.size}")
        return result
    }

    // 通过ID获取会议预约
    fun getMeetingScheduleById(id: String): MeetingSchedule? {
        return meetingSchedules.find { it.id == id }
    }
    
    // 获取即将开始的会议预约
    fun getUpcomingMeetingSchedules(): List<MeetingSchedule> {
        val now = System.currentTimeMillis()
        val result = meetingSchedules.filter { it.meetingTime > now }
            .sortedBy { it.meetingTime }
        
        Log.d(TAG, "getUpcomingMeetingSchedules() - 当前时间: ${Date(now)}")
        Log.d(TAG, "getUpcomingMeetingSchedules() - 所有会议数量: ${meetingSchedules.size}")
        Log.d(TAG, "getUpcomingMeetingSchedules() - 未来会议数量: ${result.size}")
        
        return result
    }
    
    // 通过会议号查找会议预约
    fun findMeetingScheduleByNumber(meetingNumber: String): MeetingSchedule? {
        return meetingSchedules.find { it.cloudConfNumber == meetingNumber || it.meetingNumber == meetingNumber }
    }
    
    // 通过会议号和会议时间查找会议预约
    fun findMeetingScheduleByNumberAndTime(meetingNumber: String, meetingTime: Long): MeetingSchedule? {
        return meetingSchedules.find { 
            it.meetingNumber == meetingNumber && it.meetingTime == meetingTime
        }
    }
    
    // 通过会议类型和会议号查找会议预约（更精确）
    fun findMeetingScheduleByTypeAndNumber(meetingType: String, meetingNumber: String): MeetingSchedule? {
        return meetingSchedules.find { 
            it.meetingType == meetingType && 
            (it.cloudConfNumber == meetingNumber || it.meetingNumber == meetingNumber)
        }
    }
    
    // 确认参加会议
    fun confirmMeetingSchedule(id: String, confirm: Boolean) {
        val schedule = meetingSchedules.find { it.id == id }
        if (schedule != null) {
            schedule.isConfirmed = confirm
            saveSchedules()
            Log.d(TAG, "会议预约确认状态更新: $id, 确认参加: $confirm")
            notifyListeners()
        }
    }
    
    // 通过会议号确认参加会议
    fun confirmMeetingScheduleByNumber(meetingNumber: String, confirm: Boolean) {
        val schedule = findMeetingScheduleByNumber(meetingNumber)
        if (schedule != null) {
            schedule.isConfirmed = confirm
            saveSchedules()
            Log.d(TAG, "会议预约确认状态更新: ${schedule.id}, 会议号: $meetingNumber, 确认参加: $confirm")
            notifyListeners()
        }
    }
    
    // 通过会议类型和会议号确认参加会议
    fun confirmMeetingScheduleByTypeAndNumber(meetingType: String, meetingNumber: String, confirm: Boolean) {
        val schedule = findMeetingScheduleByTypeAndNumber(meetingType, meetingNumber)
        if (schedule != null) {
            schedule.isConfirmed = confirm
            saveSchedules()
            Log.d(TAG, "会议预约确认状态更新: ${schedule.id}, 会议类型: $meetingType, 会议号: $meetingNumber, 确认参加: $confirm")
            notifyListeners()
        }
    }
    
    // 通过会议号和会议时间确认参加会议
    fun confirmMeetingScheduleByNumberAndTime(meetingNumber: String, meetingTime: Long, confirm: Boolean): Boolean {
        val schedule = meetingSchedules.find { 
            it.meetingNumber == meetingNumber && it.meetingTime == meetingTime
        }
        if (schedule != null) {
            schedule.isConfirmed = confirm
            saveSchedules()
            Log.d(TAG, "会议预约确认状态更新: ${schedule.id}, 会议号: $meetingNumber, 会议时间: ${Date(meetingTime)}, 确认参加: $confirm")
            notifyListeners()
            return true
        }
        Log.d(TAG, "未找到匹配的会议预约: 会议号: $meetingNumber, 会议时间: ${Date(meetingTime)}")
        return false
    }
    
    // 取消会议预约
    fun cancelMeetingSchedule(id: String) {
        val schedule = meetingSchedules.find { it.id == id }
        if (schedule != null) {
            if (schedule.confStatus != 2) {
                if (schedule.cloudConfId.isNotEmpty()) {
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            val bizConfApi = com.minrray.voicectl.core.mcp.BizConfApi(context)
                            val result = bizConfApi.cancelMeeting(schedule.cloudConfId)
                            if (result.first) {
                                Log.d(TAG, "云平台预约会议取消成功: ${schedule.cloudConfId}")
                                cancelMeetingReminder(schedule)
                                meetingSchedules.remove(schedule)
                                saveSchedules()
                                Log.d(TAG, "取消会议预约成功: $id")
                                notifyListeners()
                            } else {
                                Log.e(TAG, "云平台预约会议取消失败: ${result.second}")
                                Log.d(TAG, "取消会议预约失败: $id")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "取消云平台预约会议异常: ${e.message}", e)
                        }
                    }
                } else {
                    cancelMeetingReminder(schedule)
                    meetingSchedules.remove(schedule)
                    saveSchedules()
                    notifyListeners()
                }
            } else {
                Log.d(TAG, "取消会议预约失败: $id，会议正在召开")
            }
        }
    }
    
    // 通过会议号取消会议预约（删除所有相同会议号的预约记录）
    fun cancelMeetingScheduleByNumber(meetingNumber: String) {
        val schedulesToRemove = meetingSchedules.filter { 
            it.cloudConfNumber == meetingNumber || it.meetingNumber == meetingNumber 
        }
        
        if (schedulesToRemove.isNotEmpty()) {
            Log.d(TAG, "找到 ${schedulesToRemove.size} 个相同会议号的预约记录，准备删除: $meetingNumber")
            
            schedulesToRemove.forEach { schedule ->
                if (schedule.confStatus != 2 ) {
                    if (schedule.cloudConfId.isNotEmpty()) {
                        GlobalScope.launch(Dispatchers.IO) {
                            try {
                                val bizConfApi = com.minrray.voicectl.core.mcp.BizConfApi(context)
                                val result = bizConfApi.cancelMeeting(schedule.cloudConfId)
                                if (result.first) {
                                    Log.d(TAG, "云平台预约会议取消成功: ${schedule.cloudConfId}")
                                    cancelMeetingReminder(schedule)
                                    meetingSchedules.remove(schedule)
                                    saveSchedules()
                                    Log.d(TAG, "取消未确认会议预约成功: ${schedule.id}, 会议号: $meetingNumber")
                                    notifyListeners()
                                } else {
                                    Log.e(TAG, "云平台预约会议取消失败: ${result.second}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "取消云平台预约会议异常: ${e.message}", e)
                            }
                        }
                    } else {
                        cancelMeetingReminder(schedule)
                        meetingSchedules.remove(schedule)
                        saveSchedules()
                        notifyListeners()
                    }
                } else {
                    Log.d(TAG, "会议已召开或结束，不能删除: ${schedule.id}, 会议号: $meetingNumber")
                }
            }
        }
    }
    
    // 通过会议号和会议时间取消会议预约
    fun cancelMeetingScheduleByNumberAndTime(meetingNumber: String, meetingTime: Long): Boolean {
        val schedule = meetingSchedules.find { 
            it.meetingNumber == meetingNumber && it.meetingTime == meetingTime 
        }
        if (schedule != null) {
            if (schedule.confStatus != 2 ) {
                if (schedule.cloudConfId.isNotEmpty()) {
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            val bizConfApi = com.minrray.voicectl.core.mcp.BizConfApi(context)
                            val result = bizConfApi.cancelMeeting(schedule.cloudConfId)
                            if (result.first) {
                                Log.d(TAG, "云平台预约会议取消成功: ${schedule.cloudConfId}")
                                cancelMeetingReminder(schedule)
                                meetingSchedules.remove(schedule)
                                saveSchedules()
                                Log.d(TAG, "取消会议预约成功: ${schedule.id}, 会议号: $meetingNumber, 会议时间: ${Date(meetingTime)}")
                                notifyListeners()
                            } else {
                                Log.e(TAG, "云平台预约会议取消失败: ${result.second}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "取消云平台预约会议异常: ${e.message}", e)
                        }
                    }
                } else {
                    cancelMeetingReminder(schedule)
                    meetingSchedules.remove(schedule)
                    saveSchedules()
                    notifyListeners()
                }
            } else {
                Log.d(TAG, "会议已召开或结束，不能删除: ${schedule.id}, 会议号: $meetingNumber, 会议时间: ${Date(meetingTime)}")
            }
            return true
        }
        Log.d(TAG, "未找到匹配的会议预约: 会议号: $meetingNumber, 会议时间: ${Date(meetingTime)}")
        return false
    }
    
    // 通过会议类型和会议号取消会议预约
    fun cancelMeetingScheduleByTypeAndNumber(meetingType: String, meetingNumber: String) {
        val schedule = findMeetingScheduleByTypeAndNumber(meetingType, meetingNumber)
        if (schedule != null) {
            if (schedule.confStatus != 2 ) {
                if (schedule.cloudConfId.isNotEmpty()) {
                    GlobalScope.launch(Dispatchers.IO) {
                        try {
                            val bizConfApi = com.minrray.voicectl.core.mcp.BizConfApi(context)
                            val result = bizConfApi.cancelMeeting(schedule.cloudConfId)
                            if (result.first) {
                                Log.d(TAG, "云平台预约会议取消成功: ${schedule.cloudConfId}")
                                cancelMeetingReminder(schedule)
                                meetingSchedules.remove(schedule)
                                saveSchedules()
                                Log.d(TAG, "取消未确认会议预约成功: ${schedule.id}, 会议类型: $meetingType, 会议号: $meetingNumber")
                                notifyListeners()
                            } else {
                                Log.e(TAG, "云平台预约会议取消失败: ${result.second}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "取消云平台预约会议异常: ${e.message}", e)
                        }
                    }
                } else {
                    cancelMeetingReminder(schedule)
                    meetingSchedules.remove(schedule)
                    saveSchedules()
                    notifyListeners()
                }
            } else {
                Log.d(TAG, "会议已召开或结束，不能删除记录: ${schedule.id}, 会议类型: $meetingType, 会议号: $meetingNumber")
            }
        }
    }
    
    // 更新会议预约
    fun updateMeetingSchedule(updatedSchedule: MeetingSchedule) {
        val scheduleIndex = meetingSchedules.indexOfFirst { it.id == updatedSchedule.id }
        if (scheduleIndex != -1) {
            val oldSchedule = meetingSchedules[scheduleIndex]
            Log.d(TAG, "开始更新会议预约: ${updatedSchedule.id}")
            Log.d(TAG, "旧会议时间: ${Date(oldSchedule.meetingTime)}, 旧提醒时间: ${Date(oldSchedule.reminderTime)}")
            Log.d(TAG, "新会议时间: ${Date(updatedSchedule.meetingTime)}, 新提醒时间: ${Date(updatedSchedule.reminderTime)}")

            // 取消旧的提醒
            cancelMeetingReminder(oldSchedule)
            Log.d(TAG, "已取消旧提醒: ${updatedSchedule.id}")

            // 更新列表中的会议
            meetingSchedules[scheduleIndex] = updatedSchedule

            // 设置新的提醒
            if (updatedSchedule.reminderTime > System.currentTimeMillis()) {
                setMeetingReminder(updatedSchedule)
                Log.d(TAG, "已设置新提醒: ${updatedSchedule.id}, 提醒时间: ${Date(updatedSchedule.reminderTime)}")
            } else {
                Log.d(TAG, "新提醒时间已过或即将到来，不设置提醒: ${updatedSchedule.id}, 提醒时间: ${Date(updatedSchedule.reminderTime)}")
            }

            // 保存到文件
            saveSchedules()
            Log.d(TAG, "更新会议预约成功: ${updatedSchedule.id}")
            notifyListeners()
        } else {
            Log.d(TAG, "未找到要更新的会议预约: ${updatedSchedule.id}")
        }
    }
    
    // 通过会议号更新会议预约时间
    fun updateMeetingScheduleByNumber(meetingNumber: String, newMeetingTime: Long) {
        val schedule = findMeetingScheduleByNumber(meetingNumber)
        if (schedule != null) {
            val oldSchedule = schedule
            Log.d(TAG, "开始通过会议号更新会议预约: $meetingNumber")
            Log.d(TAG, "旧会议时间: ${Date(oldSchedule.meetingTime)}, 旧提醒时间: ${Date(oldSchedule.reminderTime)}")
            Log.d(TAG, "新会议时间: ${Date(newMeetingTime)}, 新提醒时间: ${Date(newMeetingTime - 5 * 60 * 1000L)}")

            // 取消旧的提醒
            cancelMeetingReminder(oldSchedule)
            Log.d(TAG, "已取消旧提醒: ${schedule.id}")

            // 创建更新后的会议预约
            val updatedSchedule = MeetingSchedule(
                id = schedule.id,
                cloudConfId = schedule.cloudConfId,
                cloudConfNumber = schedule.cloudConfNumber,
                cloudConfHostUrl = schedule.cloudConfHostUrl,
                meetingType = schedule.meetingType,
                meetingNumber = schedule.meetingNumber,
                meetingTime = newMeetingTime,
                meetingName = schedule.meetingName,
                reminderTime = newMeetingTime - 5 * 60 * 1000L, // 新时间前5分钟提醒
                isConfirmed = schedule.isConfirmed,
                attendees = schedule.attendees,
                confStatus = schedule.confStatus
            )

            // 更新列表中的会议
            val scheduleIndex = meetingSchedules.indexOfFirst { it.id == schedule.id }
            if (scheduleIndex != -1) {
                meetingSchedules[scheduleIndex] = updatedSchedule
            }

            // 设置新的提醒
            if (updatedSchedule.reminderTime > System.currentTimeMillis()) {
                setMeetingReminder(updatedSchedule)
                Log.d(TAG, "已设置新提醒: ${updatedSchedule.id}, 提醒时间: ${Date(updatedSchedule.reminderTime)}")
            } else {
                Log.d(TAG, "新提醒时间已过或即将到来，不设置提醒: ${updatedSchedule.id}, 提醒时间: ${Date(updatedSchedule.reminderTime)}")
            }

            // 保存到文件
            saveSchedules()
            Log.d(TAG, "通过会议号更新会议预约成功: $meetingNumber")
            notifyListeners()
        } else {
            Log.d(TAG, "未找到要更新的会议预约: $meetingNumber")
        }
    }

    // 更新会议状态
    fun updateMeetingStatus(cloudConfNumber: String, status: Int): Boolean {
        val schedule = findMeetingScheduleByNumber(cloudConfNumber)
        if (schedule != null) {
            // 如果要将会议状态设置为进行中（2），先确保没有其他会议正在进行
            if (status == 2) {
                val currentRunningMeetings = meetingSchedules.filter { it.confStatus == 2 && it.cloudConfNumber != cloudConfNumber }
                if (currentRunningMeetings.isNotEmpty()) {
                    Log.d(TAG, "发现${currentRunningMeetings.size}个正在进行的会议，将其状态更新为已结束")
                    currentRunningMeetings.forEach { runningMeeting ->
                        runningMeeting.confStatus = 3
                        Log.d(TAG, "会议状态已更新为已结束: ${runningMeeting.meetingNumber}")
                    }
                }
            }
            
            schedule.confStatus = status
            saveSchedules()
            val statusText = when (status) {
                0 -> "已预约"
                2 -> "进行中"
                3 -> "已结束"
                else -> "未知状态"
            }
            Log.d(TAG, "会议状态更新成功: $cloudConfNumber, 状态: $statusText")
            notifyListeners()
            return true
        }
        Log.d(TAG, "未找到要更新状态的会议: $cloudConfNumber")
        return false
    }

    // 根据会议状态查找会议
    fun findMeetingScheduleByStatus(status: Int): MeetingSchedule? {
        return meetingSchedules.find { it.confStatus == status }
    }

    // 设置会议提醒
    private fun setMeetingReminder(schedule: MeetingSchedule) {
        // 创建提醒Intent
        val intent = Intent(context, MeetingReminderBroadcastReceiver::class.java).apply {
            putExtra("meeting_id", schedule.id)
            putExtra("meeting_type", schedule.meetingType)
            putExtra("meeting_number", schedule.meetingNumber)
            putExtra("meeting_time", schedule.meetingTime)
            putExtra("meeting_name", schedule.meetingName)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (REQUEST_CODE_PREFIX + schedule.id.hashCode()) and 0xffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 设置提醒时间
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                schedule.reminderTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                schedule.reminderTime,
                pendingIntent
            )
        }
        
        Log.d(TAG, "设置会议提醒成功: ${schedule.id}, 提醒时间: ${Date(schedule.reminderTime)}")
    }
    
    // 取消会议提醒
    private fun cancelMeetingReminder(schedule: MeetingSchedule) {
        val intent = Intent(context, MeetingReminderBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (REQUEST_CODE_PREFIX + schedule.id.hashCode()) and 0xffff,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "取消会议提醒成功: ${schedule.id}")
    }
    
    // 保存会议预约到文件
    private fun saveSchedules() {
        try {
            val file = File(context.filesDir, SCHEDULE_FILE_NAME)
            val jsonArray = JSONArray()
            
            meetingSchedules.forEach { schedule ->
                val jsonObject = JSONObject()
                jsonObject.put("id", schedule.id)
                jsonObject.put("cloudConfId", schedule.cloudConfId)
                jsonObject.put("cloudConfNumber", schedule.cloudConfNumber)
                jsonObject.put("cloudConfHostUrl", schedule.cloudConfHostUrl)
                jsonObject.put("meetingType", schedule.meetingType)
                jsonObject.put("meetingNumber", schedule.meetingNumber)
                jsonObject.put("meetingTime", schedule.meetingTime)
                jsonObject.put("meetingName", schedule.meetingName)
                jsonObject.put("reminderTime", schedule.reminderTime)
                jsonObject.put("isConfirmed", schedule.isConfirmed)
                // 序列化预约人员列表
                val attendeesArray = JSONArray()
                schedule.attendees.forEach { attendeesArray.put(it) }
                jsonObject.put("attendees", attendeesArray)
                jsonObject.put("confStatus", schedule.confStatus)
                jsonArray.put(jsonObject)
            }
            
            val outputStream = FileOutputStream(file)
            outputStream.write(jsonArray.toString().toByteArray())
            outputStream.close()
            
            Log.d(TAG, "会议预约保存成功，共 ${meetingSchedules.size} 个预约")
        } catch (e: IOException) {
            Log.e(TAG, "保存会议预约失败: ${e.message}", e)
        }
    }
    
    // 从文件加载会议预约
    private fun loadSchedules() {
        try {
            val file = File(context.filesDir, SCHEDULE_FILE_NAME)
            if (file.exists()) {
                val inputStream = FileInputStream(file)
                val content = inputStream.bufferedReader().use { it.readText() }
                inputStream.close()
                
                val jsonArray = JSONArray(content)
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    // 反序列化预约人员列表
                    val attendeesArray = jsonObject.optJSONArray("attendees")
                    val attendeesList = mutableListOf<String>()
                    if (attendeesArray != null) {
                        for (i in 0 until attendeesArray.length()) {
                            attendeesList.add(attendeesArray.getString(i))
                        }
                    }
                    
                    val schedule = MeetingSchedule(
                        id = jsonObject.getString("id"),
                        cloudConfId = jsonObject.optString("cloudConfId", "") ?: jsonObject.optString("meetingId", ""),
                        cloudConfNumber = jsonObject.optString("cloudConfNumber", ""),
                        cloudConfHostUrl = jsonObject.optString("cloudConfHostUrl", ""),
                        meetingType = jsonObject.getString("meetingType"),
                        meetingNumber = jsonObject.getString("meetingNumber"),
                        meetingTime = jsonObject.getLong("meetingTime"),
                        meetingName = jsonObject.optString("meetingName", ""),
                        reminderTime = jsonObject.getLong("reminderTime"),
                        isConfirmed = jsonObject.getBoolean("isConfirmed"),
                        attendees = attendeesList,
                        confStatus = jsonObject.optInt("confStatus", 0)
                    )
                    meetingSchedules.add(schedule)
                    
                    // 重新设置提醒（如果提醒时间未过）
                    if (schedule.reminderTime > System.currentTimeMillis()) {
                        setMeetingReminder(schedule)
                    }
                }
                
                Log.d(TAG, "会议预约加载成功，共 ${meetingSchedules.size} 个预约")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载会议预约失败: ${e.message}", e)
        }
    }
}