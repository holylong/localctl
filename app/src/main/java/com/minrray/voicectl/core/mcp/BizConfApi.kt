package com.minrray.voicectl.core.mcp

import android.content.Context
import android.util.Log
import com.minrray.voicectl.core.config.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import javax.net.ssl.*

/**
 * 会畅智云 API 客户端，用于通过 HTTP 接口与会畅智云服务器通信
 */
class BizConfApi(private val context: Context) {
    companion object {
        private const val TAG = "BizConfApi"

        // 默认地址和端口
        private const val DEFAULT_BASE_URL = "https://apinext.bizvideo.cn"

        // API 端点
    private const val SCHEDULE_MEETING_ENDPOINT = "/openapi/v1/confReservation"
    private const val CANCEL_MEETING_ENDPOINT = "/openapi/v1/cancelConf"
    private const val UPDATE_MEETING_ENDPOINT = "/openapi/v1/confModify"
    private const val EXIT_MEETING_ENDPOINT = "/openapi/v1/endConf"
    private const val GET_CONTENT_FILE_LIST_ENDPOINT = "/openapi/v1/getContentFileList"
    private const val GET_CONF_LOG_ENDPOINT = "/openapi/v1/getConfLogListByConfId"
}

    private var isAuthenticated = false

    private val configManager: ConfigManager = ConfigManager.getInstance(context)

    private val bizConfConfig: ConfigManager.BizConfConfig by lazy {
        configManager.loadAllConfigs().bizConfConfig
    }

    private val defaultUserId: String get() = bizConfConfig.userId
    private val defaultUserName: String get() = bizConfConfig.userName
    private val defaultSiteSign: String get() = bizConfConfig.siteSign
    private val defaultConfParties: Int get() = bizConfConfig.confParties
    private val apiKey: String get() = bizConfConfig.apiKey

