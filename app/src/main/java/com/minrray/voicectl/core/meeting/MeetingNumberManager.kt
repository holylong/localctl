package com.minrray.voicectl.core.meeting

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.*

class MeetingNumberManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "MeetingNumberManager"
        private const val NUMBER_FILE_NAME = "meeting_number.txt"
        private const val MIN_NUMBER = 1
        private const val MAX_NUMBER = 9999
        
        private lateinit var instance: MeetingNumberManager
        
        fun initialize(context: Context) {
            if (!::instance.isInitialized) {
                instance = MeetingNumberManager(context)
            }
        }
        
        fun getInstance(): MeetingNumberManager {
            return instance
        }
    }
    
    private val numberFile: File = File(context.filesDir, NUMBER_FILE_NAME)
    
    init {
        Log.d(TAG, "MeetingNumberManager 初始化")
    }
    
    fun getNextMeetingNumber(): String {
        val lastNumber = loadLastNumber()
        val nextNumber = if (lastNumber >= MAX_NUMBER) {
            MIN_NUMBER
        } else {
            lastNumber + 1
        }
        saveLastNumber(nextNumber)
        return formatNumber(nextNumber)
    }
    
    private fun loadLastNumber(): Int {
        return try {
            if (!numberFile.exists()) {
                return MIN_NUMBER
            }
            val content = FileInputStream(numberFile).bufferedReader().use { it.readText() }
            content.trim().toIntOrNull() ?: MIN_NUMBER
        } catch (e: Exception) {
            Log.e(TAG, "加载会议号码失败: ${e.message}", e)
            MIN_NUMBER
        }
    }
    
    private fun saveLastNumber(number: Int) {
        try {
            FileOutputStream(numberFile).use { it.write(number.toString().toByteArray()) }
            Log.d(TAG, "保存会议号码: $number")
        } catch (e: Exception) {
            Log.e(TAG, "保存会议号码失败: ${e.message}", e)
        }
    }
    
    private fun formatNumber(number: Int): String {
        return String.format("%04d", number)
    }
}