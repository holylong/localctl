package com.minrray.voicectl.core.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.URI

object NetworkSecurityUpdater {
    private const val TAG = "NetworkSecurityUpdater"

    private val configFilePaths = listOf(
        "/data/local/tmp/network_security_config.xml",
        "/sdcard/Android/data/com.minrray.voicectl/files/network_security_config.xml"
    )

    fun updateConfigIfNeeded(context: Context, serverUrl: String): Boolean {
        try {
            val ip = extractIpFromUrl(serverUrl)
            if (ip == null) {
                Log.w(TAG, "无法从URL提取IP: $serverUrl")
                return false
            }

            Log.i(TAG, "检查IP是否需要添加到网络安全配置: $ip")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Log.d(TAG, "Android 9.0+ 需要配置网络安全策略")
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "更新网络安全配置失败", e)
            return false
        }
    }

    private fun extractIpFromUrl(url: String): String? {
        return try {
            val uri = URI(url)
            val host = uri.host
            if (host != null && isValidIpAddress(host)) {
                host
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析URL失败: $url", e)
            null
        }
    }

    private fun isValidIpAddress(host: String): Boolean {
        val ipPattern = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$")
        return ipPattern.matches(host)
    }

    fun checkAndLogConfig(context: Context, serverUrl: String) {
        val ip = extractIpFromUrl(serverUrl)
        if (ip != null) {
            Log.i(TAG, "========================================")
            Log.i(TAG, "网络安全配置检查")
            Log.i(TAG, "========================================")
            Log.i(TAG, "服务器URL: $serverUrl")
            Log.i(TAG, "服务器IP: $ip")
            Log.i(TAG, "Android版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Log.i(TAG, "注意: Android 9.0+ 需要在 network_security_config.xml 中配置明文流量白名单")
            }
            
            Log.i(TAG, "========================================")
            Log.i(TAG, "请确保以下IP已添加到 network_security_config.xml:")
            Log.i(TAG, "  - $ip")
            Log.i(TAG, "========================================")
        }
    }
}