    // OkHttpClient 实例
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            // 添加信任所有证书的配置（仅用于本地测试）
            .sslSocketFactory(createInsecureSSLSocketFactory(), createInsecureTrustManager())
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * 创建不安全的 SSL Socket Factory（仅用于本地测试）
     */
    private fun createInsecureSSLSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(createInsecureTrustManager())
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    /**
     * 创建不安全的 TrustManager（仅用于本地测试）
     */
    private fun createInsecureTrustManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                // 信任所有客户端证书
            }

            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {
                // 信任所有服务器证书
            }

            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
                return arrayOf()
            }
        }
    }

    /**
     * 生成认证令牌
     * token = MD5("UserId|APIKey|timeStamp") MD5加密（大写）获得
     */
    private fun generateToken(timeStamp: Long): String {
        val data = "$defaultUserId|$apiKey|$timeStamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun generateTokenForConfNo(confNo: String, timeStamp: Long): String {
        val data = "$confNo|$apiKey|$timeStamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(data.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }


    /**
     * 预约会议
     * @param meetingNumber 会议号码
     * @param confPassword 会议密码（可选）
     * @param startTime 开始时间 (yyyy-MM-dd HH:mm:ss)
     * @param duration 会议持续时间（分钟）
     * @param confName 会议名称（可选，默认使用会议号）
     */
    suspend fun scheduleMeeting(
        meetingNumber: String,
        confPassword: String? = null,
        startTime: String,
        duration: Int = 60,
        confName: String? = null,
        isInstantMeeting: Int = 0
    ): Pair<Boolean, String> {
        return try {
            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$SCHEDULE_MEETING_ENDPOINT"
                val meetingName = confName ?: "会议_$meetingNumber"
                Log.d(TAG, "预约会议: $url, 会议名称: $meetingName")

                val timeStamp = System.currentTimeMillis()
                val token = generateToken(timeStamp)

                val formBody = FormBody.Builder()
                    .add("userId", defaultUserId)
                    .add("userName", defaultUserName)
                    .add("token", token)
                    .add("timeStamp", timeStamp.toString())
                    .add("siteSign", defaultSiteSign)
                    .add("confName", meetingName)
                    .add("startTime", startTime)
                    .add("duration", duration.toString())
                    .add("confParties", defaultConfParties.toString())
                    .add("isInstantMeeting", isInstantMeeting.toString())
                    .add("optionVideoParticipants","1")
                    .apply {
                        confPassword?.let { add("confPassword", it) }
                    }
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "预约会议响应: $responseBody")
                    Pair(true, responseBody)
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请检查认证令牌"
                        400 -> "请求格式错误"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "预约会议失败: $errorMsg")
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "预约会议异常: ${e.message}")
            Pair(false, "异常: ${e.message}")
        }
    }

    /**
     * 取消会议
     * @param meetingId 会议ID
     */
    suspend fun cancelMeeting(confId: String): Pair<Boolean, String> {
        return try {
            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$CANCEL_MEETING_ENDPOINT"
                Log.d(TAG, "取消会议: $url, 会议ID: $confId")

                val timeStamp = System.currentTimeMillis()
                val token = generateToken(timeStamp)

                val formBody = FormBody.Builder()
                    .add("userId", defaultUserId)
                    .add("token", token)
                    .add("timeStamp", timeStamp.toString())
                    .add("siteSign", defaultSiteSign)
                    .add("confId", confId)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "取消会议成功: $responseBody")
                    Pair(true, "会议已取消")
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请检查认证令牌"
                        400 -> "请求格式错误"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "取消会议失败: $errorMsg")
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "取消会议异常: ${e.message}")
            Pair(false, "异常: ${e.message}")
        }
    }

    /**
     * 更新会议
     * @param meetingId 会议ID
     * @param newMeetingTime 新的会议时间（时间戳）
     */
    suspend fun updateMeeting(
        confId: String,
        confName: String,
        confPassword: String? = null,
        optionJbh: Int = 0,
        optionVideoHost: Int = 0,
        optionVideoParticipants: Int = 0,
        isTuiliu: Int? = null,
        streamDiy: Int = 0,
        streamUrl: String? = null,
        streamKey: String? = null,
        watchUrl: String? = null,
        channelId: Long? = null,
        ignoreSpecial: Int = 0,
        muteWhenJoining: Int = 0
    ): Pair<Boolean, String> {
        return try {
            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$UPDATE_MEETING_ENDPOINT"
                Log.d(TAG, "修改会议: $url, 会议ID: $confId, 会议名称: $confName")

                val timeStamp = System.currentTimeMillis()
                val token = generateToken(timeStamp)

                val formBody = FormBody.Builder()
                    .add("userId", defaultUserId)
                    .add("token", token)
                    .add("timeStamp", timeStamp.toString())
                    .add("siteSign", defaultSiteSign)
                    .add("confId", confId)
                    .add("confName", confName)
                    .add("optionJbh", optionJbh.toString())
                    .add("optionVideoHost", optionVideoHost.toString())
                    .add("optionVideoParticipants", optionVideoParticipants.toString())
                    .add("streamDiy", streamDiy.toString())
                    .add("ignoreSpecial", ignoreSpecial.toString())
                    .add("muteWhenJoining", muteWhenJoining.toString())
                    .apply {
                        confPassword?.let { add("confPassword", it) }
                        isTuiliu?.let { add("isTuiliu", it.toString()) }
                        streamUrl?.let { add("streamUrl", it) }
                        streamKey?.let { add("streamKey", it) }
                        watchUrl?.let { add("watchUrl", it) }
                        channelId?.let { add("channelId", it.toString()) }
                    }
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "修改会议成功: $responseBody")
                    Pair(true, "会议已修改")
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请检查认证令牌"
                        400 -> "请求格式错误"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "修改会议失败: $errorMsg")
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "修改会议异常: ${e.message}")
            Pair(false, "异常: ${e.message}")
        }
    }

    /**
     * 结束会议
     * @param confId 会议ID
     */
    suspend fun exitMeeting(confId: String): Pair<Boolean, String> {
        return try {
            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$EXIT_MEETING_ENDPOINT"
                Log.d(TAG, "结束会议: $url, 会议ID: $confId")

                val timeStamp = System.currentTimeMillis()
                val token = generateToken(timeStamp)

                val formBody = FormBody.Builder()
                    .add("userId", defaultUserId)
                    .add("token", token)
                    .add("timeStamp", timeStamp.toString())
                    .add("siteSign", defaultSiteSign)
                    .add("confId", confId)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "结束会议成功: $responseBody")
                    Pair(true, "会议已结束")
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请检查认证令牌"
                        400 -> "请求格式错误"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "结束会议失败: $errorMsg")
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "结束会议异常: ${e.message}")
            Pair(false, "异常: ${e.message}")
        }
    }

    /**
     * 根据站点标识查询会议室及会议列表
     * @param siteSign 站点标识
     * @param pageNo 页码，默认1
     * @param pageSize 每页条目数，默认10
     */
    suspend fun getRoomListAndConfListBySiteSign(
        siteSign: String = defaultSiteSign,
        pageNo: Int = 1,
        pageSize: Int = 10
    ): Pair<Boolean, SiteMeetingInfoResponse> {
        return try {
            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL/openapi/v1/getRoomListAndConfListBySiteSign"

                val timeStamp = System.currentTimeMillis()
                val token = generateToken(timeStamp)

                val httpUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host("apinext.bizvideo.cn")
                    .addPathSegment("openapi")
                    .addPathSegment("v1")
                    .addPathSegment("getRoomListAndConfListBySiteSign")
                    .addQueryParameter("userId", defaultUserId)
                    .addQueryParameter("token", token)
                    .addQueryParameter("timeStamp", timeStamp.toString())
                    .addQueryParameter("siteSign", siteSign)
                    .addQueryParameter("pageNo", pageNo.toString())
                    .addQueryParameter("pageSize", pageSize.toString())
                    .build()

                val request = Request.Builder()
                    .url(httpUrl)
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""

                    val jsonResponse = JSONObject(responseBody)
                    val status = jsonResponse.optInt("status", -1)
                    val message = jsonResponse.optString("message", "")

                    if (status == 100) {
                        val data = jsonResponse.optJSONArray("data") ?: JSONArray()
                        val roomList = mutableListOf<RoomInfo>()

                        for (i in 0 until data.length()) {
                            val roomObj = data.getJSONObject(i)
                            val roomId = roomObj.optInt("roomId", 0)
                            val roomParties = roomObj.optInt("roomParties", 0)
                            val confListJson = roomObj.optJSONArray("confList") ?: JSONArray()
                            val confList = mutableListOf<ConferenceInfo>()

                            for (j in 0 until confListJson.length()) {
                                val confObj = confListJson.getJSONObject(j)
                                confList.add(
                                    ConferenceInfo(
                                        confId = confObj.optInt("confId", 0),
                                        confName = confObj.optString("confName", ""),
                                        duration = confObj.optInt("duration", 0),
                                        confNumber = confObj.optString("confNumber", ""),
                                        beforeTime = confObj.optInt("beforeTime", 0),
                                        confStatus = confObj.optInt("confStatus", 0),
                                        startTime = confObj.optString("startTime", "")
                                    )
                                )
                            }

                            roomList.add(
                                RoomInfo(
                                    roomId = roomId,
                                    roomParties = roomParties,
                                    confList = confList
                                )
                            )
                        }

                        val siteMeetingInfoResponse = SiteMeetingInfoResponse(
                            status = status,
                            message = message,
                            totalPage = jsonResponse.optInt("totalPage", 0),
                            currentPage = jsonResponse.optString("currentPage", ""),
                            totalItems = jsonResponse.optInt("totalItems", 0),
                            pageSize = jsonResponse.optInt("pageSize", 0),
                            data = roomList
                        )

                        Pair(true, siteMeetingInfoResponse)
                    } else {
                        Log.e(TAG, "查询站点会议信息失败: $message")
                        Pair(false, SiteMeetingInfoResponse(status = status, message = message))
                    }
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请检查认证令牌"
                        400 -> "请求格式错误"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "查询站点会议信息失败: $errorMsg")
                    Pair(false, SiteMeetingInfoResponse(status = response.code, message = errorMsg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查询站点会议信息异常: ${e.message}")
            Pair(false, SiteMeetingInfoResponse(status = -1, message = "异常: ${e.message}"))
        }
    }

    suspend fun getContentFileList(
        confNo: String,
        pageNo: Int = 1,
        pageSize: Int = 10,
        startTime: String? = null,
        endTime: String? = null
    ): Pair<Boolean, ContentFileListResponse> {
        return try {
            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$GET_CONTENT_FILE_LIST_ENDPOINT"
                Log.d(TAG, "获取会议报告: $url, 会议号: $confNo")

                val timeStamp = System.currentTimeMillis()
                val token = generateTokenForConfNo(confNo, timeStamp)

                val formBody = FormBody.Builder()
                    .add("confNo", confNo)
                    .add("token", token)
                    .add("timeStamp", timeStamp.toString())
                    .add("siteSign", defaultSiteSign)
                    .add("pageNo", pageNo.toString())
                    .add("pageSize", pageSize.toString())
                    .apply {
                        startTime?.let { add("startTime", it) }
                        endTime?.let { add("endTime", it) }
                    }
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(formBody)
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "获取会议报告响应: $responseBody")

                    val jsonResponse = JSONObject(responseBody)
                    val status = jsonResponse.optInt("status", -1)
                    val message = jsonResponse.optString("message", "")

                    if (status == 100) {
                        val dataObj = jsonResponse.optJSONObject("data")
                        val contentFileListData = if (dataObj != null) {
                            val datasJson = dataObj.optJSONArray("datas") ?: JSONArray()
                            val datas = mutableListOf<ContentFileData>()

                            for (i in 0 until datasJson.length()) {
                                val dataItemObj = datasJson.getJSONObject(i)
                                val filesJson = dataItemObj.optJSONArray("files") ?: JSONArray()
                                val files = mutableListOf<ContentFile>()

                                for (j in 0 until filesJson.length()) {
                                    val fileObj = filesJson.getJSONObject(j)
                                    files.add(
                                        ContentFile(
                                            downloadUrl = fileObj.optString("downloadUrl", ""),
                                            fileSize = fileObj.optString("fileSize", ""),
                                            fileType = fileObj.optString("fileType", ""),
                                            recordType = fileObj.optString("recordType", "")
                                        )
                                    )
                                }

                                datas.add(
                                    ContentFileData(
                                        uuid = dataItemObj.optString("uuid", ""),
                                        confNo = dataItemObj.optString("confNo", ""),
                                        topic = dataItemObj.optString("topic", ""),
                                        recordStart = dataItemObj.optString("recordStart", ""),
                                        recordEnd = dataItemObj.optString("recordEnd", ""),
                                        files = files
                                    )
                                )
                            }

                            ContentFileListData(
                                beginRow = dataObj.optInt("beginRow", 0),
                                datas = datas,
                                pageCount = dataObj.optInt("pageCount", 0),
                                pageNo = dataObj.optString("pageNo", "1"),
                                pageSize = dataObj.optString("pageSize", "10"),
                                rowsCount = dataObj.optInt("rowsCount", 0)
                            )
                        } else {
                            null
                        }

                        val contentFileListResponse = ContentFileListResponse(
                            status = status,
                            message = message,
                            data = contentFileListData
                        )

                        Pair(true, contentFileListResponse)
                    } else {
                        Log.e(TAG, "获取会议报告失败: $message")
                        Pair(false, ContentFileListResponse(status = status, message = message))
                    }
                } else {
                    Log.e(TAG, "获取会议报告失败: HTTP ${response.code}")
                    Pair(false, ContentFileListResponse(status = -1, message = "HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取会议报告异常: ${e.message}")
            Pair(false, ContentFileListResponse(status = -1, message = "异常: ${e.message}"))
        }
    }

    suspend fun getConfLogListByConfId(
        confId: Int,
        pageNo: Int = 1,
        pageSize: Int = 10
    ): Pair<Boolean, MeetingLogResponse> {
        return try {
            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$GET_CONF_LOG_ENDPOINT"
                Log.d(TAG, "获取会议报告: $url, 会议ID: $confId")

                val timeStamp = System.currentTimeMillis()
                val token = generateToken(timeStamp)

                val httpUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host("apinext.bizvideo.cn")
                    .addPathSegment("openapi")
                    .addPathSegment("v1")
                    .addPathSegment("getConfLogListByConfId")
                    .addQueryParameter("userId", defaultUserId)
                    .addQueryParameter("token", token)
                    .addQueryParameter("timeStamp", timeStamp.toString())
                    .addQueryParameter("siteSign", defaultSiteSign)
                    .addQueryParameter("confId", confId.toString())
                    .addQueryParameter("pageNo", pageNo.toString())
                    .addQueryParameter("pageSize", pageSize.toString())
                    .build()

                val request = Request.Builder()
                    .url(httpUrl)
                    .get()
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "获取会议报告响应: $responseBody")

                    val jsonResponse = JSONObject(responseBody)
                    val status = jsonResponse.optInt("status", -1)
                    val message = jsonResponse.optString("message", "")

                    if (status == 100) {
                        val dataObj = jsonResponse.optJSONObject("data")
                        val meetingLogData = if (dataObj != null) {
                            val logsJson = dataObj.optJSONArray("logs") ?: JSONArray()
                            val logs = mutableListOf<MeetingLog>()

                            for (i in 0 until logsJson.length()) {
                                val logObj = logsJson.getJSONObject(i)
                                val userPropertiesObj = logObj.optJSONObject("userProperties")
                                val userProperties = UserProperties(
                                    virtualUserId = userPropertiesObj?.optInt("virtualUserId"),
                                    connectorType = userPropertiesObj?.optInt("connectorType", 0) ?: 0,
                                    ip = userPropertiesObj?.optString("ip"),
                                    virtualLoginName = userPropertiesObj?.optString("virtualLoginName")
                                )

                                logs.add(
                                    MeetingLog(
                                        cuid = logObj.optString("cuid", null),
                                        leaveTime = logObj.optString("leaveTime", ""),
                                        joinTime = logObj.optString("joinTime", ""),
                                        userProperties = userProperties,
                                        participantName = logObj.optString("participantName", ""),
                                        device = logObj.optString("device", ""),
                                        email = logObj.optString("email", "")
                                    )
                                )
                            }

                            MeetingLogData(
                                startTime = dataObj.optString("startTime", ""),
                                duration = dataObj.optInt("duration", 0),
                                confId = dataObj.optInt("confId", 0),
                                logs = logs
                            )
                        } else {
                            null
                        }

                        val meetingLogResponse = MeetingLogResponse(
                            status = status,
                            message = message,
                            totalPage = jsonResponse.optInt("totalPage", 0),
                            currentPage = jsonResponse.optInt("currentPage", 0),
                            totalItems = jsonResponse.optInt("totalItems", 0),
                            pageSize = jsonResponse.optInt("pageSize", 0),
                            data = meetingLogData
                        )

                        Pair(true, meetingLogResponse)
                    } else {
                        Log.e(TAG, "获取会议报告失败: $message")
                        Pair(false, MeetingLogResponse(status = status, message = message))
                    }
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请检查认证令牌"
                        400 -> "请求格式错误"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "获取会议报告失败: $errorMsg")
                    Pair(false, MeetingLogResponse(status = response.code, message = errorMsg))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取会议报告异常: ${e.message}")
            Pair(false, MeetingLogResponse(status = -1, message = "异常: ${e.message}"))
        }
    }
}

