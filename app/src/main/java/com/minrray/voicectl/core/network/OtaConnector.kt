package com.minrray.voicectl.core.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class OtaConnector private constructor() {
    companion object {
        private const val TAG = "OtaConnector"
        private lateinit var instance: OtaConnector
        fun getInstance(): OtaConnector {
            if (!::instance.isInitialized) {
                instance = OtaConnector()
            }
            return instance
        }
    }

    private var client: OkHttpClient? = null
    private var isConnected = false

    // 回调接口
    var onConnectionStateChange: ((Boolean) -> Unit)? = null

    // 初始化HTTP客户端
    private fun initClient() {
        client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    // 获取WebSocket服务器地址
    suspend fun getWebSocketUrl(otaUrl: String, deviceId: String, clientId: String): String? {
        initClient()

        try {
            Log.d(TAG, "开始OTA请求，URL: $otaUrl, deviceId: $deviceId, clientId: $clientId")
            // 直接从OTA URL解析WebSocket URL，不需要发送请求
            val wsUrl = parseWebSocketUrlFromOtaUrl(otaUrl, deviceId, clientId)
            Log.d(TAG, "从OTA URL解析出WebSocket URL: $wsUrl")
            isConnected = true
            onConnectionStateChange?.invoke(true)
            return wsUrl
        } catch (e: Exception) {
            Log.e(TAG, "OTA请求异常: ${e.message}", e)
            Log.e(TAG, "异常类型: ${e::class.simpleName}")
            isConnected = false
            onConnectionStateChange?.invoke(false)
        }
        return null
    }

    // 从OTA URL解析WebSocket服务器地址
    private fun parseWebSocketUrlFromOtaUrl(otaUrl: String, deviceId: String, clientId: String): String {
        Log.d(TAG, "解析OTA URL: $otaUrl")
        
        // 解析协议
        val isHttps = otaUrl.startsWith("https://")
        val wsProtocol = if (isHttps) "wss://" else "ws://"
        
        // 移除协议
        val urlWithoutProtocol = otaUrl.removePrefix(if (isHttps) "https://" else "http://")
        Log.d(TAG, "移除协议后: $urlWithoutProtocol")
        
        // 解析主机名和端口
        val hostAndPort = urlWithoutProtocol.split("/")[0]
        Log.d(TAG, "主机和端口: $hostAndPort")
        
        // 解析主机名
        val host = hostAndPort.split(":")[0]
        Log.d(TAG, "主机名: $host")
        
        // 构建WebSocket URL，包含参数
        val wsUrl = "$wsProtocol$host:8000/xiaozhi/v1/?device-id=$deviceId&client-id=$clientId"
        Log.d(TAG, "构建的WebSocket URL: $wsUrl")
        
        return wsUrl
    }

    // 检查OTA连接状态
    fun isConnected(): Boolean {
        return isConnected
    }
}