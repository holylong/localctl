package com.minrray.voicectl.core.mcp

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object McuFun {
    private const val TAG = "McuFun"
    private lateinit var visionApi: VisionApi
    private lateinit var bizConfApi: BizConfApi

    /**
     * 初始化 Vision API（如果尚未初始化）
     */
    private fun initVisionApi(context: Context): VisionApi {
        if (!::visionApi.isInitialized) {
            visionApi = VisionApi(context)
        }
        return visionApi
    }

    /**
     * 初始化 BizConf API（如果尚未初始化）
     */
    private fun initBizConfApi(context: Context): BizConfApi {
        if (!::bizConfApi.isInitialized) {
            bizConfApi = BizConfApi(context)
        }
        return bizConfApi
    }

    /**
     * 启动腾讯会议并加入会议
     */
    fun startTencentMeeting(context: Context, meetingNumber: String): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试启动腾讯会议，会议号: $meetingNumber")

            // 方式1: 尝试通过腾讯会议App的scheme加入会议
            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                // 腾讯会议scheme格式
                data = Uri.parse("tmeeting://meeting?action=join&meetingcode=$meetingNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 检查腾讯会议App是否已安装
            val packageManager = context.packageManager
            val activities = packageManager.queryIntentActivities(intent, 0)
            val isTencentMeetingInstalled = activities.any {
                it.activityInfo.packageName.contains("tencent") ||
                        it.activityInfo.packageName.contains("tmeeting") ||
                        it.activityInfo.packageName.contains("com.tencent.wemeet") // 腾讯会议包名
            }

            if (isTencentMeetingInstalled) {
                // 尝试启动腾讯会议App
                context.startActivity(intent)
                Pair(true, "正在启动腾讯会议加入会议 $meetingNumber")
            } else {
                // 方式2: 腾讯会议App未安装，尝试通过网页版加入
                Log.d(TAG, "腾讯会议App未安装，尝试使用网页版")
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://meeting.tencent.com/dm/$meetingNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (webIntent.resolveActivity(packageManager) != null) {
                    context.startActivity(webIntent)
                    Pair(true, "腾讯会议App未安装，已打开网页版加入会议")
                } else {
                    Pair(false, "无法加入会议：请安装腾讯会议App或使用支持HTTPS的浏览器")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动腾讯会议失败: ${e.message}", e)
            Pair(false, "启动腾讯会议失败: ${e.message}")
        }
    }

    /**
     * 启动会畅智云会议
     */
    fun startBizConfMeeting(context: Context, meetingNumber: String, protocolHostStartUrl: String? = null): Pair<Boolean, String> {
        return try {
            val url = if (protocolHostStartUrl != null && protocolHostStartUrl.isNotEmpty()) {
                "$protocolHostStartUrl&cuid=&hidemeetingpanel=&hideinvitebutton=&muteAudio=0&muteVideo=0"
            } else {
                "bizconfvc://meetingnext-qa-bfsite1.bizvideo.cn/join?confno=$meetingNumber&uname=会会助手&muteAudio=0&muteVideo=0"
            }
            Log.d(TAG, "尝试启动会畅智云会议，会议号: $meetingNumber,url=$url")
            val appSchemeUri = Uri.parse(url)

            val appIntent = Intent(Intent.ACTION_VIEW, appSchemeUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(appIntent)
            Log.d(TAG, "已尝试通过会畅智云App Scheme启动会议")
            return Pair(true, "正在打开会畅智云App加入会议...")

        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "未安装app: ${e.message}", e)
            Pair(false, "未安装智云app。")
        } catch (e: SecurityException) {
            // 3. 处理可能的权限或安全异常
            Log.e(TAG, "启动活动时发生安全异常: ${e.message}", e)
            Pair(false, "启动会议失败：权限不足。")
        } catch (e: Exception) {
            // 4. 处理其他所有未知异常
            Log.e(TAG, "启动会畅智云会议发生未知错误: ${e.message}", e)
            Pair(false, "启动会议时发生意外错误: ${e.message}")
        }
    }

    /**
     * 启动vl.vision.app应用并加入会议
     * 使用 VisionApi 进行 HTTP 接口调用
     */
    fun startVisionAppMeeting(context: Context, meetingNumber: String): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试通过 Vision API 启动 vl.vision.app 并加入会议，会议号: $meetingNumber")

            // 初始化 VisionApi
            val api = initVisionApi(context)

            // 由于 VisionApi 的 startVisionAppMeeting 是挂起函数，我们需要在协程中调用
            // 这里使用 runBlocking 将异步调用转换为同步调用
            var result: Pair<Boolean, String> = Pair(false, "未知错误")

            // 在 IO 线程上执行阻塞调用
            val thread = Thread {
                kotlinx.coroutines.runBlocking {
                    result = api.startVisionAppMeeting(meetingNumber)
                }
            }
            thread.start()
            thread.join() // 等待线程完成

            result


        } catch (e: Exception) {
            Log.e(TAG, "启动 Vision App 会议失败: ${e.message}", e)
            Pair(false, "启动 Vision App 会议失败: ${e.message}")
        }
    }

    /**
     * 挂断 Vision 通话
     */
    fun dropVisionCall(context: Context): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试挂断 Vision 通话")

            // 初始化 VisionApi
            val api = initVisionApi(context)

            // 检查应用是否已安装
            if (!api.isVisionAppInstalled()) {
                return Pair(false, "vl.vision.app 应用未安装")
            }

            // 在协程中挂断通话
            var result: Pair<Boolean, String> = Pair(false, "未知错误")

            val thread = Thread {
                kotlinx.coroutines.runBlocking {
                    result = api.dropCall()
                }
            }
            thread.start()
            thread.join()

            result

        } catch (e: Exception) {
            Log.e(TAG, "挂断 Vision 通话失败: ${e.message}", e)
            Pair(false, "挂断 Vision 通话失败: ${e.message}")
        }
    }

    /**
     * 获取 Vision 通话状态
     */
    fun getVisionCallState(context: Context): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试获取 Vision 通话状态")

            // 初始化 VisionApi
            val api = initVisionApi(context)

            // 检查应用是否已安装
            if (!api.isVisionAppInstalled()) {
                return Pair(false, "vl.vision.app 应用未安装")
            }

            // 在协程中获取状态
            var result: Pair<Boolean, String> = Pair(false, "未知错误")

            val thread = Thread {
                kotlinx.coroutines.runBlocking {
                    result = api.getCallState()
                }
            }
            thread.start()
            thread.join()

            result

        } catch (e: Exception) {
            Log.e(TAG, "获取 Vision 通话状态失败: ${e.message}", e)
            Pair(false, "获取 Vision 通话状态失败: ${e.message}")
        }
    }

    /**
     * 在浏览器中打开会议链接（通用方式）
     */
    fun startMeetingInBrowser(context: Context, meetingType: String, meetingNumber: String): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "在浏览器中打开会议，类型: $meetingType, 会议号: $meetingNumber")

            // 根据会议类型生成不同的URL
            val url = when {
                meetingType.contains("zoom", ignoreCase = true) -> "https://zoom.us/j/$meetingNumber"
                meetingType.contains("google", ignoreCase = true) || meetingType.contains("meet", ignoreCase = true) ->
                    "https://meet.google.com/$meetingNumber"
                meetingType.contains("teams", ignoreCase = true) -> "https://teams.microsoft.com/l/meetup-join/$meetingNumber"
                meetingType.contains("飞书", ignoreCase = true) -> "https://vc.feishu.cn/j/$meetingNumber"
                meetingType.contains("钉钉", ignoreCase = true) -> "https://meeting.dingtalk.com/j/$meetingNumber"
                else -> "https://meeting.tencent.com/dm/$meetingNumber" // 默认使用腾讯会议
            }

            Log.d(TAG, "在浏览器中打开会议链接: $url")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val packageManager = context.packageManager
            if (intent.resolveActivity(packageManager) != null) {
                context.startActivity(intent)
                Pair(true, "已通过浏览器打开$meetingType 会议，会议号: $meetingNumber")
            } else {
                Pair(false, "无法打开会议链接：没有找到可用的浏览器应用")
            }
        } catch (e: Exception) {
            Log.e(TAG, "在浏览器中打开会议失败: ${e.message}", e)
            Pair(false, "在浏览器中打开会议失败: ${e.message}")
        }
    }

    /**
     * 启动钉钉会议
     */
    fun startDingTalkMeeting(context: Context, meetingNumber: String): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试启动钉钉会议，会议号: $meetingNumber")

            // 方式1: 尝试通过钉钉App的scheme加入会议
            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                // 钉钉scheme格式
                data = Uri.parse("dingtalk://dingtalkclient/action/joinconf?confno=$meetingNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 检查钉钉App是否已安装
            val packageManager = context.packageManager
            val activities = packageManager.queryIntentActivities(intent, 0)
            val isDingTalkInstalled = activities.any {
                it.activityInfo.packageName.contains("dingtalk") ||
                        it.activityInfo.packageName.contains("com.alibaba.android.rimet") // 钉钉包名
            }

            if (isDingTalkInstalled) {
                // 尝试启动钉钉App
                context.startActivity(intent)
                Pair(true, "正在启动钉钉加入会议 $meetingNumber")
            } else {
                // 方式2: 钉钉App未安装，尝试通过网页版加入
                Log.d(TAG, "钉钉App未安装，尝试使用网页版")
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://meeting.dingtalk.com/j/$meetingNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (webIntent.resolveActivity(packageManager) != null) {
                    context.startActivity(webIntent)
                    Pair(true, "钉钉App未安装，已打开网页版加入会议")
                } else {
                    Pair(false, "无法加入会议：请安装钉钉App或使用支持HTTPS的浏览器")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动钉钉会议失败: ${e.message}", e)
            Pair(false, "启动钉钉会议失败: ${e.message}")
        }
    }

    /**
     * 启动飞书会议
     */
    fun startFeishuMeeting(context: Context, meetingNumber: String): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试启动飞书会议，会议号: $meetingNumber")

            // 方式1: 尝试通过飞书App的scheme加入会议
            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                // 飞书scheme格式
                data = Uri.parse("lark://open/videocall?callId=$meetingNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 检查飞书App是否已安装
            val packageManager = context.packageManager
            val activities = packageManager.queryIntentActivities(intent, 0)
            val isFeishuInstalled = activities.any {
                it.activityInfo.packageName.contains("feishu") ||
                        it.activityInfo.packageName.contains("lark") ||
                        it.activityInfo.packageName.contains("com.ss.android.lark") // 飞书包名
            }

            if (isFeishuInstalled) {
                // 尝试启动飞书App
                context.startActivity(intent)
                Pair(true, "正在启动飞书加入会议 $meetingNumber")
            } else {
                // 方式2: 飞书App未安装，尝试通过网页版加入
                Log.d(TAG, "飞书App未安装，尝试使用网页版")
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://vc.feishu.cn/j/$meetingNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (webIntent.resolveActivity(packageManager) != null) {
                    context.startActivity(webIntent)
                    Pair(true, "飞书App未安装，已打开网页版加入会议")
                } else {
                    Pair(false, "无法加入会议：请安装飞书App或使用支持HTTPS的浏览器")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动飞书会议失败: ${e.message}", e)
            Pair(false, "启动飞书会议失败: ${e.message}")
        }
    }

    /**
     * 启动Zoom会议
     */
    fun startZoomMeeting(context: Context, meetingNumber: String): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试启动Zoom会议，会议号: $meetingNumber")

            // 方式1: 尝试通过Zoom App的scheme加入会议
            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                // Zoom scheme格式
                data = Uri.parse("zoomus://zoom.us/join?confno=$meetingNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 检查Zoom App是否已安装
            val packageManager = context.packageManager
            val activities = packageManager.queryIntentActivities(intent, 0)
            val isZoomInstalled = activities.any {
                it.activityInfo.packageName.contains("zoom") ||
                        it.activityInfo.packageName.contains("us.zoom.videomeetings") // Zoom包名
            }

            if (isZoomInstalled) {
                // 尝试启动Zoom App
                context.startActivity(intent)
                Pair(true, "正在启动Zoom加入会议 $meetingNumber")
            } else {
                // 方式2: Zoom App未安装，尝试通过网页版加入
                Log.d(TAG, "Zoom App未安装，尝试使用网页版")
                val webIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse("https://zoom.us/j/$meetingNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (webIntent.resolveActivity(packageManager) != null) {
                    context.startActivity(webIntent)
                    Pair(true, "Zoom App未安装，已打开网页版加入会议")
                } else {
                    Pair(false, "无法加入会议：请安装Zoom App或使用支持HTTPS的浏览器")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动Zoom会议失败: ${e.message}", e)
            Pair(false, "启动Zoom会议失败: ${e.message}")
        }
    }

    /**
     * 在云服务器上预约会议
     * @param context 上下文
     * @param meetingType 会议类型
     * @param meetingNumber 会议号码
     * @param meetingTime 会议时间（时间戳）
     * @param meetingTheme 会议主题
     * @param meetingDuration 会议时长（分钟）
     */
    fun executeScheduleMeetingOnCloudServer(
        context: Context,
        meetingNumber: String,
        confPassword: String?,
        startTime: String,
        duration: Int,
        confName: String?,
        confParties: Int
    ): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试在云服务器上预约会议，会议号: $meetingNumber")

            val api = initBizConfApi(context)

            var result: Pair<Boolean, String> = Pair(false, "未知错误")

            val thread = Thread {
                kotlinx.coroutines.runBlocking {
                    result = api.scheduleMeeting(
                        meetingNumber,
                        confPassword,
                        startTime,
                        duration,
                        confName
                    )
                }
            }
            thread.start()
            thread.join()

            result

        } catch (e: Exception) {
            Log.e(TAG, "云服务器预约会议失败: ${e.message}", e)
            Pair(false, "云服务器预约会议失败: ${e.message}")
        }
    }

    /**
     * 在云服务器上取消会议
     * @param context 上下文
     * @param meetingId 会议ID
     */
    fun executeCancelMeetingOnCloudServer(
        context: Context,
        meetingId: String
    ): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试在云服务器上取消会议，会议ID: $meetingId")

            val api = initBizConfApi(context)

            var result: Pair<Boolean, String> = Pair(false, "未知错误")

            val thread = Thread {
                kotlinx.coroutines.runBlocking {
                    result = api.cancelMeeting(meetingId)
                }
            }
            thread.start()
            thread.join()

            result

        } catch (e: Exception) {
            Log.e(TAG, "云服务器取消会议失败: ${e.message}", e)
            Pair(false, "云服务器取消会议失败: ${e.message}")
        }
    }

    /**
     * 在云服务器上更新会议
     * @param context 上下文
     * @param meetingId 会议ID
     * @param newMeetingTime 新的会议时间（时间戳）
     */
    fun executeUpdateMeetingOnCloudServer(
        context: Context,
        confId: String,
        confName: String,
        confPassword: String?,
        optionJbh: Int,
        optionVideoHost: Int,
        optionVideoParticipants: Int,
        isTuiliu: Int?,
        streamDiy: Int,
        streamUrl: String?,
        streamKey: String?,
        watchUrl: String?,
        channelId: Long?,
        ignoreSpecial: Int,
        muteWhenJoining: Int
    ): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试在云服务器上修改会议，会议ID: $confId, 会议名称: $confName")

            val api = initBizConfApi(context)

            var result: Pair<Boolean, String> = Pair(false, "未知错误")

            val thread = Thread {
                kotlinx.coroutines.runBlocking {
                    result = api.updateMeeting(
                        confId,
                        confName,
                        confPassword,
                        optionJbh,
                        optionVideoHost,
                        optionVideoParticipants,
                        isTuiliu,
                        streamDiy,
                        streamUrl,
                        streamKey,
                        watchUrl,
                        channelId,
                        ignoreSpecial,
                        muteWhenJoining
                    )
                }
            }
            thread.start()
            thread.join()

            result

        } catch (e: Exception) {
            Log.e(TAG, "云服务器修改会议失败: ${e.message}", e)
            Pair(false, "云服务器修改会议失败: ${e.message}")
        }
    }

    /**
     * 在云服务器上结束会议
     * @param context 上下文
     * @param confId 会议ID
     */
    fun executeExitMeetingOnCloudServer(
        context: Context,
        confId: String
    ): Pair<Boolean, String> {
        return try {
            Log.d(TAG, "尝试在云服务器上结束会议，会议ID: $confId")

            val api = initBizConfApi(context)

            var result: Pair<Boolean, String> = Pair(false, "未知错误")

            val thread = Thread {
                kotlinx.coroutines.runBlocking {
                    result = api.exitMeeting(confId)
                }
            }
            thread.start()
            thread.join()

            result

        } catch (e: Exception) {
            Log.e(TAG, "云服务器结束会议失败: ${e.message}", e)
            Pair(false, "云服务器结束会议失败: ${e.message}")
        }
    }
}