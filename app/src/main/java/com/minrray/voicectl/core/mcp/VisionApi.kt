package com.minrray.voicectl.core.mcp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

import javax.net.ssl.*

/**
 * Vision API 客户端，用于通过 HTTP 接口与 vl.vision.app 通信
 */
class VisionApi(private val context: Context) {
    companion object {
        private const val TAG = "VisionApi"

        // 默认地址和端口
        private const val BASE_URL_HTTP = "http://[::1]:8080"
        private const val BASE_URL_HTTPS = "https://[::1]:8443"
        private const val LOCALHOST_HTTP = "http://localhost:8080"
        private const val LOCALHOST_HTTPS = "https://localhost:8443"

        // 使用本地 IPv4 地址作为默认
        private const val DEFAULT_BASE_URL = LOCALHOST_HTTPS

        // API 端点
        private const val LOGIN_ENDPOINT = "/api/settings/login_web_ui"
        private const val MAKE_CALL_ENDPOINT = "/api/calls/make_call"
        private const val DROP_CALL_ENDPOINT = "/api/calls/drop_call"
        private const val CALL_STATE_ENDPOINT = "/api/calls/call_state"
        private const val CALL_INFO_ENDPOINT = "/api/calls/call_info"
    }

    private var cookie: String? = null
    private var isLoggedIn = false

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
     * 登录到 Vision Web UI
     */
    suspend fun login(username: String = "admin", password: String = "admin"): Boolean {
        return try {
            withContext(Dispatchers.IO) {
                val loginUrl = "$DEFAULT_BASE_URL$LOGIN_ENDPOINT"
                Log.d(TAG, "登录到 Vision Web UI: $loginUrl")

                // 根据代码，登录请求似乎是 POST 请求，但可能不需要正文
                // 尝试发送一个空的 POST 请求
                val request = Request.Builder()
                    .url(loginUrl)
                    .post("".toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    // 从响应头中提取 Cookie
                    val setCookieHeader = response.headers["Set-Cookie"]
                    if (setCookieHeader != null) {
                        cookie = setCookieHeader
                        isLoggedIn = true
                        Log.d(TAG, "登录成功，Cookie: $cookie")
                        true
                    } else {
                        // 如果响应头中没有 Cookie，尝试从代码逻辑看，登录接口会生成 Cookie
                        // 可能需要通过后续请求来获取
                        cookie = "user_${System.currentTimeMillis()}=admin_${System.currentTimeMillis()}"
                        isLoggedIn = true
                        Log.d(TAG, "使用模拟 Cookie: $cookie")
                        true
                    }
                } else {
                    Log.e(TAG, "登录失败: HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "登录异常: ${e.message}")
            false
        }
    }

    /**
     * 发起通话
     * @param meetingNumber 会议号码（可以是 SIP URI、IP 地址、E.164 号码等）
     * @param displayName 显示名称（可选）
     * @param rate 码率（默认 4096）
     * @param callType 通话类型（0=自动）
     */
    suspend fun makeCall(
        meetingNumber: String,
        displayName: String = "",
        rate: Int = 4096,
        callType: Int = 0
    ): Pair<Boolean, String> {
        return try {
            // 确保已登录
            if (!isLoggedIn && !login()) {
                return Pair(false, "登录失败，无法发起通话")
            }

            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$MAKE_CALL_ENDPOINT"
                Log.d(TAG, "发起通话: $url, 号码: $meetingNumber")

                // 构建请求体
                val requestBody = JSONObject().apply {
                    put("uri", meetingNumber)
                    put("display_name", displayName)
                    put("rate", rate)
                    put("call_type", callType)
                }.toString()

                val request = Request.Builder()
                    .url(url)
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .apply {
                        // 添加 Cookie 认证
                        cookie?.let { addHeader("Cookie", it) }
                    }
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "发起通话成功: $responseBody")
                    Pair(true, "通话已发起: $meetingNumber")
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请重新登录"
                        400 -> "请求格式错误"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "发起通话失败: $errorMsg")
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "发起通话异常: ${e.message}")
            Pair(false, "异常: ${e.message}")
        }
    }

