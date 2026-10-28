package com.minrray.voicectl.core.view

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.minrray.voicectl.core.meeting.MeetingScheduleManager
import java.text.SimpleDateFormat
import java.util.*

class CalendarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val redCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val queryDatePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val calendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("yyyy年MM月", Locale.CHINA)
    private val dayFormat = SimpleDateFormat("d", Locale.CHINA)

    private val scheduleManager = MeetingScheduleManager.getInstance()
    
    private var importantDays = mutableListOf<Int>()
    private var queryDate: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private var restoreRunnable: Runnable? = null

    init {
        setupPaints()
        updateImportantDays()
    }

    private fun setupPaints() {
        paint.color = Color.parseColor("#252525")
        paint.style = Paint.Style.FILL

        textPaint.color = Color.WHITE
        textPaint.textSize = 12f
        textPaint.textAlign = Paint.Align.CENTER

        redCirclePaint.color = Color.RED
        redCirclePaint.style = Paint.Style.STROKE
        redCirclePaint.strokeWidth = 2f

        queryDatePaint.color = Color.RED
        queryDatePaint.style = Paint.Style.FILL
    }

    private fun updateImportantDays() {
        importantDays.clear()
        val schedules = scheduleManager.getAllMeetingSchedules()
        val currentCalendar = Calendar.getInstance()
        currentCalendar.time = calendar.time

        schedules.forEach { schedule ->
            val scheduleCalendar = Calendar.getInstance()
            scheduleCalendar.timeInMillis = schedule.meetingTime

            if (scheduleCalendar.get(Calendar.YEAR) == currentCalendar.get(Calendar.YEAR) &&
                scheduleCalendar.get(Calendar.MONTH) == currentCalendar.get(Calendar.MONTH)) {
                val day = scheduleCalendar.get(Calendar.DAY_OF_MONTH)
                if (!importantDays.contains(day)) {
                    importantDays.add(day)
                }
            }
        }

        importantDays.sort()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        val cellSize = width / 7
        val rowHeight = height / 6

        val monthText = dateFormat.format(calendar.time)
        textPaint.textSize = 14f
        textPaint.color = Color.LTGRAY
        canvas.drawText(monthText, width / 2, rowHeight * 0.4f, textPaint)

        val weekDays = arrayOf("日", "一", "二", "三", "四", "五", "六")
        textPaint.textSize = 12f
        textPaint.color = Color.WHITE

        for (i in weekDays.indices) {
            val x = cellSize * i + cellSize / 2
            val y = rowHeight * 0.8f
            canvas.drawText(weekDays[i], x, y, textPaint)
        }

        val firstDayOfMonth = getFirstDayOfMonth()
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)

        val today = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

        for (day in 1..daysInMonth) {
            val position = (firstDayOfMonth + day - 1)
            val row = position / 7
            val col = position % 7

            val x = cellSize * col + cellSize / 2
            val y = rowHeight * (row + 1.5f)

            if (day == queryDate) {
                val queryDateBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                queryDateBgPaint.color = Color.RED
                queryDateBgPaint.style = Paint.Style.FILL
                canvas.drawCircle(x, y - 8, 16f, queryDateBgPaint)
                textPaint.color = Color.WHITE
            } else if (day == today) {
                val todayBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                todayBgPaint.color = Color.parseColor("#2196F3")
                todayBgPaint.style = Paint.Style.FILL
                canvas.drawCircle(x, y - 8, 14f, todayBgPaint)
                textPaint.color = Color.WHITE
            } else {
                textPaint.color = Color.WHITE
            }

            canvas.drawText(day.toString(), x, y, textPaint)

            if (importantDays.contains(day) && day != queryDate) {
                canvas.drawCircle(x, y - 8, 16f, redCirclePaint)
            }
        }
    }

    private fun getFirstDayOfMonth(): Int {
        val tempCalendar = Calendar.getInstance()
        tempCalendar.time = calendar.time
        tempCalendar.set(Calendar.DAY_OF_MONTH, 1)
        return tempCalendar.get(Calendar.DAY_OF_WEEK) - 1
    }

    fun setMonth(year: Int, month: Int) {
        calendar.set(year, month - 1, 1)
        updateImportantDays()
        invalidate()
    }

    fun queryDate(year: Int, month: Int, day: Int) {
        calendar.set(year, month - 1, 1)
        updateImportantDays()
        queryDate = day
        invalidate()

        restoreRunnable?.let { handler.removeCallbacks(it) }
        restoreRunnable = Runnable {
            queryDate = null
            invalidate()
        }
        handler.postDelayed(restoreRunnable!!, 30000)
    }

    fun refresh() {
        updateImportantDays()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        restoreRunnable?.let { handler.removeCallbacks(it) }
    }
}