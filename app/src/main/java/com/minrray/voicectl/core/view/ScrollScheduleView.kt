package com.minrray.voicectl.core.view

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.minrray.voicectl.core.meeting.MeetingScheduleManager
import com.minrray.voicectl.core.meeting.OnMeetingScheduleChangeListener
import java.text.SimpleDateFormat
import java.util.*

class ScrollScheduleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), OnMeetingScheduleChangeListener {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val scheduleItems = mutableListOf<ScheduleItem>()
    private var scrollOffset = 0f
    private val scrollSpeed = 0.5f
    private val itemHeight = 45f
    private var isScrolling = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastUpdateTime = 0L
    private val updateThrottle = 100L
    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (isScrolling) {
                scrollOffset += scrollSpeed
                val targetOffset = (Math.floor(scrollOffset / itemHeight.toDouble()).toInt() + 1) * itemHeight
                
                if (scrollOffset >= targetOffset || scrollOffset >= getTotalHeight()) {
                    scrollOffset = if (scrollOffset >= getTotalHeight()) 0f else targetOffset
                    isScrolling = false
                    invalidate()
                    handler.postDelayed(this, 3000)
                } else {
                    invalidate()
                    handler.postDelayed(this, 30)
                }
            } else {
                isScrolling = true
                handler.postDelayed(this, 30)
            }
        }
    }

    private val dateFormat = SimpleDateFormat("MM/dd", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA).apply {
        timeZone = TimeZone.getTimeZone("Asia/Shanghai")
    }

    private val scheduleManager = MeetingScheduleManager.getInstance()
    private var queryDate: Date? = null
    private var restoreRunnable: Runnable? = null

    data class ScheduleItem(
        val date: Date,
        val title: String,
        val location: String? = null,
        val isImportant: Boolean = false,
        val meetingNumber: String? = null,
        val confStatus: Int = 0
    )

    init {
        setupPaints()
        setBackgroundColor(Color.TRANSPARENT)
        loadTodaySchedules()
        startAutoScroll()
        scheduleManager.addListener(this)
    }

    private fun setupPaints() {
        paint.color = Color.TRANSPARENT
        paint.style = Paint.Style.FILL

        textPaint.color = Color.BLACK
        textPaint.textSize = 16f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun loadTodaySchedules() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < updateThrottle) {
            return
        }
        lastUpdateTime = currentTime
        
        val schedules = scheduleManager.getAllMeetingSchedules()
        val todayCalendar = Calendar.getInstance()
        todayCalendar.set(Calendar.HOUR_OF_DAY, 0)
        todayCalendar.set(Calendar.MINUTE, 0)
        todayCalendar.set(Calendar.SECOND, 0)
        todayCalendar.set(Calendar.MILLISECOND, 0)

        val newItems = mutableListOf<ScheduleItem>()
        schedules.forEach { schedule ->
            val scheduleCalendar = Calendar.getInstance()
            scheduleCalendar.timeInMillis = schedule.meetingTime

            if (scheduleCalendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR) &&
                scheduleCalendar.get(Calendar.MONTH) == todayCalendar.get(Calendar.MONTH) &&
                scheduleCalendar.get(Calendar.DAY_OF_MONTH) == todayCalendar.get(Calendar.DAY_OF_MONTH)) {
                
                val item = ScheduleItem(
                    date = Date(schedule.meetingTime),
                    title = schedule.meetingName ?: "会议",
                    location = null,
                    isImportant = schedule.isConfirmed,
                    meetingNumber = schedule.meetingNumber,
                    confStatus = schedule.confStatus
                )
                newItems.add(item)
            }
        }

        newItems.sortBy { it.date }
        
        val changeType = detectChangeType(scheduleItems, newItems)
        
        when (changeType) {
            ChangeType.NONE -> {
                return
            }
            ChangeType.STATUS_ONLY -> {
                updateStatusOnly(scheduleItems, newItems)
                invalidate()
            }
            ChangeType.FULL_UPDATE -> {
                scheduleItems.clear()
                scheduleItems.addAll(newItems)
                scrollOffset = 0f
                invalidate()
            }
        }
    }
    
    private enum class ChangeType {
        NONE, STATUS_ONLY, FULL_UPDATE
    }
    
    private fun detectChangeType(oldItems: List<ScheduleItem>, newItems: List<ScheduleItem>): ChangeType {
        if (oldItems.size != newItems.size) {
            return ChangeType.FULL_UPDATE
        }
        
        var hasStatusChange = false
        
        for (i in oldItems.indices) {
            val oldItem = oldItems[i]
            val newItem = newItems[i]
            
            if (oldItem.meetingNumber != newItem.meetingNumber) {
                return ChangeType.FULL_UPDATE
            }
            if (oldItem.date != newItem.date) {
                return ChangeType.FULL_UPDATE
            }
            if (oldItem.title != newItem.title) {
                return ChangeType.FULL_UPDATE
            }
            if (oldItem.isImportant != newItem.isImportant) {
                return ChangeType.FULL_UPDATE
            }
            if (oldItem.confStatus != newItem.confStatus) {
                hasStatusChange = true
            }
        }
        
        return if (hasStatusChange) ChangeType.STATUS_ONLY else ChangeType.NONE
    }
    
    private fun updateStatusOnly(oldItems: MutableList<ScheduleItem>, newItems: List<ScheduleItem>) {
        for (i in oldItems.indices) {
            if (oldItems[i].confStatus != newItems[i].confStatus) {
                oldItems[i] = newItems[i]
            }
        }
    }

    internal fun loadSchedulesForDate(date: Date) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < updateThrottle) {
            return
        }
        lastUpdateTime = currentTime
        
        val schedules = scheduleManager.getAllMeetingSchedules()
        val targetCalendar = Calendar.getInstance()
        targetCalendar.time = date
        targetCalendar.set(Calendar.HOUR_OF_DAY, 0)
        targetCalendar.set(Calendar.MINUTE, 0)
        targetCalendar.set(Calendar.SECOND, 0)
        targetCalendar.set(Calendar.MILLISECOND, 0)

        val newItems = mutableListOf<ScheduleItem>()
        schedules.forEach { schedule ->
            val scheduleCalendar = Calendar.getInstance()
            scheduleCalendar.timeInMillis = schedule.meetingTime

            if (scheduleCalendar.get(Calendar.YEAR) == targetCalendar.get(Calendar.YEAR) &&
                scheduleCalendar.get(Calendar.MONTH) == targetCalendar.get(Calendar.MONTH) &&
                scheduleCalendar.get(Calendar.DAY_OF_MONTH) == targetCalendar.get(Calendar.DAY_OF_MONTH)) {
                
                val item = ScheduleItem(
                    date = Date(schedule.meetingTime),
                    title = schedule.meetingName ?: "会议",
                    location = null,
                    isImportant = schedule.isConfirmed,
                    meetingNumber = schedule.meetingNumber,
                    confStatus = schedule.confStatus
                )
                newItems.add(item)
            }
        }

        newItems.sortBy { it.date }
        
        val changeType = detectChangeType(scheduleItems, newItems)
        
        when (changeType) {
            ChangeType.NONE -> {
                return
            }
            ChangeType.STATUS_ONLY -> {
                updateStatusOnly(scheduleItems, newItems)
                invalidate()
            }
            ChangeType.FULL_UPDATE -> {
                scheduleItems.clear()
                scheduleItems.addAll(newItems)
                scrollOffset = 0f
                invalidate()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()

        // 保存画布状态
        canvas.save()

        // 设置裁剪区域
        canvas.clipRect(0f, 0f, width, height)

        // 应用滚动偏移
        canvas.translate(0f, -scrollOffset)

        var yPos = 10f

        for (item in scheduleItems) {
            if (yPos + itemHeight > scrollOffset && yPos < scrollOffset + height) {
                // 绘制行程项
                drawScheduleItem(canvas, item, yPos, width)
            }

            yPos += itemHeight

            // 如果超出画布，停止绘制
            if (yPos > scrollOffset + height + itemHeight) {
                break
            }
        }

        // 恢复画布状态
        canvas.restore()

        // 绘制渐变遮罩（顶部和底部）
        drawGradientMask(canvas, width, height)
    }

    private fun drawScheduleItem(canvas: Canvas, item: ScheduleItem, yPos: Float, width: Float) {
        val textColor = when (item.confStatus) {
            0 -> Color.WHITE
            2 -> Color.RED
            3 -> Color.GREEN
            else -> Color.WHITE
        }

        paint.color = Color.parseColor("#30000000")
        paint.style = Paint.Style.FILL
        canvas.drawRect(5f, yPos, width - 5f, yPos + itemHeight - 5f, paint)
        
        textPaint.textSize = 16f
        textPaint.color = textColor
        
        val dateTimeText = "${timeFormat.format(item.date)}-${dateFormat.format(item.date)}"
        canvas.drawText(dateTimeText, 15f, yPos + 26, textPaint)

        textPaint.textSize = 18f
        textPaint.color = textColor

        canvas.drawText(item.title, width / 2, yPos + 26, textPaint)

        if (item.meetingNumber != null) {
            textPaint.color = textColor
            textPaint.textSize = 14f
            val statusText = when (item.confStatus) {
                0 -> "（未召开）"
                2 -> "（召开中）"
                3 -> "（已结束）"
                else -> ""
            }
            val meetingNumberText = "${item.meetingNumber}$statusText"
            val textWidth = textPaint.measureText(meetingNumberText)
            canvas.drawText(meetingNumberText, width - textWidth - 15f, yPos + 26, textPaint)
            textPaint.textSize = 16f
        }

        if (item.isImportant) {
            paint.color = Color.RED
            paint.style = Paint.Style.FILL
            canvas.drawCircle(width - 15f, yPos + 12, 5f, paint)
        }
    }

    private fun drawGradientMask(canvas: Canvas, width: Float, height: Float) {
        // 顶部渐变
        val topShader = LinearGradient(
            0f, 0f,
            0f, 30f,
            Color.parseColor("#40000000"), Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        paint.shader = topShader
        canvas.drawRect(0f, 0f, width, 30f, paint)

        // 底部渐变
        val bottomShader = LinearGradient(
            0f, height - 30f,
            0f, height,
            Color.TRANSPARENT, Color.parseColor("#40000000"),
            Shader.TileMode.CLAMP
        )
        paint.shader = bottomShader
        canvas.drawRect(0f, height - 30f, width, height, paint)

        paint.shader = null
    }

    private fun getTotalHeight(): Float {
        return scheduleItems.size * itemHeight
    }

    private fun startAutoScroll() {
        handler.post(scrollRunnable)
    }

    fun stopAutoScroll() {
        handler.removeCallbacks(scrollRunnable)
    }

    fun addScheduleItem(item: ScheduleItem) {
        scheduleItems.add(item)
        invalidate()
    }

    fun clearSchedules() {
        scheduleItems.clear()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAutoScroll()
        scheduleManager.removeListener(this)
    }

    override fun onMeetingScheduleChanged() {
        handler.post {
            stopAutoScroll()
            scrollOffset = 0f
            isScrolling = false
            if (queryDate != null) {
                loadSchedulesForDate(queryDate!!)
            } else {
                loadTodaySchedules()
            }
            startAutoScroll()
        }
    }
}