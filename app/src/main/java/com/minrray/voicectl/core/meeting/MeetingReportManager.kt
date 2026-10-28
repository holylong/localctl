package com.minrray.voicectl.core.meeting

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.minrray.voicectl.core.mcp.BizConfApi
import kotlinx.coroutines.*
import org.json.JSONObject

data class MeetingReport(
    val confNo: String,
    val topic: String,
    val recordStart: String,
    val recordEnd: String,
    val files: List<FileInfo>,
    val meetingTime: Long = 0
)

data class FileInfo(
    val downloadUrl: String,
    val fileSize: String,
    val fileType: String,
    val recordType: String
)

class MeetingReportManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "MeetingReportManager"
        private const val REPORT_PREFS_NAME = "meeting_reports"
        private const val REPORT_KEY_PREFIX = "bizconf_cloud_conf_report_"
        
        private lateinit var instance: MeetingReportManager
        
        fun initialize(context: Context) {
            if (!::instance.isInitialized) {
                instance = MeetingReportManager(context)
            }
        }
        
        fun getInstance(): MeetingReportManager {
            if (!::instance.isInitialized) {
                throw IllegalStateException("MeetingReportManager must be initialized first")
            }
            return instance
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(REPORT_PREFS_NAME, Context.MODE_PRIVATE)
    private val reportTasks = mutableMapOf<Int, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val bizConfApi = BizConfApi(context)
    
    init {
        Log.d(TAG, "MeetingReportManager initialized")
        
        scope.launch {
            try {
                Log.d(TAG, "开始检查已结束会议的报告...")
                
                val scheduleManager = MeetingScheduleManager.getInstance()
                val completedMeetings = scheduleManager.getAllMeetingSchedules()
                    .filter { it.confStatus == 3 && it.cloudConfNumber.isNotEmpty() }
                
                Log.d(TAG, "找到${completedMeetings.size}个已结束的会议")
                
                val existingReports = getAllReportKeys()
                Log.d(TAG, "已有${existingReports.size}个会议报告记录: $existingReports")
                
                completedMeetings.forEach { meeting ->
                    if (!existingReports.contains(meeting.cloudConfNumber)) {
                        Log.d(TAG, "会议${meeting.cloudConfNumber}(${meeting.meetingName})没有报告记录，启动拉取任务")
                        startFetchingReport(meeting.cloudConfNumber)
                    } else {
                        Log.d(TAG, "会议${meeting.cloudConfNumber}(${meeting.meetingName})已有报告记录，跳过")
                    }
                }
                
                Log.d(TAG, "已结束会议报告检查完成")
            } catch (e: Exception) {
                Log.e(TAG, "检查已结束会议报告失败: ${e.message}", e)
            }
        }
    }
    
    fun startFetchingReport(confNo: String) {
        Log.d(TAG, "开始拉取会议报告: confNo=$confNo")
        
        val confId = confNo.toIntOrNull() ?: -1
        val existingTask = reportTasks[confId]
        if (existingTask != null && existingTask.isActive) {
            Log.d(TAG, "会议报告拉取任务已存在: confNo=$confNo")
            return
        }
        
        val task = scope.launch {
            var retryCount = 0
            val maxRetries = 5
            val retryInterval = 3 * 60 * 1000L // 3分钟

            delay(retryInterval)
            
            while (retryCount < maxRetries && isActive) {
                try {
                    Log.d(TAG, "尝试拉取会议报告: confNo=$confNo, 第${retryCount + 1}次")
                    
                    val result = bizConfApi.getContentFileList(confNo)
                    
                    if (result.first) {
                        val response = result.second
                        if (response.status == 100 ) {
                            Log.d(TAG, "成功获取会议报告: confNo=$confNo")
                            if(response.data != null && response.data.datas.isNotEmpty())
                            {
                                val scheduleManager = MeetingScheduleManager.getInstance()
                                val meetingSchedule = scheduleManager.findMeetingScheduleByNumber(confNo)
                                val meetingTime = meetingSchedule?.meetingTime ?: 0
                                
                                val report = MeetingReport(
                                    confNo = response.data.datas[0].confNo,
                                    topic = response.data.datas[0].topic,
                                    recordStart = response.data.datas[0].recordStart,
                                    recordEnd = response.data.datas[0].recordEnd,
                                    files = response.data.datas[0].files.map { file ->
                                        FileInfo(
                                            downloadUrl = file.downloadUrl,
                                            fileSize = file.fileSize,
                                            fileType = file.fileType,
                                            recordType = file.recordType
                                        )
                                    },
                                    meetingTime = meetingTime
                                )
                                saveReport(confNo, report)
                                Log.d(TAG, "会议报告已保存: confNo=$confNo")
                            } else {
                                val scheduleManager = MeetingScheduleManager.getInstance()
                                val meetingSchedule = scheduleManager.findMeetingScheduleByNumber(confNo)
                                val meetingName = meetingSchedule?.meetingName ?: ""
                                val meetingTime = meetingSchedule?.meetingTime ?: 0
                                
                                val emptyReport = MeetingReport(
                                    confNo = confNo,
                                    topic = meetingName,
                                    recordStart = "",
                                    recordEnd = "",
                                    files = emptyList(),
                                    meetingTime = meetingTime
                                )
                                saveReport(confNo, emptyReport)
                                Log.d(TAG, "会议总结内容为空，已保存空报告记录: confNo=$confNo, 主题: $meetingName")
                            }
                            break
                        } else {
                            Log.w(TAG, "获取会议报告失败: confNo=$confNo, status=${response.status}, message=${response.message}")
                        }
                    } else {
                        Log.w(TAG, "获取会议报告失败: confNo=$confNo, error=${result.second.message}")
                    }
                    
                    retryCount++
                    
                    if (retryCount < maxRetries && isActive) {
                        Log.d(TAG, "等待${retryInterval / 1000}秒后重试: confNo=$confNo")
                        delay(retryInterval)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "拉取会议报告异常: confNo=$confNo, error=${e.message}", e)
                    retryCount++
                    
                    if (retryCount < maxRetries && isActive) {
                        Log.d(TAG, "等待${retryInterval / 1000}秒后重试: confNo=$confNo")
                        delay(retryInterval)
                    }
                }
            }
            
            if (retryCount >= maxRetries) {
                Log.e(TAG, "会议报告拉取失败，已达到最大重试次数: confNo=$confNo")
            }
            
            reportTasks.remove(confId)
        }
        
        reportTasks[confId] = task
    }
    
    private fun saveReport(confNo: String, report: MeetingReport) {
        try {
            val key = "$REPORT_KEY_PREFIX$confNo"
            val reportJson = JSONObject().apply {
                put("conf_no", report.confNo)
                put("topic", report.topic)
                put("record_start", report.recordStart)
                put("record_end", report.recordEnd)
                put("meeting_time", report.meetingTime)
                
                val filesArray = org.json.JSONArray()
                report.files.forEach { file ->
                    filesArray.put(JSONObject().apply {
                        put("download_url", file.downloadUrl)
                        put("file_size", file.fileSize)
                        put("file_type", file.fileType)
                        put("record_type", file.recordType)
                    })
                }
                put("files", filesArray)
            }
            
            prefs.edit().putString(key, reportJson.toString()).apply()
            Log.d(TAG, "会议报告已保存到SharedPreferences: key=$key")
        } catch (e: Exception) {
            Log.e(TAG, "保存会议报告失败: confNo=$confNo, error=${e.message}", e)
        }
    }
    
    fun getReport(confNo: String): MeetingReport? {
        return try {
            val key = "$REPORT_KEY_PREFIX$confNo"
            val reportJsonStr = prefs.getString(key, null)
            
            if (reportJsonStr != null) {
                val reportJson = JSONObject(reportJsonStr)
                val filesArray = reportJson.optJSONArray("files") ?: org.json.JSONArray()
                val files = mutableListOf<FileInfo>()
                
                for (i in 0 until filesArray.length()) {
                    val fileJson = filesArray.getJSONObject(i)
                    files.add(
                        FileInfo(
                            downloadUrl = fileJson.optString("download_url", ""),
                            fileSize = fileJson.optString("file_size", ""),
                            fileType = fileJson.optString("file_type", ""),
                            recordType = fileJson.optString("record_type", "")
                        )
                    )
                }
                
                MeetingReport(
                    confNo = reportJson.optString("conf_no", ""),
                    topic = reportJson.optString("topic", ""),
                    recordStart = reportJson.optString("record_start", ""),
                    recordEnd = reportJson.optString("record_end", ""),
                    files = files,
                    meetingTime = reportJson.optLong("meeting_time", 0)
                )
            } else {
                Log.d(TAG, "未找到会议报告: confNo=$confNo")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取会议报告失败: confNo=$confNo, error=${e.message}", e)
            null
        }
    }
    
    fun deleteReport(confNo: String) {
        try {
            val key = "$REPORT_KEY_PREFIX$confNo"
            prefs.edit().remove(key).apply()
            Log.d(TAG, "会议报告已删除: confNo=$confNo")
        } catch (e: Exception) {
            Log.e(TAG, "删除会议报告失败: confNo=$confNo, error=${e.message}", e)
        }
    }
    
    fun cancelFetchingReport(confNo: String) {
        val confId = confNo.toIntOrNull() ?: -1
        reportTasks[confId]?.cancel()
        reportTasks.remove(confId)
        Log.d(TAG, "已取消会议报告拉取任务: confNo=$confNo")
    }
    
    fun clearAllReports() {
        prefs.edit().clear().apply()
        Log.d(TAG, "已清空所有会议报告")
    }
    
    fun getAllReportKeys(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith(REPORT_KEY_PREFIX) }
            .map { it.removePrefix(REPORT_KEY_PREFIX) }
    }
    
    fun getAllReports(limit: Int = 10): List<MeetingReport> {
        return try {
            val reports = mutableListOf<MeetingReport>()
            val keys = getAllReportKeys()
            
            keys.forEach { confNo ->
                getReport(confNo)?.let { report ->
                    reports.add(report)
                }
            }
            
            reports.sortedByDescending { it.meetingTime }.take(limit)
        } catch (e: Exception) {
            Log.e(TAG, "获取所有会议报告失败: error=${e.message}", e)
            emptyList()
        }
    }
    
    fun destroy() {
        reportTasks.values.forEach { it.cancel() }
        reportTasks.clear()
        scope.cancel()
        Log.d(TAG, "MeetingReportManager destroyed")
    }
}