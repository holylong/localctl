package com.minrray.voicectl.core.activechat

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.minrray.voicectl.core.face.FaceRecognitionManager
import com.minrray.voicectl.core.network.WebSocketHandler
import com.minrray.voicectl.core.weather.WeatherManager
import org.json.JSONObject

class ActiveChatManager(
    private val context: Context,
    private val weatherManager: WeatherManager,
    private val wsHandler: WebSocketHandler,
    private val faceRecognitionManager: FaceRecognitionManager
) {
    companion object {
        private const val TAG = "ActiveChatManager"
        
        const val ACTIVE_CHAT_INTERVAL = 60*60 * 1000L
        
        private var lastActiveChatTime: Long = 0
        
        const val GREETING_COOLDOWN = 24 * 60 * 60 * 1000L
        const val WEATHER_REMINDER_COOLDOWN = 4 * 60 * 60 * 1000L
        const val MEAL_REMINDER_COOLDOWN = 6 * 60 * 60 * 1000L
        const val REST_REMINDER_COOLDOWN = 12 * 60 * 60 * 1000L
        const val EXERCISE_REMINDER_COOLDOWN = 24 * 60 * 60 * 1000L
        
        private const val PREFS_NAME = "active_chat_prefs"
        private const val KEY_LAST_GREETING_DATE = "last_greeting_date"
        
        private const val ACTIVE_CHAT_DELAY = 60*60*1000L
        private const val FACE_REGISTRATION_CHECK_INTERVAL = 10 * 1000L
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var pendingActiveChatRunnable: Runnable? = null
    private var faceRegistrationCheckRunnable: Runnable? = null
    
    init {
        startFaceRegistrationCheck()
    }
    
    private var lastGreetingDate: String
        get() = prefs.getString(KEY_LAST_GREETING_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_GREETING_DATE, value).apply()
    
    private var lastGreetingTime: Long = 0
    private var lastWeatherReminderTime: Long = 0
    private var lastMealReminderTime: Long = 0
    private var lastRestReminderTime: Long = 0
    private var lastExerciseReminderTime: Long = 0
    
    fun handleActiveTrigger(name: String): String? {
        val currentTime = System.currentTimeMillis()
        val currentDate = getCurrentDateString()

        val chatTopic = generateChatTopic(currentTime, name)

        if (faceRecognitionManager.hasRegisteredFaces()&&(currentTime - lastActiveChatTime < ACTIVE_CHAT_INTERVAL)) {
           // Log.i(TAG, "距离上次主动聊天时间不足 ${ACTIVE_CHAT_INTERVAL / 1000} 秒，跳过本次触发")
            return null
        }

        
        if (chatTopic != null) {
            Log.i(TAG, "人脸识别触发，将在5秒后发送主动聊天: $chatTopic")
            
            lastActiveChatTime = currentTime
            
            cancelPendingActiveChat()
            
            val runnable = Runnable {
                Log.i(TAG, "执行延时后的主动聊天: $chatTopic")
                sendActiveChatMessage(chatTopic, name)
                faceRecognitionManager.clearFaceRecognitionRecords(name)
            }
            
            pendingActiveChatRunnable = runnable
            handler.postDelayed(runnable, ACTIVE_CHAT_DELAY)
            
            return chatTopic
        }
        
        return null
    }
    
    private fun cancelPendingActiveChat() {
        pendingActiveChatRunnable?.let {
            handler.removeCallbacks(it)
            pendingActiveChatRunnable = null
            Log.d(TAG, "取消待执行的主动聊天")
        }
    }
    
    private fun generateChatTopic(currentTime: Long, name: String): String? {
        val beijingCalendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Shanghai"))
        beijingCalendar.timeInMillis = currentTime
        val hour = beijingCalendar.get(java.util.Calendar.HOUR_OF_DAY)
        val currentDate = getCurrentDateString()
        val dayOfWeek = beijingCalendar.get(java.util.Calendar.DAY_OF_WEEK)
        val dayOfMonth = beijingCalendar.get(java.util.Calendar.DAY_OF_MONTH)
        val month = beijingCalendar.get(java.util.Calendar.MONTH) + 1
        
        val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
        val isHoliday = isHoliday(month, dayOfMonth)
        val isWorkDay = !isWeekend && !isHoliday
        
        if (!faceRecognitionManager.hasRegisteredFaces()) {
            return "你好，你还没有注册绑定这台设备，需要我现在给你注册吗"
        }
        
   /*     if (lastGreetingDate != currentDate) {
            lastGreetingDate = currentDate
            lastGreetingTime = currentTime
            return "$name，你好，今天又见到你了，需要我给你查询今天的日程安排吗？"
        }
        */
        if (currentTime - lastWeatherReminderTime > WEATHER_REMINDER_COOLDOWN) {
          val weather = weatherManager.getCurrentWeather()
          if (weather != null) {
              lastWeatherReminderTime = currentTime
              when {
                  weather.text.contains("雨") || weather.text.contains("雪") -> {
                      return "今天${weather.text}，记得带伞出门，路上注意安全"
                  }
                  weather.text.contains("雾") || weather.text.contains("霾") -> {
                      return "今天有${weather.text}，出行注意安全，建议佩戴口罩"
                  }
                  weather.temp.toIntOrNull()?.let { it < 5 } == true -> {
                      return "今天气温${weather.temp}度，非常冷，一定要多穿点保暖"
                  }
                  weather.temp.toIntOrNull()?.let { it < 10 } == true -> {
                      return "今天气温${weather.temp}度，比较冷，记得多穿点衣服"
                  }
                  weather.temp.toIntOrNull()?.let { it > 35 } == true -> {
                      return "今天气温${weather.temp}度，酷热难耐，注意防暑降温，多喝水"
                  }
                  weather.temp.toIntOrNull()?.let { it > 30 } == true -> {
                      return "今天气温${weather.temp}度，很热，注意防暑降温"
                  }
                  weather.text.contains("晴") -> {
                      return "今天天气晴朗，是个好天气，心情也会很好吧"
                  }
              }
          }
      }
        
        if (currentTime - lastMealReminderTime > MEAL_REMINDER_COOLDOWN) {
            when (hour) {
                7, 8, 9 -> {
                    lastMealReminderTime = currentTime
                    val messages = listOf(
                        "早餐时间到了，记得吃早餐哦，一天之计在于晨",
                        "早餐很重要，记得按时吃早餐，保持充沛精力",
                        "早上好，该吃早餐了，营养早餐开启美好一天"
                    )
                    return messages.random()
                }
                11, 12, 13 -> {
                    lastMealReminderTime = currentTime
                    val messages = listOf(
                        "午餐时间到了，记得按时吃饭，工作再忙也要照顾好自己",
                        "该吃午饭了，放松一下，享受美食吧",
                        "中午好，午餐时间到了，记得按时用餐"
                    )
                    return messages.random()
                }
                17, 18, 19 -> {
                    lastMealReminderTime = currentTime
                    val messages = listOf(
                        "晚餐时间到了，记得按时吃饭",
                        "下班了，该吃晚饭了，好好犒劳自己",
                        "晚上好，晚餐时间到了，记得按时用餐"
                    )
                    return messages.random()
                }
            }
        }
        
        if (currentTime - lastRestReminderTime > REST_REMINDER_COOLDOWN) {
            when (hour) {
                12, 13 -> {
                    lastRestReminderTime = currentTime
                    val messages = listOf(
                        "午休时间到了，适当休息一下吧，下午工作更高效",
                        "中午了，小憩一会儿，养精蓄锐",
                        "该休息一下了，闭目养神几分钟"
                    )
                    return messages.random()
                }
                21, 22, 23 -> {
                    lastRestReminderTime = currentTime
                    val messages = listOf(
                        "夜深了，早点休息吧，明天又是新的一天",
                        "很晚了，该休息了，保证充足睡眠很重要",
                        "时间不早了，早点休息，晚安"
                    )
                    return messages.random()
                }
            }
        }
        
        if (currentTime - lastExerciseReminderTime > EXERCISE_REMINDER_COOLDOWN) {
            when (hour) {
                18, 19, 20 -> {
                    lastExerciseReminderTime = currentTime
                    val messages = listOf(
                        "下班了，记得适当锻炼身体，保持健康",
                        "工作了一天，运动一下放松身心吧",
                        "晚上好，记得抽时间锻炼身体，健康最重要"
                    )
                    return messages.random()
                }
            }
        }
        
        if (isWorkDay && hour in 9..10) {
            val messages = listOf(
                "$name，上午好！今天有什么重要安排吗？需要我帮你查看日程吗？",
                "$name，新的一天开始了，需要我帮你整理今日日程吗？",
                "$name，早上好！今天有什么会议或安排需要提醒吗？"
            )
            return messages.random()
        }
        
        if (isWorkDay && hour in 14..15) {
            val messages = listOf(
                "$name，下午好！上午的工作进展如何？需要我帮你查看后续安排吗？",
                "$name，下午了，今天还有什么重要事项需要处理吗？",
                "$name，下午好！需要我帮你查询下午的日程安排吗？"
            )
            return messages.random()
        }
        
        if (isWeekend && hour in 10..11) {
            val messages = listOf(
                "$name，周末愉快！今天有什么计划吗？需要我帮你查看日程吗？",
                "$name，周末好！今天准备怎么度过？有什么安排需要我帮忙吗？",
                "$name，周末了，好好放松一下，今天有什么安排吗？"
            )
            return messages.random()
        }
        
        return null
    }
    
    private fun getCurrentDateString(): String {
        val calendar = java.util.Calendar.getInstance()
        val year = calendar.get(java.util.Calendar.YEAR)
        val month = calendar.get(java.util.Calendar.MONTH) + 1
        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
        return "$year-$month-$day"
    }
    
    private fun sendActiveChatMessage(message: String, name: String) {
        try {
            val json = JSONObject().apply {
                put("type", "active_chat")
                put("topic", message)
                put("user_name", name)
            }
            wsHandler.sendRawJsonMessage(json.toString())
            Log.d(TAG, "发送主动聊天话题: $json")
        } catch (e: Exception) {
            Log.e(TAG, "发送主动聊天话题失败", e)
        }
    }
    
    fun resetCooldowns() {
        cancelPendingActiveChat()
        stopFaceRegistrationCheck()
        lastActiveChatTime = 0
        lastGreetingDate = ""
        lastGreetingTime = 0
        lastWeatherReminderTime = 0
        lastMealReminderTime = 0
        lastRestReminderTime = 0
        lastExerciseReminderTime = 0
        Log.d(TAG, "重置所有冷却时间")
    }
    
    fun resetGreetingDate() {
        lastGreetingDate = ""
        lastGreetingTime = 0
        Log.d(TAG, "重置问候日期，下次识别到人脸将重新触发问候")
    }
    
    private fun isHoliday(month: Int, dayOfMonth: Int): Boolean {
        val holidays = listOf(
            Pair(1, 1),
            Pair(2, 10),
            Pair(2, 11),
            Pair(2, 12),
            Pair(2, 13),
            Pair(2, 14),
            Pair(2, 15),
            Pair(2, 16),
            Pair(2, 17),
            Pair(4, 4),
            Pair(4, 5),
            Pair(4, 6),
            Pair(5, 1),
            Pair(5, 2),
            Pair(5, 3),
            Pair(5, 4),
            Pair(5, 5),
            Pair(6, 10),
            Pair(9, 15),
            Pair(9, 16),
            Pair(9, 17),
            Pair(10, 1),
            Pair(10, 2),
            Pair(10, 3),
            Pair(10, 4),
            Pair(10, 5),
            Pair(10, 6),
            Pair(10, 7),
            Pair(10, 8)
        )
        return Pair(month, dayOfMonth) in holidays
    }
    
    private fun getHolidayGreeting(month: Int, dayOfMonth: Int, name: String): String {
        return when {
            month == 1 && dayOfMonth == 1 -> "$name，元旦快乐！新的一年开始了，祝你新年快乐，万事如意！需要我帮你查看新年假期安排吗？"
            month == 2 && dayOfMonth in 10..17 -> "$name，春节快乐！祝你新春大吉，阖家幸福！假期有什么安排吗？"
            month == 4 && dayOfMonth in 4..6 -> "$name，清明节安康！踏青赏春，注意安全，需要我帮你查看假期安排吗？"
            month == 5 && dayOfMonth in 1..5 -> "$name，劳动节快乐！辛苦了，好好休息放松一下，假期有什么计划吗？"
            month == 6 && dayOfMonth == 10 -> "$name，端午节安康！祝你身体健康，假期愉快！需要我帮你查看安排吗？"
            month == 9 && dayOfMonth in 15..17 -> "$name，中秋节快乐！月圆人团圆，祝你阖家幸福！需要我帮你查看假期安排吗？"
            month == 10 && dayOfMonth in 1..8 -> "$name，国庆节快乐！祝你节日愉快，假期有什么计划吗？"
            else -> "$name，节日快乐！今天有什么安排吗？需要我帮你查看日程吗？"
        }
    }
    
    fun startFaceRegistrationCheck() {
        stopFaceRegistrationCheck()
        
        faceRegistrationCheckRunnable = object : Runnable {
            override fun run() {
                val hasRegistered = faceRecognitionManager.hasRegisteredFaces()
               // Log.d(TAG, "人脸注册检查定时器执行，已注册人脸: $hasRegistered")
                
                if (!hasRegistered) {
                    Log.i(TAG, "检测到未注册人脸，发送注册提示")
                    val message = "你好，你还没有注册绑定这台设备，需要我现在给你注册吗"
                    try {
                        val json = JSONObject().apply {
                            put("type", "active_chat")
                            put("topic", message)
                            put("user_name", "")
                        }
                        wsHandler.sendRawJsonMessage(json.toString())
                        Log.d(TAG, "发送人脸注册提示: $json")
                    } catch (e: Exception) {
                        Log.e(TAG, "发送人脸注册提示失败", e)
                    }
                }
                handler.postDelayed(this, 6*FACE_REGISTRATION_CHECK_INTERVAL)
            }
        }
        
        handler.postDelayed(faceRegistrationCheckRunnable!!, 3000)
        Log.d(TAG, "启动人脸注册检查定时器，间隔 ${FACE_REGISTRATION_CHECK_INTERVAL / 1000} 秒")
    }
    
    fun stopFaceRegistrationCheck() {
        faceRegistrationCheckRunnable?.let {
            handler.removeCallbacks(it)
            faceRegistrationCheckRunnable = null
            Log.d(TAG, "停止人脸注册检查定时器")
        }
    }
}
