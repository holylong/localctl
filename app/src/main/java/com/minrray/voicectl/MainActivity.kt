package com.minrray.voicectl

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.minrray.voicectl.ui.theme.VoicectlTheme
import com.minrray.voicectl.ui.AppScreen
import com.minrray.voicectl.core.contact.ContactManager
import com.minrray.voicectl.core.service.FloatingWindowService
import com.minrray.voicectl.core.service.WakeWordService
import com.minrray.voicectl.core.meeting.MeetingScheduleManager
import com.minrray.voicectl.core.meeting.MeetingMonitorService
import com.minrray.voicectl.core.meeting.MeetingReportManager
import com.minrray.voicectl.core.config.ConfigManager
import com.minrray.voicectl.core.utils.NetworkSecurityUpdater

class MainActivity : ComponentActivity() {
    private var shouldFinish = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 初始化ContactManager
        ContactManager.initialize(this)
        
        // 初始化会议预约管理
        MeetingScheduleManager.initialize(this)
        
        // 初始化会议报告管理
        MeetingReportManager.initialize(this)
        
        // 检查并记录网络安全配置
        checkNetworkSecurityConfig()
        
        // 启动会议监控服务
        startMeetingMonitorService()
        
        // 请求通知权限
        checkAndRequestNotificationPermission()
        
        // 请求相机权限
        checkAndRequestCameraPermission()
        
        // 请求悬浮窗权限并启动悬浮窗服务
        checkAndRequestOverlayPermission()
        
        setContent {
            VoicectlTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingWindowService()
            }
        } else {
            startFloatingWindowService()
        }
        
        // 检查并启动唤醒词服务
        checkAndStartWakeWordService()
        
        // 延迟关闭主界面，确保服务启动完成
        if (!shouldFinish) {
            shouldFinish = true
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 2000)
        }
    }
    
    /**
     * 检查并启动唤醒词服务
     */
    private fun checkAndStartWakeWordService() {
        if (hasRecordAudioPermission()) {
            startWakeWordService()
        }
    }
    
    /**
     * 启动唤醒词服务
     */
    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d("MainActivity", "唤醒词服务已启动")
    }
    
    /**
     * 检查并请求悬浮窗权限
     */
    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // 没有悬浮窗权限，跳转到设置页面请求权限
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_PERMISSION_REQUEST_CODE)
            } else {
                // 已有悬浮窗权限，直接启动悬浮窗服务
                startFloatingWindowService()
            }
        } else {
            // Android 6.0以下不需要特殊权限，直接启动悬浮窗服务
            startFloatingWindowService()
        }
    }
    
    /**
     * 启动悬浮窗服务
     */
    private fun startFloatingWindowService() {
        Log.d("MainActivity", "startFloatingWindowService: 开始启动悬浮窗服务")
        if (!hasRecordAudioPermission()) {
            Log.d("MainActivity", "startFloatingWindowService: 没有录音权限，请求权限")
            requestRecordAudioPermission()
            return
        }
        val intent = Intent(this, FloatingWindowService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
            Log.d("MainActivity", "startFloatingWindowService: 已启动前台服务")
        } else {
            startService(intent)
            Log.d("MainActivity", "startFloatingWindowService: 已启动服务")
        }
    }
    
    /**
     * 检查并请求通知权限
     */
    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    /**
     * 检查并请求相机权限
     */
    private fun checkAndRequestCameraPermission() {
        if (!hasCameraPermission()) {
            requestCameraPermission()
        }
    }
    
    /**
     * 处理权限请求结果
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    // 获得了悬浮窗权限，启动悬浮窗服务和唤醒词服务
                    startFloatingWindowService()
                    startWakeWordService()
                    
                    // 延迟关闭主界面，确保服务启动完成
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 1000)
                }
            }
        }
    }
    
    /**
     * 处理运行时权限请求结果
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 通知权限已授予
                Log.d("MainActivity", "通知权限已授予")
            } else {
                // 通知权限被拒绝
                Log.d("MainActivity", "通知权限被拒绝")
            }
        } else if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 录音权限已授予，启动悬浮窗服务和唤醒词服务
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                    startFloatingWindowService()
                }
                startWakeWordService()
                
                // 延迟关闭主界面，确保服务启动完成
                Handler(Looper.getMainLooper()).postDelayed({
                    finish()
                }, 1000)
            } else {
                Log.d("MainActivity", "录音权限被拒绝，悬浮窗服务未启动")
            }
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 相机权限已授予
                Log.d("MainActivity", "相机权限已授予")
            } else {
                // 相机权限被拒绝
                Log.d("MainActivity", "相机权限被拒绝")
            }
        }
    }
    
    /**
     * 启动会议监控服务
     */
    private fun startMeetingMonitorService() {
        val intent = Intent(this, MeetingMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    
    companion object {
        private const val OVERLAY_PERMISSION_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
        private const val RECORD_AUDIO_PERMISSION_REQUEST_CODE = 1003
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1004
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRecordAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            RECORD_AUDIO_PERMISSION_REQUEST_CODE
        )
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }

    private fun checkNetworkSecurityConfig() {
        try {
            val configManager = ConfigManager.getInstance(this)
            val allConfigs = configManager.loadAllConfigs()
            val serverUrl = allConfigs.deviceConfig.serverUrl
            
            if (serverUrl.isNotEmpty()) {
                NetworkSecurityUpdater.checkAndLogConfig(this, serverUrl)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "检查网络安全配置失败", e)
        }
    }
}