data class ConferenceInfo(
    val confId: Int,
    val confName: String,
    val duration: Int,
    val confNumber: String,
    val beforeTime: Int,
    val confStatus: Int,
    val startTime: String
)

data class RoomInfo(
    val roomId: Int,
    val roomParties: Int,
    val confList: List<ConferenceInfo>
)

data class SiteMeetingInfoResponse(
    val status: Int,
    val message: String,
    val totalPage: Int = 0,
    val currentPage: String = "",
    val totalItems: Int = 0,
    val pageSize: Int = 0,
    val data: List<RoomInfo> = emptyList()
)

data class ContentFile(
    val downloadUrl: String,
    val fileSize: String,
    val fileType: String,
    val recordType: String
)

data class ContentFileData(
    val uuid: String,
    val confNo: String,
    val topic: String,
    val recordStart: String,
    val recordEnd: String,
    val files: List<ContentFile>
)

data class ContentFileListResponse(
    val status: Int,
    val message: String,
    val data: ContentFileListData? = null
)

data class ContentFileListData(
    val beginRow: Int,
    val datas: List<ContentFileData>,
    val pageCount: Int,
    val pageNo: String,
    val pageSize: String,
    val rowsCount: Int
)

data class MeetingLog(
    val cuid: String? = null,
    val leaveTime: String,
    val joinTime: String,
    val userProperties: UserProperties,
    val participantName: String,
    val device: String,
    val email: String
)

data class UserProperties(
    val virtualUserId: Int? = null,
    val connectorType: Int,
    val ip: String? = null,
    val virtualLoginName: String? = null
)

data class MeetingLogData(
    val startTime: String,
    val duration: Int,
    val confId: Int,
    val logs: List<MeetingLog>
)

data class MeetingLogResponse(
    val status: Int,
    val message: String,
    val totalPage: Int = 0,
    val currentPage: Int = 0,
    val totalItems: Int = 0,
    val pageSize: Int = 0,
    val data: MeetingLogData? = null
)