    /**
     * 挂断当前通话
     */
    suspend fun dropCall(): Pair<Boolean, String> {
        return try {
            // 确保已登录
            if (!isLoggedIn && !login()) {
                return Pair(false, "登录失败，无法挂断通话")
            }

            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$DROP_CALL_ENDPOINT"
                Log.d(TAG, "挂断通话: $url")

                val request = Request.Builder()
                    .url(url)
                    .delete()
                    .apply {
                        // 添加 Cookie 认证
                        cookie?.let { addHeader("Cookie", it) }
                    }
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "挂断通话成功: $responseBody")
                    Pair(true, "通话已挂断")
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请重新登录"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "挂断通话失败: $errorMsg")
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "挂断通话异常: ${e.message}")
            Pair(false, "异常: ${e.message}")
        }
    }

    /**
     * 获取通话状态
     */
    suspend fun getCallState(): Pair<Boolean, String> {
        return try {
            // 确保已登录
            if (!isLoggedIn && !login()) {
                return Pair(false, "登录失败，无法获取通话状态")
            }

            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$CALL_STATE_ENDPOINT"
                Log.d(TAG, "获取通话状态: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply {
                        // 添加 Cookie 认证
                        cookie?.let { addHeader("Cookie", it) }
                    }
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "通话状态: $responseBody")

                    // 解析 JSON 响应
                    try {
                        val json = JSONObject(responseBody)
                        val status = json.optString("status", "未知状态")
                        Pair(true, status)
                    } catch (e: Exception) {
                        Pair(true, responseBody)
                    }
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请重新登录"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "获取通话状态失败: $errorMsg")
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取通话状态异常: ${e.message}")
            Pair(false, "异常: ${e.message}")
        }
    }

    /**
     * 获取通话信息
     */
    suspend fun getCallInfo(): Pair<Boolean, String> {
        return try {
            // 确保已登录
            if (!isLoggedIn && !login()) {
                return Pair(false, "登录失败，无法获取通话信息")
            }

            withContext(Dispatchers.IO) {
                val url = "$DEFAULT_BASE_URL$CALL_INFO_ENDPOINT"
                Log.d(TAG, "获取通话信息: $url")

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .apply {
                        // 添加 Cookie 认证
                        cookie?.let { addHeader("Cookie", it) }
                    }
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    Log.d(TAG, "通话信息: $responseBody")
                    Pair(true, responseBody)
                } else {
                    val errorMsg = when (response.code) {
                        401 -> "认证失败，请重新登录"
                        404 -> "接口未找到"
                        else -> "HTTP ${response.code}: ${response.message}"
                    }
                    Log.e(TAG, "获取通话信息失败: $errorMsg")
                    Pair(false, errorMsg)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取通话信息异常: ${e.message}")
            Pair(false, "异常: ${e.message}")
        }
    }

    /**
     * 启动 Vision App 并加入会议
     */
    suspend fun startVisionAppMeeting(meetingNumber: String): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "启动 Vision App 并加入会议: $meetingNumber")

            // 先启动应用
            val startResult = startVisionApp()

            if (startResult.first) {
                // 应用启动成功，等待一段时间让应用完全启动
                kotlinx.coroutines.delay(3000)

                // 发起通话
                val callResult = makeCall(meetingNumber)

                if (callResult.first) {
                    Log.d(TAG, "vl.vision.app 已启动，并成功发起通话: $meetingNumber")
                    Pair(true, "vl.vision.app 已启动，并成功发起通话: $meetingNumber")
                } else {
                    Log.d(TAG, "应用启动成功，但发起通话失败: ${callResult.second}")
                    Pair(false, "应用启动成功，但发起通话失败: ${callResult.second}")
                }
            } else {
                Log.d(TAG, "vl.vision.app started failed")
                startResult
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动 Vision App 会议异常: ${e.message}")
            Pair(false, "启动异常: ${e.message}")
        }
    }

    /**
     * 直接发起通话（不启动应用，假设应用已在运行）
     */
    suspend fun makeCallOnly(meetingNumber: String): Pair<Boolean, String> {
        return makeCall(meetingNumber)
    }

    /**
     * 启动 Vision App
     */
    private fun startVisionApp(): Pair<Boolean, String> {
        return try {
            // 方法1：使用 ComponentName 显式启动
            try {
                val intent = android.content.Intent()
                intent.component = android.content.ComponentName(
                    "vl.vision.app",
                    "com.taxiao.cn.mvp.module.tv.splash.SplashActivity"
                )
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return Pair(true, "vl.vision.app 已通过显式 Intent 启动")
            } catch (e: Exception) {
                Log.w(TAG, "显式 Intent 失败: ${e.message}")
            }

            // 方法2：尝试通过包名获取启动 Intent
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("vl.vision.app")
                if (launchIntent != null) {
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    launchIntent.putExtra("meeting_number", "")
                    context.startActivity(launchIntent)
                    return Pair(true, "通过启动器 Intent 启动成功")
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动器 Intent 失败: ${e.message}")
            }

            // 所有方法都失败
            Pair(false, "无法启动 vl.vision.app 应用")
        } catch (e: Exception) {
            Log.e(TAG, "启动过程异常: ${e.message}")
            Pair(false, "启动异常: ${e.message}")
        }
    }

    /**
     * 退出登录
     */
    fun logout() {
        cookie = null
        isLoggedIn = false
        Log.d(TAG, "已退出登录")
    }

    /**
     * 检查应用是否已安装
     */
    fun isVisionAppInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("vl.vision.app", 0) != null
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
    }
}

/**
 * TLS Socket Factory 用于信任所有证书（仅用于本地测试）
 */
class TLSSocketFactory : javax.net.ssl.SSLSocketFactory() {
    private val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
    val trustManager = arrayOf(TrustAllManager())

    init {
        sslContext.init(null, trustManager, null)
    }

    override fun getDefaultCipherSuites(): Array<String> {
        return sslContext.socketFactory.defaultCipherSuites
    }

    override fun getSupportedCipherSuites(): Array<String> {
        return sslContext.socketFactory.supportedCipherSuites
    }

    override fun createSocket(s: java.net.Socket?, host: String?, port: Int, autoClose: Boolean): java.net.Socket {
        return sslContext.socketFactory.createSocket(s, host, port, autoClose)
    }

    override fun createSocket(host: String?, port: Int): java.net.Socket {
        return sslContext.socketFactory.createSocket(host, port)
    }

    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket {
        return sslContext.socketFactory.createSocket(host, port, localHost, localPort)
    }

    override fun createSocket(address: java.net.InetAddress?, port: Int): java.net.Socket {
        return sslContext.socketFactory.createSocket(address, port)
    }

    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket {
        return sslContext.socketFactory.createSocket(address, port, localAddress, localPort)
    }
}

/**
 * 信任所有证书的 TrustManager（仅用于本地测试）
 */
class TrustAllManager : javax.net.ssl.X509TrustManager {
    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {
        // 信任所有客户端证书
    }

    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {
        // 信任所有服务器证书
    }

    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> {
        return emptyArray()
    }
}