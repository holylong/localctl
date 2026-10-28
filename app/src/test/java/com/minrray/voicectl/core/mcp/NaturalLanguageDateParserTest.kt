package com.minrray.voicectl.core.mcp

import org.junit.Test
import org.junit.Assert.*
import java.text.SimpleDateFormat
import java.util.*

class NaturalLanguageDateParserTest {
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    
    @Test
    fun testParseDateWithFullYear() {
        val testCases = listOf(
            "2025-12-20" to "2025-12-20",
            "2024-01-01" to "2024-01-01",
            "2023-12-31" to "2023-12-31"
        )
        
        testCases.forEach { (input, expected) ->
            val result = parseNaturalLanguageDate(input)
            assertNotNull("Failed to parse: $input", result)
            val resultStr = dateFormat.format(result!!)
            assertEquals("Input: $input", expected, resultStr)
        }
    }
    
    @Test
    fun testParseDateWithMonthAndDay() {
        val testCases = listOf(
            "7月5号",
            "7月5日",
            "7月 5号",
            "7月 5日"
        )
        
        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH)
        val currentDay = now.get(Calendar.DAY_OF_MONTH)
        
        testCases.forEach { input ->
            val result = parseNaturalLanguageDate(input)
            assertNotNull("Failed to parse: $input", result)
            
            val resultCalendar = Calendar.getInstance()
            resultCalendar.time = result!!
            val resultMonth = resultCalendar.get(Calendar.MONTH)
            val resultDay = resultCalendar.get(Calendar.DAY_OF_MONTH)
            
            assertEquals("Input: $input - Month mismatch", 6, resultMonth) // 7月 = 6 (0-indexed)
            assertEquals("Input: $input - Day mismatch", 5, resultDay)
            
            val resultYear = resultCalendar.get(Calendar.YEAR)
            if (6 < currentMonth || (6 == currentMonth && 5 < currentDay)) {
                assertEquals("Input: $input - Year should be next year", currentYear + 1, resultYear)
            } else {
                assertEquals("Input: $input - Year should be current year", currentYear, resultYear)
            }
        }
    }
    
    @Test
    fun testParseDateWithChineseMonthAndDay() {
        val testCases = listOf(
            "七月五号",
            "七月 5号",
            "七月五日",
            "七月 5日",
            "十二月三十一号",
            "一月一日"
        )
        
        val now = Calendar.getInstance()
        val currentYear = now.get(Calendar.YEAR)
        val currentMonth = now.get(Calendar.MONTH)
        val currentDay = now.get(Calendar.DAY_OF_MONTH)
        
        testCases.forEach { input ->
            val result = parseNaturalLanguageDate(input)
            assertNotNull("Failed to parse: $input", result)
            
            val resultCalendar = Calendar.getInstance()
            resultCalendar.time = result!!
            val resultYear = resultCalendar.get(Calendar.YEAR)
            
            when {
                input.contains("七月") -> {
                    assertEquals("Input: $input - Month mismatch", 6, resultCalendar.get(Calendar.MONTH))
                    assertEquals("Input: $input - Day mismatch", 5, resultCalendar.get(Calendar.DAY_OF_MONTH))
                    if (6 < currentMonth || (6 == currentMonth && 5 < currentDay)) {
                        assertEquals("Input: $input - Year should be next year", currentYear + 1, resultYear)
                    } else {
                        assertEquals("Input: $input - Year should be current year", currentYear, resultYear)
                    }
                }
                input.contains("十二月") -> {
                    assertEquals("Input: $input - Month mismatch", 11, resultCalendar.get(Calendar.MONTH))
                    assertEquals("Input: $input - Day mismatch", 31, resultCalendar.get(Calendar.DAY_OF_MONTH))
                }
                input.contains("一月") -> {
                    assertEquals("Input: $input - Month mismatch", 0, resultCalendar.get(Calendar.MONTH))
                    assertEquals("Input: $input - Day mismatch", 1, resultCalendar.get(Calendar.DAY_OF_MONTH))
                }
            }
        }
    }
    
    @Test
    fun testParseRelativeDates() {
        val now = Calendar.getInstance()
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        
        val yesterday = Calendar.getInstance().apply {
            time = today.time
            add(Calendar.DAY_OF_YEAR, -1)
        }
        
        val tomorrow = Calendar.getInstance().apply {
            time = today.time
            add(Calendar.DAY_OF_YEAR, 1)
        }
        
        val dayAfterTomorrow = Calendar.getInstance().apply {
            time = today.time
            add(Calendar.DAY_OF_YEAR, 2)
        }
        
        val testCases = listOf(
            "昨天" to yesterday,
            "今天" to today,
            "明天" to tomorrow,
            "后天" to dayAfterTomorrow
        )
        
        testCases.forEach { (input, expected) ->
            val result = parseNaturalLanguageDate(input)
            assertNotNull("Failed to parse: $input", result)
            val resultStr = dateFormat.format(result!!)
            val expectedStr = dateFormat.format(expected.time)
            assertEquals("Input: $input", expectedStr, resultStr)
        }
    }
    
    @Test
    fun testParseWeekdays() {
        val testCases = listOf(
            "周一", "星期一",
            "周二", "星期二",
            "周三", "星期三",
            "周四", "星期四",
            "周五", "星期五",
            "周六", "星期六",
            "周日", "星期日"
        )
        
        testCases.forEach { input ->
            val result = parseNaturalLanguageDate(input)
            assertNotNull("Failed to parse: $input", result)
            
            val resultCalendar = Calendar.getInstance()
            resultCalendar.time = result
            
            val weekdayMap = mapOf(
                "周一" to Calendar.MONDAY, "星期一" to Calendar.MONDAY,
                "周二" to Calendar.TUESDAY, "星期二" to Calendar.TUESDAY,
                "周三" to Calendar.WEDNESDAY, "星期三" to Calendar.WEDNESDAY,
                "周四" to Calendar.THURSDAY, "星期四" to Calendar.THURSDAY,
                "周五" to Calendar.FRIDAY, "星期五" to Calendar.FRIDAY,
                "周六" to Calendar.SATURDAY, "星期六" to Calendar.SATURDAY,
                "周日" to Calendar.SUNDAY, "星期日" to Calendar.SUNDAY
            )
            
            val expectedWeekday = weekdayMap[input]
            val actualWeekday = resultCalendar.get(Calendar.DAY_OF_WEEK)
            assertEquals("Input: $input", expectedWeekday, actualWeekday)
        }
    }
    
    @Test
    fun testParseLastWeekWeekdays() {
        val testCases = listOf(
            "上周一", "上周星期一",
            "上周二", "上周星期二",
            "上周三", "上周星期三",
            "上周四", "上周星期四",
            "上周五", "上周星期五",
            "上周六", "上周星期六",
            "上周日", "上周星期日"
        )
        
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)
        val nowInMillis = now.timeInMillis
        val oneDayInMillis = 24 * 60 * 60 * 1000L
        
        testCases.forEach { input ->
            val result = parseNaturalLanguageDate(input)
            assertNotNull("Failed to parse: $input", result)
            
            val resultCalendar = Calendar.getInstance()
            resultCalendar.time = result
            
            val weekdayMap = mapOf(
                "上周一" to Calendar.MONDAY, "上周星期一" to Calendar.MONDAY,
                "上周二" to Calendar.TUESDAY, "上周星期二" to Calendar.TUESDAY,
                "上周三" to Calendar.WEDNESDAY, "上周星期三" to Calendar.WEDNESDAY,
                "上周四" to Calendar.THURSDAY, "上周星期四" to Calendar.THURSDAY,
                "上周五" to Calendar.FRIDAY, "上周星期五" to Calendar.FRIDAY,
                "上周六" to Calendar.SATURDAY, "上周星期六" to Calendar.SATURDAY,
                "上周日" to Calendar.SUNDAY, "上周星期日" to Calendar.SUNDAY
            )
            
            val expectedWeekday = weekdayMap[input]
            val actualWeekday = resultCalendar.get(Calendar.DAY_OF_WEEK)
            val resultInMillis = resultCalendar.timeInMillis
            val daysDiff = (nowInMillis - resultInMillis) / oneDayInMillis
            
            assertEquals("Input: $input - Weekday mismatch", expectedWeekday, actualWeekday)
            assertTrue("Input: $input - Should be last week (daysDiff=$daysDiff)", daysDiff >= 1 && daysDiff <= 13)
        }
    }
    
    @Test
    fun testParseNextWeekWeekdays() {
        val testCases = listOf(
            "下周一", "下周星期一",
            "下周二", "下周星期二",
            "下周三", "下周星期三",
            "下周四", "下周星期四",
            "下周五", "下周星期五",
            "下周六", "下周星期六",
            "下周日", "下周星期日"
        )
        
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)
        val nowInMillis = now.timeInMillis
        val oneDayInMillis = 24 * 60 * 60 * 1000L
        
        testCases.forEach { input ->
            val result = parseNaturalLanguageDate(input)
            assertNotNull("Failed to parse: $input", result)
            
            val resultCalendar = Calendar.getInstance()
            resultCalendar.time = result
            
            val weekdayMap = mapOf(
                "下周一" to Calendar.MONDAY, "下周星期一" to Calendar.MONDAY,
                "下周二" to Calendar.TUESDAY, "下周星期二" to Calendar.TUESDAY,
                "下周三" to Calendar.WEDNESDAY, "下周星期三" to Calendar.WEDNESDAY,
                "下周四" to Calendar.THURSDAY, "下周星期四" to Calendar.THURSDAY,
                "下周五" to Calendar.FRIDAY, "下周星期五" to Calendar.FRIDAY,
                "下周六" to Calendar.SATURDAY, "下周星期六" to Calendar.SATURDAY,
                "下周日" to Calendar.SUNDAY, "下周星期日" to Calendar.SUNDAY
            )
            
            val expectedWeekday = weekdayMap[input]
            val actualWeekday = resultCalendar.get(Calendar.DAY_OF_WEEK)
            val resultInMillis = resultCalendar.timeInMillis
            val daysDiff = (resultInMillis - nowInMillis) / oneDayInMillis
            
            assertEquals("Input: $input - Weekday mismatch", expectedWeekday, actualWeekday)
            assertTrue("Input: $input - Should be next week (daysDiff=$daysDiff)", daysDiff >= 1 && daysDiff <= 13)
        }
    }
    
    @Test
    fun testParseThisMonthDay() {
        val now = Calendar.getInstance()
        val currentMonth = now.get(Calendar.MONTH)
        
        val testCases = listOf(
            "这个月5号",
            "这个月 5号",
            "这个月5日",
            "这个月 5日"
        )
        
        testCases.forEach { input ->
            val result = parseNaturalLanguageDate(input)
            assertNotNull("Failed to parse: $input", result)
            
            val resultCalendar = Calendar.getInstance()
            resultCalendar.time = result
            
            assertEquals("Input: $input - Month mismatch", currentMonth, resultCalendar.get(Calendar.MONTH))
            assertEquals("Input: $input - Day mismatch", 5, resultCalendar.get(Calendar.DAY_OF_MONTH))
        }
    }
    
    @Test
    fun testParseNextMonthDay() {
        val now = Calendar.getInstance()
        val currentMonth = now.get(Calendar.MONTH)
        
        val testCases = listOf(
            "下个月5号",
            "下个月 5号",
            "下个月5日",
            "下个月 5日"
        )
        
        testCases.forEach { input ->
            val result = parseNaturalLanguageDate(input)
            assertNotNull("Failed to parse: $input", result)
            
            val resultCalendar = Calendar.getInstance()
            resultCalendar.time = result
            
            val expectedMonth = if (currentMonth == 11) 0 else currentMonth + 1
            assertEquals("Input: $input - Month mismatch", expectedMonth, resultCalendar.get(Calendar.MONTH))
            assertEquals("Input: $input - Day mismatch", 5, resultCalendar.get(Calendar.DAY_OF_MONTH))
        }
    }
    
    @Test
    fun testParseInvalidDates() {
        val invalidInputs = listOf(
            "invalid date",
            "13月5号",
            "2月30号",
            "invalid"
        )
        
        invalidInputs.forEach { input ->
            val result = parseNaturalLanguageDate(input)
            assertNull("Should return null for invalid input: $input", result)
        }
    }
    
    private fun parseNaturalLanguageDate(dateStr: String): Date? {
        val now = Calendar.getInstance()
        now.set(Calendar.HOUR_OF_DAY, 0)
        now.set(Calendar.MINUTE, 0)
        now.set(Calendar.SECOND, 0)
        now.set(Calendar.MILLISECOND, 0)
        val targetDate = Calendar.getInstance()
        
        try {
            val trimmedStr = dateStr.trim()
            
            val dateRegex = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
            val dateMatch = dateRegex.find(trimmedStr)
            if (dateMatch != null) {
                val year = dateMatch.groupValues[1].toInt()
                val month = dateMatch.groupValues[2].toInt() - 1
                val day = dateMatch.groupValues[3].toInt()
                
                if (!isValidDate(year, month, day)) {
                    return null
                }
                
                targetDate.set(year, month, day, 0, 0, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            val monthDayRegex = Regex("(\\d+)\\s*[月]\\s*(\\d+)\\s*[号日]")
            val monthDayMatch = monthDayRegex.find(trimmedStr)
            if (monthDayMatch != null) {
                val month = monthDayMatch.groupValues[1].toInt() - 1
                val day = monthDayMatch.groupValues[2].toInt()
                
                if (month < 0 || month > 11) {
                    return null
                }
                
                val currentYear = now.get(Calendar.YEAR)
                val currentMonth = now.get(Calendar.MONTH)
                val currentDay = now.get(Calendar.DAY_OF_MONTH)
                
                var targetYear = currentYear
                
                if (month < currentMonth || (month == currentMonth && day < currentDay)) {
                    targetYear = currentYear + 1
                }
                
                if (!isValidDate(targetYear, month, day)) {
                    return null
                }
                
                targetDate.set(targetYear, month, day, 0, 0, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            val chineseMonthDayRegex = Regex("(一|二|三|四|五|六|七|八|九|十|十一|十二)\\s*月\\s*(一|二|三|四|五|六|七|八|九|十|十一|十二|十三|十四|十五|十六|十七|十八|十九|二十|二十一|二十二|二十三|二十四|二十五|二十六|二十七|二十八|二十九|三十|三十一|\\d+)\\s*[号日]")
            val chineseMonthDayMatch = chineseMonthDayRegex.find(trimmedStr)
            if (chineseMonthDayMatch != null) {
                val monthMap = mapOf(
                    "一" to 1, "二" to 2, "三" to 3, "四" to 4,
                    "五" to 5, "六" to 6, "七" to 7, "八" to 8,
                    "九" to 9, "十" to 10, "十一" to 11, "十二" to 12
                )
                val dayMap = mapOf(
                    "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
                    "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
                    "十一" to 11, "十二" to 12, "十三" to 13, "十四" to 14, "十五" to 15,
                    "十六" to 16, "十七" to 17, "十八" to 18, "十九" to 19, "二十" to 20,
                    "二十一" to 21, "二十二" to 22, "二十三" to 23, "二十四" to 24,
                    "二十五" to 25, "二十六" to 26, "二十七" to 27, "二十八" to 28,
                    "二十九" to 29, "三十" to 30, "三十一" to 31
                )
                val month = (monthMap[chineseMonthDayMatch.groupValues[1]] ?: 1) - 1
                val dayStr = chineseMonthDayMatch.groupValues[2]
                val day = if (dayStr.matches(Regex("\\d+"))) {
                    dayStr.toInt()
                } else {
                    dayMap[dayStr] ?: 1
                }
                
                val currentYear = now.get(Calendar.YEAR)
                val currentMonth = now.get(Calendar.MONTH)
                val currentDay = now.get(Calendar.DAY_OF_MONTH)
                
                var targetYear = currentYear
                
                if (month < currentMonth || (month == currentMonth && day < currentDay)) {
                    targetYear = currentYear + 1
                }
                
                if (!isValidDate(targetYear, month, day)) {
                    return null
                }
                
                targetDate.set(targetYear, month, day, 0, 0, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            if (trimmedStr.contains("昨天")) {
                targetDate.time = now.time
                targetDate.add(Calendar.DAY_OF_YEAR, -1)
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            } else if (trimmedStr.contains("今天")) {
                targetDate.time = now.time
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            } else if (trimmedStr.contains("明天")) {
                targetDate.time = now.time
                targetDate.add(Calendar.DAY_OF_YEAR, 1)
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            } else if (trimmedStr.contains("后天")) {
                targetDate.time = now.time
                targetDate.add(Calendar.DAY_OF_YEAR, 2)
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
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
                val currentWeekday = now.get(Calendar.DAY_OF_WEEK)
                val normalizedCurrentWeekday = if (currentWeekday == Calendar.SUNDAY) 0 else currentWeekday - 1
                
                val daysToAdd = targetWeekday - normalizedCurrentWeekday - 7
                
                println("daysToAdd: $daysToAdd")
                
                targetDate.time = now.time
                targetDate.add(Calendar.DAY_OF_YEAR, daysToAdd)
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
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
                val currentWeekday = now.get(Calendar.DAY_OF_WEEK)
                val normalizedCurrentWeekday = if (currentWeekday == Calendar.SUNDAY) 0 else currentWeekday - 1
                
                val daysToAdd = targetWeekday - normalizedCurrentWeekday + 7
                
                targetDate.time = now.time
                targetDate.add(Calendar.DAY_OF_YEAR, daysToAdd)
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
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
                val currentWeekday = now.get(Calendar.DAY_OF_WEEK)
                val normalizedCurrentWeekday = if (currentWeekday == Calendar.SUNDAY) 0 else currentWeekday - 1
                
                val daysToAdd = if (targetWeekday > normalizedCurrentWeekday) {
                    targetWeekday - normalizedCurrentWeekday
                } else if (targetWeekday < normalizedCurrentWeekday) {
                    targetWeekday + 7 - normalizedCurrentWeekday
                } else {
                    0
                }
                
                targetDate.time = now.time
                targetDate.add(Calendar.DAY_OF_YEAR, daysToAdd)
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            val thisMonthRegex = Regex("这个月\\s*(\\d+)\\s*[号日]")
            val thisMonthMatch = thisMonthRegex.find(trimmedStr)
            if (thisMonthMatch != null) {
                val day = thisMonthMatch.groupValues[1].toInt()
                targetDate.set(Calendar.DAY_OF_MONTH, day)
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            val nextMonthRegex = Regex("下个月\\s*(\\d+)\\s*[号日]")
            val nextMonthMatch = nextMonthRegex.find(trimmedStr)
            if (nextMonthMatch != null) {
                val day = nextMonthMatch.groupValues[1].toInt()
                targetDate.add(Calendar.MONTH, 1)
                targetDate.set(Calendar.DAY_OF_MONTH, day)
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
        } catch (e: Exception) {
            return null
        }
        
        return null
    }
    
    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (month < 0 || month > 11) {
            return false
        }
        
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.YEAR, year)
        calendar.set(Calendar.MONTH, month)
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        
        val maxDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        
        return day >= 1 && day <= maxDay
    }
}
