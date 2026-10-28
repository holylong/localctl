package com.minrray.voicectl.core.mcp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import android.hardware.camera2.*
import android.media.ImageReader
import android.graphics.BitmapFactory
import android.app.ActivityManager
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import java.util.UUID
import com.minrray.voicectl.core.network.WebSocketHandler
import com.minrray.voicectl.core.audio.AudioRecorder
import com.minrray.voicectl.core.contact.ContactManager
import com.minrray.voicectl.core.meeting.MeetingSchedule
import com.minrray.voicectl.core.meeting.MeetingScheduleManager
import com.minrray.voicectl.core.meeting.MeetingNumberManager
import com.minrray.voicectl.core.meeting.MeetingReportManager
import com.minrray.voicectl.core.service.WakeWordService
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.runBlocking

interface SpeakerRegisterProgressListener {
    fun onSpeakerRegisterProgress(progress: Int, maxProgress: Int)
    fun onSpeakerRegisterComplete(success: Boolean, message: String)
}

class McpToolsManager private constructor() {
    companion object {
        private const val TAG = "McpToolsManager"
        private lateinit var instance: McpToolsManager
        private var speakerRegisterProgressListener: SpeakerRegisterProgressListener? = null
        
        fun getInstance(): McpToolsManager {
            if (!::instance.isInitialized) {
                instance = McpToolsManager()
            }
            return instance
        }
        
        fun setSpeakerRegisterProgressListener(listener: SpeakerRegisterProgressListener?) {
            speakerRegisterProgressListener = listener
        }
        
        /**
         * 获取应用上下文
         * @return 应用上下文
         */
        private fun getContext(): Context {
            return ContactManager.getInstance().javaClass.getDeclaredField("context").let {
                it.isAccessible = true
                it.get(ContactManager.getInstance()) as Context
            }
        }
    }
    
    // MCP工具数据类
    data class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JSONObject,
        val mockResponse: JSONObject?
    )
    
    // 初始化工具列表
    private val mcpTools: MutableList<McpTool> = mutableListOf()
    
    // 存储当前 MCP 调用的 sessionId 和 id
    private var currentSessionId: String = ""
    private var currentMcpCallId: String = ""
    
    init {
        // 初始化默认工具
        initDefaultMcpTools()
    }
    
    // 初始化默认MCP工具
    private fun initDefaultMcpTools() {
        // 添加默认工具，这些工具与前端的default-mcp-tools.json内容一致
        val tools = listOf(
            createTool(
                "self.get_current_time",
                "获取设备当前的时间信息，包括时间戳和格式化日期时间",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
                JSONObject().apply {
                    put("success", true)
                    put("timestamp", System.currentTimeMillis())
                    put("formatted_datetime", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
                }
            ),
            createTool(
                "self.get_device_status",
                "Provides the real-time information of the device, including the current status of the audio speaker, screen, battery, network, etc.\nUse this tool for: \n1. Answering questions about current condition (e.g. what is the current volume of the audio speaker?)\n2. As the first step to control the device (e.g. turn up / down the volume of the audio speaker, etc.)",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
                JSONObject().apply {
                    put("audio_speaker", JSONObject().apply {
                        put("volume", 50)
                        put("muted", false)
                    })
                    put("screen", JSONObject().apply {
                        put("brightness", 80)
                        put("theme", "light")
                    })
                    put("battery", JSONObject().apply {
                        put("level", 85)
                        put("charging", false)
                    })
                    put("network", JSONObject().apply {
                        put("connected", true)
                        put("type", "wifi")
                    })
                }
            ),
            createTool(
                "self.audio_speaker.set_volume",
                "Set the volume of the audio speaker. If the current volume is unknown, you must call `self.get_device_status` tool first and then call this tool.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("volume", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 100)
                        })
                    })
                    put("required", JSONArray().apply {
                        put("volume")
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("volume", "\${volume}")
                    put("message", "音量已设置为 \${volume}")
                }
            ),
            createTool(
                "self.screen.set_brightness",
                "Set the brightness of the screen.",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("brightness", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 100)
                        })
                    })
                    put("required", JSONArray().apply {
                        put("brightness")
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("brightness", "\${brightness}")
                    put("message", "亮度已设置为 \${brightness}")
                }
            ),
            createTool(
                "self.join_meeting",
                "用于加入视频会议，需要会议号。如果不指定会议类型，默认使用智云会议",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("meeting_type", JSONObject().apply {
                            put("type", "string")
                            put("description", "会议类型，如智云、腾讯会议等。默认使用智云会议")
                        })
                        put("meeting_number", JSONObject().apply {
                            put("type", "string")
                            put("description", "要加入的会议号码")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("meeting_number")
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "已成功加入\${meeting_type}会议 \${meeting_number}")
                }
            ),
            createTool(
                "self.list_meeting_schedules",
                "用于查询已预约的视频会议列表，可以按日期筛选。返回结果中包含meeting_time（毫秒时间戳）、meeting_time_formatted（格式化时间）、meeting_time_description（自然语言描述，如'晚上8点'）和time_period（时间段分类：morning/forenoon/noon/afternoon/evening/night）四个时间字段",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("date", JSONObject().apply {
                            put("type", "string")
                            put("description", "要查询的日期，自然语言描述，例如：今天、明天、2025-12-20。如果不提供，则查询所有会议")
                        })
                    })
                    put("required", JSONArray().apply {
                    })
                },
                null
            ),
            createTool(
                "self_schedule_meeting",
                "用于预约视频会议，在指定时间提醒并自动加入。如果不指定会议类型，默认使用智云会议。会议号码由系统自动生成（0001-9999），程序重启后不会重复。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("meeting_type", JSONObject().apply {
                            put("type", "string")
                            put("description", "会议类型，如智云、腾讯会议等。如果不提供，默认使用智云会议")
                        })
                        put("meeting_time", JSONObject().apply {
                            put("type", "string")
                            put("description", "会议开始时间，自然语言描述，例如：今天下午3点、明天上午10点、2025-12-20 14:30")
                        })
                        put("meeting_theme", JSONObject().apply {
                            put("type", "string")
                            put("description", "会议主题")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("meeting_time")
                        put("meeting_theme")
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "会议预约成功，将在会议开始前3分钟提醒")
                }
            ),
            createTool(
                "self.cancel_meeting_schedule",
                "用于取消已预约的视频会议。支持以下几种方式：1) 使用meeting_id取消特定会议；2) 仅使用meeting_number取消该会议号的所有会议；3) 同时使用meeting_number和meeting_time精确匹配特定时间的会议。用户说'取消会议XXX'或'取消XXX'时，XXX就是会议号，应该使用meeting_number参数。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("meeting_id", JSONObject().apply {
                            put("type", "string")
                            put("description", "要取消的会议预约ID（优先使用，如果提供则忽略其他参数）")
                        })
                        put("meeting_number", JSONObject().apply {
                            put("type", "string")
                            put("description", "要取消的会议号。用户说'取消会议XXX'或'取消XXX'时，XXX就是会议号。可以单独使用该参数取消该会议号的所有会议，也可以配合meeting_time参数使用以精确匹配特定时间的会议")
                        })
                        put("meeting_time", JSONObject().apply {
                            put("type", "integer")
                            put("description", "会议时间（毫秒时间戳），必须与meeting_number一起使用。注意：这是从list_meeting_schedules工具返回的meeting_time字段的值，不是自然语言描述")
                        })
                    })
                    put("required", JSONArray().apply {
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "会议预约已取消")
                }
            ),
            createTool(
                "self.cancel_meetings_by_time_period",
                "用于批量删除指定时间范围内的全部会议。支持自然语言时间描述，例如：'删除下午的全部会议'、'取消上午的所有会议'、'删除今天晚上的会议'等。会删除所有符合时间范围的会议预约记录。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("time_period", JSONObject().apply {
                            put("type", "string")
                            put("description", "时间范围描述，例如：'下午'、'上午'、'晚上'、'morning'、'afternoon'、'evening'等")
                        })
                        put("date", JSONObject().apply {
                            put("type", "string")
                            put("description", "日期描述，例如：'今天'、'明天'、'2025-12-20'等。如果不提供，默认为今天")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("time_period")
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "指定时间范围内的会议已全部取消")
                }
            ),
            createTool(
                "self.confirm_meeting",
                "用于确认或取消确认参加会议",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("meeting_id", JSONObject().apply {
                            put("type", "string")
                            put("description", "会议预约ID")
                        })
                        put("meeting_number", JSONObject().apply {
                            put("type", "string")
                            put("description", "会议号")
                        })
                        put("meeting_time", JSONObject().apply {
                            put("type", "integer")
                            put("description", "会议时间（毫秒时间戳），与会议号一起使用以精确匹配会议")
                        })
                        put("confirm", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "是否确认参加，true表示参加，false表示不参加")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("confirm")
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "会议确认状态已更新")
                }
            ),
            createTool(
                "self.update_meeting_schedule",
                "用于更新已预约的会议时间",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("meeting_id", JSONObject().apply {
                            put("type", "string")
                            put("description", "要更新的会议预约ID")
                        })
                        put("meeting_number", JSONObject().apply {
                            put("type", "string")
                            put("description", "要更新的会议号")
                        })
                        put("meeting_time", JSONObject().apply {
                            put("type", "string")
                            put("description", "新的会议开始时间，自然语言描述，例如：今天下午3点、明天上午10点、2025-12-20 14:30")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("meeting_time")
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "会议时间已更新")
                }
            ),
            createTool(
                "self.exit_meeting",
                "用于退出当前视频会议，也用于结束云平台正在召开得会议",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                    put("required", JSONArray())
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "已成功退出会议")
                }
            ),
            createTool(
                "self.get_site_meeting_info",
                "用于查询指定平台（默认智云平台）的会议信息，包括正在进行的会议和已预约的会议。返回结果包含会议列表、会议状态、会议时间等信息，可以用于推荐用户参加正在进行的会议或预约已预约好的会议",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("site_sign", JSONObject().apply {
                            put("type", "string")
                            put("description", "站点标识，默认为'kexinyun'（智云平台）。可以指定其他平台标识")
                        })
                        put("page_no", JSONObject().apply {
                            put("type", "integer")
                            put("description", "页码，默认为1")
                        })
                        put("page_size", JSONObject().apply {
                            put("type", "integer")
                            put("description", "每页条目数，默认为10")
                        })
                    })
                    put("required", JSONArray().apply {
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "已成功获取站点会议信息")
                }
            ),
            createTool(
                "self.get_meeting_report",
                "用于查询指定会议的报告信息，包括会议主题、录音开始时间、录音结束时间、文件列表等详细信息。返回结果包含会议主题、录音时间范围、文件下载链接、文件大小、文件类型、记录类型（全文转写content或智能纪要aiSummary）等",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("conf_no", JSONObject().apply {
                            put("type", "string")
                            put("description", "会议号，要查询报告的会议号")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("conf_no")
                    })
                },
                null
            ),
            createTool(
                "self.face_register",
                "人脸注册功能。当用户说'记住我，我是XXX'、'我是XXX'、'注册人脸'、'记住我的脸'、'我是张三'等表达要记住身份或注册人脸的意图时，必须调用此函数进行人脸注册，而不是对话说'我记住你了'。该函数会通过摄像头拍照注册人脸，并将用户身份ID、角色权限和名字与人脸特征绑定。注册成功后会默认设置为master权限，master权限用户的人脸识别会自动触发唤醒词问候。使用场景：用户说'记住我，我是张三'、'我是李四'、'注册我的脸'、'记住我的脸'、'我是王五'等。注意：当用户说'记住我，我是XXX'时，XXX就是用户的姓名，必须调用此函数进行人脸注册，不要回复'我记住你了'或'好的，我记住了'等对话内容。如果用户没有提供姓名，必须先询问用户的姓名，不能自动生成。默认权限为master，注册成功后告知用户已设置为master权限。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("identity_id", JSONObject().apply {
                            put("type", "string")
                            put("description", "用户身份ID，例如：test1、test2等。如果用户没有提供，可以使用用户姓名的拼音或随机生成一个ID")
                        })
                        put("role_permission", JSONObject().apply {
                            put("type", "string")
                            put("description", "角色权限，例如：master（管理员）、user（普通用户）等，默认为master")
                        })
                        put("name", JSONObject().apply {
                            put("type", "string")
                            put("description", "用户姓名，例如：张三、李四等。从用户的语句中提取姓名，例如用户说'记住我，我是张三'，姓名就是'张三'。如果用户没有提供姓名，必须先询问用户的姓名，不能自动生成")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("name")
                    })
                },
                null
            ),
            createTool(
                "self.weather_query",
                "天气查询功能。用于查询指定日期的天气信息，支持自然语言日期描述，例如：今天、明天、今晚、明天上午、1.22号、1月22号、1月22日等。返回天气状况、温度、湿度、风力等详细信息。使用场景：用户说'今天天气怎么样'、'明天天气如何'、'今晚天气'、'1.22号天气'、'明天上午天气'等。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("date", JSONObject().apply {
                            put("type", "string")
                            put("description", "要查询的日期，自然语言描述，例如：今天、明天、今晚、明天上午、1.22号、1月22号、1月22日等。如果不提供，默认查询今天天气")
                        })
                    })
                    put("required", JSONArray().apply {
                    })
                },
                null
            ),
            createTool(
                "self.reset_user_info",
                "重置用户信息功能。当用户说'重置'、'重置用户信息'、'重置系统'等语句时，调用此函数删除本地数据库中的人脸信息，并重启系统。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("confirm", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "用户是否确认重置操作，true表示确认，false表示取消")
                        })
                    })
                    put("required", JSONArray().apply {
                    })
                },
                null
            ),
            createTool(
                "self.set_terminal_mode",
                "设置终端模式。服务端调用此工具来设置客户端的工作模式（sleep休息模式或work工作模式）。客户端收到工具调用后会设置自己的工作模式，并返回实际设置的结果给服务端。服务端根据客户端返回的实际模式来管理状态：当客户端处于sleep模式时，服务端大模型要能分辨用户传上来的话是否有唤醒自己的意图（如'你醒一醒'、'起来干活'、'快起来'、'别睡觉了'等），没有就不对用户的话做任何回应，有则再次调用此工具设置终端为work模式。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("mode", JSONObject().apply {
                            put("type", "string")
                            put("description", "终端模式，'sleep'表示休息模式，'work'表示工作模式")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("mode")
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "终端模式已设置为\${mode}")
                }
            ),
            createTool(
                "self.speaker_register",
                "声纹注册功能。用于注册用户的声纹信息，将用户姓名与声纹特征绑定。该工具会通过麦克风采集用户的语音数据，提取声纹特征并保存到声纹数据库中。使用场景：用户完成人脸注册后，需要注册声纹以实现语音身份识别。参数name为用户姓名，必须与人脸注册时的姓名一致。注册成功后会返回注册结果。",
                JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().apply {
                            put("type", "string")
                            put("description", "用户姓名，必须与人脸注册时的姓名一致")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("name")
                    })
                },
                JSONObject().apply {
                    put("success", true)
                    put("message", "声纹注册成功")
                }
            )
        )

        mcpTools.addAll(tools)
        Log.d(TAG, "初始化MCP工具完成，共 ${mcpTools.size} 个工具")
    }
    
    // 创建工具实例
    private fun createTool(name: String, description: String, inputSchema: JSONObject, mockResponse: JSONObject?): McpTool {
        return McpTool(name, description, inputSchema, mockResponse)
    }
    
    // 获取工具列表
    fun getMcpTools(): List<McpTool> {
        return mcpTools.toList()
    }
    
    // 转换工具列表为JSONArray
    fun getMcpToolsAsJsonArray(): JSONArray {
        val toolsArray = JSONArray()
        mcpTools.forEach { tool ->
            val toolJson = JSONObject()
            toolJson.put("name", tool.name)
            toolJson.put("description", tool.description)
            toolJson.put("inputSchema", tool.inputSchema)
            toolsArray.put(toolJson)
        }
        return toolsArray
    }
    
    // 执行工具调用
    fun executeMcpTool(toolName: String, toolArgs: JSONObject?): JSONObject {
        val tool = mcpTools.find { it.name == toolName }
        
        if (tool == null) {
            Log.e(TAG, "未找到工具: $toolName")
            return JSONObject().apply {
                put("success", false)
                put("error", "未知工具: $toolName")
            }
        }
        
        Log.d(TAG, "执行工具: $toolName 参数: $toolArgs")
        
        // 特殊处理特定工具，添加实际执行逻辑
        return when (toolName) {
            "self.list_meeting_schedules" -> executeListMeetingSchedulesTool(toolArgs)
            "self_schedule_meeting" -> executeScheduleMeetingTool(toolArgs)
            "self.cancel_meeting_schedule" -> executeCancelMeetingScheduleTool(toolArgs)
            "self.cancel_meetings_by_time_period" -> executeCancelMeetingsByTimePeriodTool(toolArgs)
            "self.join_meeting" -> executeJoinMeetingTool(toolArgs)
            "self.confirm_meeting" -> executeConfirmMeetingTool(toolArgs)
            "self.update_meeting_schedule" -> executeUpdateMeetingScheduleTool(toolArgs)
            "self.exit_meeting" -> executeExitMeetingTool(toolArgs)
            "self.get_site_meeting_info" -> executeGetSiteMeetingInfoTool(toolArgs)
            "self.get_meeting_report" -> executeGetMeetingReportTool(toolArgs)
            "self.face_register" -> executeFaceRegisterTool(toolArgs)
            "self.reset_user_info" -> executeResetUserInfoTool(toolArgs)
            "self.weather_query" -> executeWeatherQueryTool(toolArgs)
            "self.set_terminal_mode" -> executeSetTerminalModeTool(toolArgs)
            "self.speaker_register" -> executeSpeakerRegisterTool(toolArgs)
            "self.get_current_time" -> {
                // 返回实时时间信息
                val currentTime = System.currentTimeMillis()
                val formattedTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(currentTime))
                JSONObject().apply {
                    put("success", true)
                    put("timestamp", currentTime)
                    put("formatted_datetime", formattedTime)
                    put("timezone", java.util.TimeZone.getDefault().id)
                }
            }
            else -> {
                // 其他工具的处理逻辑保持不变
                // 如果有模拟返回结果，使用它
                if (tool.mockResponse != null) {
                    // 替换模板变量
                    val responseStr = replaceTemplateVariables(tool.mockResponse.toString(), toolArgs)
                    return try {
                        val response = JSONObject(responseStr)
                        Log.d(TAG, "工具 $toolName 执行成功，返回模拟结果: $response")
                        response
                    } catch (e: Exception) {
                        Log.e(TAG, "解析模拟返回结果失败: ${e.message}")
                        tool.mockResponse
                    }
                }
                
                // 没有模拟返回结果，返回默认成功消息
                Log.d(TAG, "工具 $toolName 执行成功，返回默认结果")
                JSONObject().apply {
                    put("success", true)
                    put("message", "工具 $toolName 执行成功")
                    put("tool", toolName)
                    put("arguments", toolArgs ?: JSONObject())
                }
            }
        }
    }
    
    // 执行查询会议列表工具
    private fun executeListMeetingSchedulesTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行查询会议列表工具，参数: $toolArgs")
        
        try {
            val meetingScheduleManager = MeetingScheduleManager.getInstance()
            val allSchedules = meetingScheduleManager.getAllMeetingSchedules()
            
            // 解析日期参数
            val dateStr = toolArgs?.optString("date", "") ?: ""
            val filteredSchedules = if (dateStr.isNotEmpty()) {
                // 解析自然语言日期
                val targetDate = parseNaturalLanguageDate(dateStr)
                if (targetDate != null) {
                    // 筛选指定日期的会议
                    allSchedules.filter { schedule ->
                        val scheduleDate = java.util.Date(schedule.meetingTime)
                        isSameDay(scheduleDate, targetDate)
                    }
                } else {
                    // 如果日期解析失败，返回所有会议
                    allSchedules
                }
            } else {
                // 没有提供日期参数，返回所有会议
                allSchedules
            }
            
            // 构建返回结果
            val meetingsArray = JSONArray()
            filteredSchedules.forEach { schedule ->
                val meetingDate = java.util.Date(schedule.meetingTime)
                
                // 使用北京时区格式化时间
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                dateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                val formattedTime = dateFormat.format(meetingDate)
                
                // 使用北京时区计算小时和分钟
                val calendar = java.util.Calendar.getInstance()
                calendar.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
                calendar.time = meetingDate
                val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                val minute = calendar.get(java.util.Calendar.MINUTE)
                
                val timeDescription = when {
                    hour >= 5 && hour < 9 -> "早上${hour}点${if (minute > 0) "${minute}分" else ""}"
                    hour >= 9 && hour < 12 -> "上午${hour}点${if (minute > 0) "${minute}分" else ""}"
                    hour >= 12 && hour < 14 -> "中午${if (hour == 12) "12" else hour}点${if (minute > 0) "${minute}分" else ""}"
                    hour >= 14 && hour < 18 -> "下午${hour - 12}点${if (minute > 0) "${minute}分" else ""}"
                    hour >= 18 && hour < 22 -> "晚上${hour - 12}点${if (minute > 0) "${minute}分" else ""}"
                    else -> "晚上${hour - 12}点${if (minute > 0) "${minute}分" else ""}"
                }
                
                val timePeriod = when {
                    hour >= 5 && hour < 9 -> "morning"
                    hour >= 9 && hour < 12 -> "forenoon"
                    hour >= 12 && hour < 14 -> "noon"
                    hour >= 14 && hour < 18 -> "afternoon"
                    hour >= 18 && hour < 22 -> "evening"
                    else -> "night"
                }
                
                val meetingJson = JSONObject().apply {
                    put("meeting_id", schedule.id)
                    put("meeting_type", schedule.meetingType)
                    put("meeting_number", schedule.meetingNumber)
                    put("meeting_time", schedule.meetingTime)
                    put("meeting_time_formatted", formattedTime)
                    put("meeting_time_description", timeDescription)
                    put("time_period", timePeriod)
                    put("meeting_theme", schedule.meetingName)
                    put("is_confirmed", schedule.isConfirmed)
                }
                meetingsArray.put(meetingJson)
            }
            
            return JSONObject().apply {
                put("success", true)
                put("count", filteredSchedules.size)
                put("meetings", meetingsArray)
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行查询会议列表工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行查询会议列表工具失败: ${e.message}")
            }
        }
    }
    
    // 执行呼叫联系人工具
    private fun executeCallPersonTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行呼叫联系人工具，参数: $toolArgs")
        
        try {
            // 检查参数
            if (toolArgs == null || !toolArgs.has("person_name")) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少person_name参数")
                }
            }
            
            // 获取联系人姓名
            val personName = toolArgs.getString("person_name")
            Log.d(TAG, "要呼叫的联系人: $personName")
            
            // 调用ContactManager的callPerson方法
            val contactManager = ContactManager.getInstance()
            val callResult = contactManager.callPerson(personName)
            
            // 返回执行结果
            return JSONObject().apply {
                put("success", callResult.success)
                put("message", callResult.message)
                put("person_name", personName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行呼叫联系人工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行呼叫联系人工具失败: ${e.message}")
            }
        }
    }
    
    // 替换模板变量
    private fun replaceTemplateVariables(responseStr: String, args: JSONObject?): String {
        if (args == null) return responseStr
        
        var result = responseStr
        val keys = args.keys()
        
        while (keys.hasNext()) {
            val key = keys.next()
            val value = args.optString(key, args.opt(key)?.toString() ?: "")
            val placeholder = "\${" + key + "}" // 创建类似 ${key} 的占位符
            result = result.replace(placeholder, value)
        }
        
        return result
    }
    
    // 停止录音并断开WebSocket连接
    fun stopRecordingAndDisconnect() {
        Log.d(TAG, "执行停止录音并断开连接操作")
        
        // 获取实例
        val wsHandler = WebSocketHandler.getInstance()
        val audioRecorder = AudioRecorder.getInstance()
        
        // 停止 opus 编码（不停止录音）
        if (audioRecorder.isRecording) {
            Log.d(TAG, "停止 opus 编码，保持录音继续")
            audioRecorder.start(enableOpusEncoding = false)
            // 触发录音状态变化回调，更新UI
            wsHandler.onRecordButtonStateChange?.invoke(false)
        }
        
        // 断开WebSocket连接
        if (wsHandler.isConnected()) {
            Log.d(TAG, "断开WebSocket连接")
            wsHandler.disconnect()
        }

        Log.d(TAG, "停止录音并断开连接操作完成，唤醒词服务由FloatingWindowService管理")
    }
    
    // 解析自然语言时间描述为时间戳
    private fun parseNaturalLanguageTime(timeStr: String): Long {
        try {
            Log.d(TAG, "开始解析自然语言时间: $timeStr")
            
            val systemTimeZone = java.util.TimeZone.getDefault()
            Log.d(TAG, "系统默认时区: ${systemTimeZone.id}, 偏移量: ${systemTimeZone.getOffset(System.currentTimeMillis())}ms")
            
            val currentTime = System.currentTimeMillis()
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            dateFormat.timeZone = systemTimeZone
            Log.d(TAG, "当前系统时间: ${dateFormat.format(java.util.Date(currentTime))}")
            
            val utcDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            utcDateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            Log.d(TAG, "当前UTC时间: ${utcDateFormat.format(java.util.Date(currentTime))}")
            
            val beijingDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            beijingDateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            Log.d(TAG, "当前北京时间: ${beijingDateFormat.format(java.util.Date(currentTime))}")
            
            val result = fallbackParseNaturalLanguageTime(timeStr)
            
            Log.d(TAG, "解析时间成功: $timeStr -> 时间戳: $result")
            Log.d(TAG, "  系统时区时间: ${dateFormat.format(java.util.Date(result))}")
            Log.d(TAG, "  UTC时间: ${utcDateFormat.format(java.util.Date(result))}")
            Log.d(TAG, "  北京时间: ${beijingDateFormat.format(java.util.Date(result))}")
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "解析时间异常: ${e.message}", e)
            // 如果解析异常，返回当前时间作为默认值
            return Calendar.getInstance().timeInMillis
        }
    }
    
    // 备用的自然语言时间解析方法（原实现）
    private fun fallbackParseNaturalLanguageTime(timeStr: String): Long {
        val beijingTimeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val now = Calendar.getInstance(beijingTimeZone)
        val targetTime = Calendar.getInstance(beijingTimeZone)
        val trimmedStr = timeStr.trim()
        
        try {
            Log.d(TAG, "开始解析时间字符串: $trimmedStr")
            
            // 处理24小时制时间格式，如：2025-12-20 14:30
            val fullDateTimeRegex = Regex("(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}):(\\d{2})")
            val fullDateTimeMatch = fullDateTimeRegex.find(trimmedStr)
            if (fullDateTimeMatch != null) {
                val year = fullDateTimeMatch.groupValues[1].toInt()
                val month = fullDateTimeMatch.groupValues[2].toInt() - 1
                val day = fullDateTimeMatch.groupValues[3].toInt()
                val hour = fullDateTimeMatch.groupValues[4].toInt()
                val minute = fullDateTimeMatch.groupValues[5].toInt()
                
                targetTime.set(year, month, day, hour, minute, 0)
                targetTime.set(Calendar.MILLISECOND, 0)
                Log.d(TAG, "匹配到完整日期时间格式: $trimmedStr -> ${targetTime.timeInMillis}")
                return targetTime.timeInMillis
            }
            
            // 处理相对时间格式，如：10分钟后、1小时后、2天后、十分钟后、一小时后、两天后
            val chineseNumberMap = mapOf(
                "一" to 1, "二" to 2, "三" to 3, "四" to 4,
                "五" to 5, "六" to 6, "七" to 7, "八" to 8,
                "九" to 9, "十" to 10, "十一" to 11, "十二" to 12,
                "十三" to 13, "十四" to 14, "十五" to 15, "十六" to 16,
                "十七" to 17, "十八" to 18, "十九" to 19, "二十" to 20,
                "三十" to 30, "四十" to 40, "五十" to 50, "六十" to 60
            )
            
            // 匹配阿拉伯数字的相对时间
            val relativeTimeRegex = Regex("(\\d+)\\s*(个)?\\s*(分钟|小时|天|分钟后|小时后|天后)")
            val relativeTimeMatch = relativeTimeRegex.find(trimmedStr)
            
            // 匹配中文数字的相对时间
            val chineseRelativeTimeRegex = Regex("(一|二|三|四|五|六|七|八|九|十|十一|十二|十三|十四|十五|十六|十七|十八|十九|二十|三十|四十|五十|六十)\\s*(个)?\\s*(分钟|小时|天|分钟后|小时后|天后)")
            val chineseRelativeTimeMatch = chineseRelativeTimeRegex.find(trimmedStr)
            
            if (relativeTimeMatch != null || chineseRelativeTimeMatch != null) {
                var amount = 0
                var unit = ""
                
                if (relativeTimeMatch != null) {
                    amount = relativeTimeMatch.groupValues[1].toInt()
                    unit = relativeTimeMatch.groupValues[3]
                } else {
                    val chineseNumber = chineseRelativeTimeMatch!!.groupValues[1]
                    amount = chineseNumberMap[chineseNumber] ?: 0
                    unit = chineseRelativeTimeMatch.groupValues[3]
                }
                
                // 使用北京时间创建Calendar实例
                val relativeTime = Calendar.getInstance(beijingTimeZone)
                
                when (unit) {
                    "分钟", "分钟后" -> relativeTime.add(Calendar.MINUTE, amount)
                    "小时", "小时后" -> relativeTime.add(Calendar.HOUR_OF_DAY, amount)
                    "天", "天后" -> relativeTime.add(Calendar.DAY_OF_YEAR, amount)
                }
                
                Log.d(TAG, "匹配到相对时间格式: $trimmedStr -> ${relativeTime.timeInMillis}")
                return relativeTime.timeInMillis
            }
            
            // 处理月份+日期格式，如：七月五号、7月5号、七月5号、7月5日
            val monthDayRegex = Regex("(\\d+)\\s*[月]\\s*(\\d+)\\s*[号日]")
            val monthDayMatch = monthDayRegex.find(trimmedStr)
            if (monthDayMatch != null) {
                val month = monthDayMatch.groupValues[1].toInt() - 1
                val day = monthDayMatch.groupValues[2].toInt()
                
                val currentYear = now.get(Calendar.YEAR)
                val currentMonth = now.get(Calendar.MONTH)
                val currentDay = now.get(Calendar.DAY_OF_MONTH)
                
                var targetYear = currentYear
                
                if (month < currentMonth || (month == currentMonth && day < currentDay)) {
                    targetYear = currentYear + 1
                }
                
                targetTime.set(targetYear, month, day, 0, 0, 0)
                targetTime.set(Calendar.MILLISECOND, 0)
                Log.d(TAG, "匹配到月份日期格式: $trimmedStr -> ${targetTime.timeInMillis}")
                return targetTime.timeInMillis
            }
            
            val chineseMonthDayRegex = Regex("(一|二|三|四|五|六|七|八|九|十|十一|十二)\\s*月\\s*(\\d+)\\s*[号日]")
            val chineseMonthDayMatch = chineseMonthDayRegex.find(trimmedStr)
            if (chineseMonthDayMatch != null) {
                val monthMap = mapOf(
                    "一" to 1, "二" to 2, "三" to 3, "四" to 4,
                    "五" to 5, "六" to 6, "七" to 7, "八" to 8,
                    "九" to 9, "十" to 10, "十一" to 11, "十二" to 12
                )
                val month = (monthMap[chineseMonthDayMatch.groupValues[1]] ?: 1) - 1
                val day = chineseMonthDayMatch.groupValues[2].toInt()
                
                val currentYear = now.get(Calendar.YEAR)
                val currentMonth = now.get(Calendar.MONTH)
                val currentDay = now.get(Calendar.DAY_OF_MONTH)
                
                var targetYear = currentYear
                
                if (month < currentMonth || (month == currentMonth && day < currentDay)) {
                    targetYear = currentYear + 1
                }
                
                targetTime.set(targetYear, month, day, 0, 0, 0)
                targetTime.set(Calendar.MILLISECOND, 0)
                Log.d(TAG, "匹配到中文月份日期格式: $trimmedStr -> ${targetTime.timeInMillis}")
                return targetTime.timeInMillis
            }
            
            val todayRegex = Regex("今天\\s*(.*)")
            val tonightRegex = Regex("今晚\\s*(.*)")
            val thisMorningRegex = Regex("今早\\s*(.*)")
            val thisAfternoonRegex = Regex("今下午\\s*(.*)")
            val tomorrowRegex = Regex("明天\\s*(.*)")
            val tomorrowNightRegex = Regex("明晚\\s*(.*)")
            val dayAfterTomorrowRegex = Regex("后天\\s*(.*)")
            val thisMonthRegex = Regex("这个月\\s*(\\d+)\\s*[号日]")
            val nextMonthRegex = Regex("下个月\\s*(\\d+)\\s*[号日]")
            val lastWeekRegex = Regex("上周\\s*(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)")
            val nextWeekRegex = Regex("下周\\s*(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)")
            val weekdayRegex = Regex("(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)\\s*(.*)")
            
            val timeRegex = Regex("(上午|下午|晚上|凌晨)?\\s*(\\d+|一|二|三|四|五|六|七|八|九|十|十一|十二|十三|十四|十五|十六|十七|十八|十九|二十|二十一|二十二|二十三)[:：]?(\\d+|零|一|二|三|四|五|六|七|八|九|十|十一|十二|十三|十四|十五|十六|十七|十八|十九|二十|二十一|二十二|二十三|二十四|二十五|二十六|二十七|二十八|二十九|三十|三十一|三十二|三十三|三十四|三十五|三十六|三十七|三十八|三十九|四十|四十一|四十二|四十三|四十四|四十五|四十六|四十七|四十八|四十九|五十|五十一|五十二|五十三|五十四|五十五|五十六|五十七|五十八|五十九)?")
            
            var timePart = trimmedStr
            var daysToAdd = 0
            var periodPrefix = ""
            var explicitDay: Int? = null
            var explicitMonth: Int? = null
            
            if (todayRegex.matches(trimmedStr)) {
                val match = todayRegex.find(trimmedStr)
                timePart = match?.groupValues?.get(1) ?: trimmedStr
            } else if (tonightRegex.matches(trimmedStr)) {
                val match = tonightRegex.find(trimmedStr)
                timePart = match?.groupValues?.get(1) ?: trimmedStr
                periodPrefix = "晚上"
            } else if (thisMorningRegex.matches(trimmedStr)) {
                val match = thisMorningRegex.find(trimmedStr)
                timePart = match?.groupValues?.get(1) ?: trimmedStr
                periodPrefix = "上午"
            } else if (thisAfternoonRegex.matches(trimmedStr)) {
                val match = thisAfternoonRegex.find(trimmedStr)
                timePart = match?.groupValues?.get(1) ?: trimmedStr
                periodPrefix = "下午"
            } else if (tomorrowRegex.matches(trimmedStr)) {
                val match = tomorrowRegex.find(trimmedStr)
                timePart = match?.groupValues?.get(1) ?: trimmedStr
                daysToAdd = 1
            } else if (tomorrowNightRegex.matches(trimmedStr)) {
                val match = tomorrowNightRegex.find(trimmedStr)
                timePart = match?.groupValues?.get(1) ?: trimmedStr
                daysToAdd = 1
                periodPrefix = "晚上"
            } else if (dayAfterTomorrowRegex.matches(trimmedStr)) {
                val match = dayAfterTomorrowRegex.find(trimmedStr)
                timePart = match?.groupValues?.get(1) ?: trimmedStr
                daysToAdd = 2
            } else if (thisMonthRegex.matches(trimmedStr)) {
                val match = thisMonthRegex.find(trimmedStr)
                explicitDay = match?.groupValues?.get(1)?.toInt()
                explicitMonth = now.get(Calendar.MONTH)
            } else if (nextMonthRegex.matches(trimmedStr)) {
                val match = nextMonthRegex.find(trimmedStr)
                explicitDay = match?.groupValues?.get(1)?.toInt()
                explicitMonth = now.get(Calendar.MONTH) + 1
                if (explicitMonth!! > 11) {
                    explicitMonth = 0
                    targetTime.add(Calendar.YEAR, 1)
                }
            } else if (lastWeekRegex.matches(trimmedStr) || trimmedStr.startsWith("上周")) {
                val match = lastWeekRegex.find(trimmedStr)
                val weekdayStr = if (match != null) {
                    match.groupValues[1]
                } else {
                    val weekdayPattern = Regex("(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)")
                    val weekdayMatch = weekdayPattern.find(trimmedStr)
                    weekdayMatch?.groupValues?.get(1) ?: ""
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
                
                daysToAdd = if (targetWeekday > normalizedCurrentWeekday) {
                    targetWeekday - normalizedCurrentWeekday - 7
                } else if (targetWeekday < normalizedCurrentWeekday) {
                    targetWeekday + 7 - normalizedCurrentWeekday - 7
                } else {
                    -7
                }
            } else if (nextWeekRegex.matches(trimmedStr) || trimmedStr.startsWith("下周")) {
                val match = nextWeekRegex.find(trimmedStr)
                val weekdayStr = if (match != null) {
                    match.groupValues[1]
                } else {
                    val weekdayPattern = Regex("(周一|周二|周三|周四|周五|周六|周日|星期一|星期二|星期三|星期四|星期五|星期六|星期日)")
                    val weekdayMatch = weekdayPattern.find(trimmedStr)
                    weekdayMatch?.groupValues?.get(1) ?: ""
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
                
                daysToAdd = if (targetWeekday > normalizedCurrentWeekday) {
                    targetWeekday - normalizedCurrentWeekday + 7
                } else if (targetWeekday < normalizedCurrentWeekday) {
                    targetWeekday + 7 - normalizedCurrentWeekday + 7
                } else {
                    14
                }
            } else if (weekdayRegex.matches(trimmedStr) && !trimmedStr.contains("上周") && !trimmedStr.contains("下周")) {
                val match = weekdayRegex.find(trimmedStr)
                val weekdayStr = match?.groupValues?.get(1) ?: ""
                timePart = match?.groupValues?.get(2) ?: trimmedStr
                
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
                
                daysToAdd = if (targetWeekday > normalizedCurrentWeekday) {
                    targetWeekday - normalizedCurrentWeekday
                } else if (targetWeekday < normalizedCurrentWeekday) {
                    targetWeekday + 7 - normalizedCurrentWeekday
                } else {
                    7
                }
            }
            
            if (explicitDay != null && explicitMonth != null) {
                targetTime.set(Calendar.MONTH, explicitMonth)
                targetTime.set(Calendar.DAY_OF_MONTH, explicitDay)
                targetTime.set(Calendar.HOUR_OF_DAY, 0)
                targetTime.set(Calendar.MINUTE, 0)
                targetTime.set(Calendar.SECOND, 0)
                targetTime.set(Calendar.MILLISECOND, 0)
                Log.d(TAG, "匹配到显式月份日期: $trimmedStr -> ${targetTime.timeInMillis}")
                return targetTime.timeInMillis
            }
            
            val timeMatch = timeRegex.find(timePart)
            if (timeMatch != null) {
                val period = if (periodPrefix.isNotEmpty()) periodPrefix else timeMatch.groupValues[1]
                val hourStr = timeMatch.groupValues[2]
                val minuteStr = timeMatch.groupValues[3]
                
                val chineseNumberMap = mapOf(
                    "零" to 0, "一" to 1, "二" to 2, "三" to 3, "四" to 4,
                    "五" to 5, "六" to 6, "七" to 7, "八" to 8,
                    "九" to 9, "十" to 10, "十一" to 11, "十二" to 12,
                    "十三" to 13, "十四" to 14, "十五" to 15, "十六" to 16,
                    "十七" to 17, "十八" to 18, "十九" to 19, "二十" to 20,
                    "二十一" to 21, "二十二" to 22, "二十三" to 23, "二十四" to 24,
                    "二十五" to 25, "二十六" to 26, "二十七" to 27, "二十八" to 28,
                    "二十九" to 29, "三十" to 30, "三十一" to 31, "三十二" to 32,
                    "三十三" to 33, "三十四" to 34, "三十五" to 35, "三十六" to 36,
                    "三十七" to 37, "三十八" to 38, "三十九" to 39, "四十" to 40,
                    "四十一" to 41, "四十二" to 42, "四十三" to 43, "四十四" to 44,
                    "四十五" to 45, "四十六" to 46, "四十七" to 47, "四十八" to 48,
                    "四十九" to 49, "五十" to 50, "五十一" to 51, "五十二" to 52,
                    "五十三" to 53, "五十四" to 54, "五十五" to 55, "五十六" to 56,
                    "五十七" to 57, "五十八" to 58, "五十九" to 59
                )
                
                var hour = if (hourStr.matches(Regex("\\d+"))) {
                    hourStr.toInt()
                } else {
                    chineseNumberMap[hourStr] ?: 0
                }
                val minute = if (minuteStr.isNotEmpty()) {
                    if (minuteStr.matches(Regex("\\d+"))) {
                        minuteStr.toInt()
                    } else {
                        chineseNumberMap[minuteStr] ?: 0
                    }
                } else {
                    0
                }
                
                when (period) {
                    "下午" -> if (hour < 12) hour += 12
                    "晚上" -> if (hour < 12) hour += 12
                    "凌晨" -> if (hour >= 12) hour -= 12
                }
                
                if (hour == 12 && period == "上午") {
                    hour = 0
                }
                
                targetTime.set(Calendar.HOUR_OF_DAY, hour)
                targetTime.set(Calendar.MINUTE, minute)
                targetTime.set(Calendar.SECOND, 0)
                targetTime.set(Calendar.MILLISECOND, 0)
                
                targetTime.add(Calendar.DAY_OF_YEAR, daysToAdd)
                
                Log.d(TAG, "匹配到时间格式: $trimmedStr -> ${targetTime.timeInMillis}")
                return targetTime.timeInMillis
            }
            
            if (daysToAdd > 0) {
                targetTime.add(Calendar.DAY_OF_YEAR, daysToAdd)
                targetTime.set(Calendar.HOUR_OF_DAY, 0)
                targetTime.set(Calendar.MINUTE, 0)
                targetTime.set(Calendar.SECOND, 0)
                targetTime.set(Calendar.MILLISECOND, 0)
                Log.d(TAG, "仅匹配到天数: $trimmedStr -> ${targetTime.timeInMillis}")
                return targetTime.timeInMillis
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "备用解析时间失败: ${e.message}", e)
        }
        
        Log.d(TAG, "未能解析时间字符串，返回当前时间: $trimmedStr")
        return now.timeInMillis
    }
    
    // 解析自然语言日期描述为Date对象
    private fun parseNaturalLanguageDate(dateStr: String): java.util.Date? {
        val now = Calendar.getInstance()
        val targetDate = Calendar.getInstance()
        
        try {
            val trimmedStr = dateStr.trim()
            
            // 处理日期格式，如：2025-12-20
            val dateRegex = Regex("(\\d{4})-(\\d{2})-(\\d{2})")
            val dateMatch = dateRegex.find(trimmedStr)
            if (dateMatch != null) {
                val year = dateMatch.groupValues[1].toInt()
                val month = dateMatch.groupValues[2].toInt() - 1
                val day = dateMatch.groupValues[3].toInt()
                
                targetDate.set(year, month, day, 0, 0, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理月份+日期格式，如：七月五号、7月5号、七月5号、7月5日
            val monthDayRegex = Regex("(\\d+)\\s*[月]\\s*(\\d+)\\s*[号日]")
            val monthDayMatch = monthDayRegex.find(trimmedStr)
            if (monthDayMatch != null) {
                val month = monthDayMatch.groupValues[1].toInt() - 1
                val day = monthDayMatch.groupValues[2].toInt()
                
                val currentYear = now.get(Calendar.YEAR)
                val currentMonth = now.get(Calendar.MONTH)
                val currentDay = now.get(Calendar.DAY_OF_MONTH)
                
                var targetYear = currentYear
                
                if (month < currentMonth || (month == currentMonth && day < currentDay)) {
                    targetYear = currentYear + 1
                }
                
                targetDate.set(targetYear, month, day, 0, 0, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理中文月份日期格式，如：七月五号
            val chineseMonthDayRegex = Regex("(一|二|三|四|五|六|七|八|九|十|十一|十二)\\s*月\\s*(\\d+)\\s*[号日]")
            val chineseMonthDayMatch = chineseMonthDayRegex.find(trimmedStr)
            if (chineseMonthDayMatch != null) {
                val monthMap = mapOf(
                    "一" to 1, "二" to 2, "三" to 3, "四" to 4,
                    "五" to 5, "六" to 6, "七" to 7, "八" to 8,
                    "九" to 9, "十" to 10, "十一" to 11, "十二" to 12
                )
                val month = (monthMap[chineseMonthDayMatch.groupValues[1]] ?: 1) - 1
                val day = chineseMonthDayMatch.groupValues[2].toInt()
                
                val currentYear = now.get(Calendar.YEAR)
                val currentMonth = now.get(Calendar.MONTH)
                val currentDay = now.get(Calendar.DAY_OF_MONTH)
                
                var targetYear = currentYear
                
                if (month < currentMonth || (month == currentMonth && day < currentDay)) {
                    targetYear = currentYear + 1
                }
                
                targetDate.set(targetYear, month, day, 0, 0, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理相对日期格式，如：昨天、今天、明天、后天
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
            
            // 处理星期几格式，如：周一、周二等
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
            
            // 处理上周/下周的星期几
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
                
                val daysToAdd = if (targetWeekday > normalizedCurrentWeekday) {
                    targetWeekday - normalizedCurrentWeekday - 7
                } else if (targetWeekday < normalizedCurrentWeekday) {
                    targetWeekday + 7 - normalizedCurrentWeekday - 7
                } else {
                    -7
                }
                
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
                
                val daysToAdd = if (targetWeekday > normalizedCurrentWeekday) {
                    targetWeekday - normalizedCurrentWeekday + 7
                } else if (targetWeekday < normalizedCurrentWeekday) {
                    targetWeekday + 7 - normalizedCurrentWeekday + 7
                } else {
                    14
                }
                
                targetDate.time = now.time
                targetDate.add(Calendar.DAY_OF_YEAR, daysToAdd)
                targetDate.set(Calendar.HOUR_OF_DAY, 0)
                targetDate.set(Calendar.MINUTE, 0)
                targetDate.set(Calendar.SECOND, 0)
                targetDate.set(Calendar.MILLISECOND, 0)
                return targetDate.time
            }
            
            // 处理这个月/下个月
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
            Log.e(TAG, "解析日期失败: ${e.message}", e)
        }
        
        return null
    }
    
    // 判断两个日期是否是同一天
    private fun isSameDay(date1: java.util.Date, date2: java.util.Date): Boolean {
        val cal1 = Calendar.getInstance()
        cal1.time = date1
        
        val cal2 = Calendar.getInstance()
        cal2.time = date2
        
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.MONTH) == cal2.get(Calendar.MONTH) &&
               cal1.get(Calendar.DAY_OF_MONTH) == cal2.get(Calendar.DAY_OF_MONTH)
    }
    
    // 执行预约会议工具
    private fun executeScheduleMeetingTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行预约会议工具，参数: $toolArgs")
        
        try {
            // 检查参数
            if (toolArgs == null) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少参数")
                }
            }
            
            // 检查必需参数
            val requiredParams = listOf("meeting_time", "meeting_theme")
            for (param in requiredParams) {
                if (!toolArgs.has(param)) {
                    return JSONObject().apply {
                        put("success", false)
                        put("error", "缺少必需参数: $param")
                    }
                }
            }
            
            // 获取参数值，如果不提供会议类型，默认使用智云会议
            val meetingType = if (toolArgs.has("meeting_type")) {
                toolArgs.getString("meeting_type")
            } else {
                "智云"
            }
            
            // 自动生成会议号码（0001-9999）
            val meetingNumberManager = MeetingNumberManager.getInstance()
            val meetingNumber = meetingNumberManager.getNextMeetingNumber()
            Log.d(TAG, "自动生成会议号码: $meetingNumber")
            
            val meetingName = if (toolArgs.has("meeting_theme")) {
                toolArgs.optString("meeting_theme", "")
            } else {
                toolArgs.optString("meeting_name", "")
            }
            
            // 处理会议时间，支持自然语言描述和时间戳两种格式
            val meetingTime: Long = try {
                // 尝试直接解析为Long类型（时间戳）
                val timeValue = toolArgs.get("meeting_time")
                if (timeValue is Long) {
                    timeValue
                } else if (timeValue is Int) {
                    timeValue.toLong()
                } else {
                    // 否则视为自然语言描述
                    val timeStr = timeValue.toString()
                    parseNaturalLanguageTime(timeStr)
                }
            } catch (e: Exception) {
                // 如果解析为Long失败，视为自然语言描述
                val timeStr = toolArgs.getString("meeting_time")
                parseNaturalLanguageTime(timeStr)
            }
            
            // 固定提醒时间为会议开始前5分钟，不允许动态修改
            val reminderTime = meetingTime - 5 * 60 * 1000L
            
            val duration = if (toolArgs.has("duration")) {
                toolArgs.getInt("duration")
            } else {
                60
            }
            
            // 创建会议预约（初始 cloudConfId 为空，等待云服务器返回后更新）
            val schedule = MeetingSchedule(
                id = UUID.randomUUID().toString(),
                cloudConfId = "",
                cloudConfNumber = "",
                cloudConfHostUrl = "",
                meetingType = meetingType,
                meetingNumber = meetingNumber,
                meetingTime = meetingTime,
                meetingName = meetingName,
                reminderTime = reminderTime,
                duration = duration,
                isConfirmed = false
            )
            
            // 准备云服务器API调用参数
            val confPassword = toolArgs.optString("conf_password", "").takeIf { it.isNotEmpty() }
            
            val systemTimeZone = java.util.TimeZone.getDefault()
            Log.d(TAG, "executeScheduleMeetingTool - 系统默认时区: ${systemTimeZone.id}, 偏移量: ${systemTimeZone.getOffset(System.currentTimeMillis())}ms")
            
            val currentTime = System.currentTimeMillis()
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            dateFormat.timeZone = systemTimeZone
            Log.d(TAG, "executeScheduleMeetingTool - 当前系统时间: ${dateFormat.format(java.util.Date(currentTime))}")
            
            val utcDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            utcDateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            Log.d(TAG, "executeScheduleMeetingTool - 当前UTC时间: ${utcDateFormat.format(java.util.Date(currentTime))}")
            
            val beijingDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            beijingDateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
            Log.d(TAG, "executeScheduleMeetingTool - 当前北京时间: ${beijingDateFormat.format(java.util.Date(currentTime))}")
            
            Log.d(TAG, "executeScheduleMeetingTool - 解析后的会议时间戳: $meetingTime")
            Log.d(TAG, "  系统时区时间: ${dateFormat.format(java.util.Date(meetingTime))}")
            Log.d(TAG, "  UTC时间: ${utcDateFormat.format(java.util.Date(meetingTime))}")
            Log.d(TAG, "  北京时间: ${beijingDateFormat.format(java.util.Date(meetingTime))}")
            
            // 将时间戳转换为 yyyy-MM-dd HH:mm:ss 格式（使用UTC时区上报给服务器）
            val serverDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            serverDateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val startTime = serverDateFormat.format(java.util.Date(meetingTime))
            Log.d(TAG, "executeScheduleMeetingTool - 发送到云服务器的时间字符串(UTC): $startTime")
            
            // 会议方数，默认1方
            val confParties = toolArgs.optInt("conf_parties", 25)
            
            // 调用云服务器API预约会议
            val cloudResult = McuFun.executeScheduleMeetingOnCloudServer(
                getContext(),
                meetingNumber,
                confPassword,
                startTime,
                duration,
                meetingName,
                confParties
            )
            
            // 解析云服务器响应
            var cloudMeetingId: String? = null
            var cloudSuccess = cloudResult.first
            var cloudMessage = cloudResult.second
            
            if (cloudResult.first) {
                try {
                    val responseJson = org.json.JSONObject(cloudResult.second)
                    val status = responseJson.optInt("status", -1)
                    
                    if (status == 100) {
                        val data = responseJson.optJSONObject("data")
                        if (data != null) {
                            cloudMeetingId = data.optString("confId", "")
                            val cloudConfNumber = data.optString("confNumber", "")
                            val protocolHostStartUrl = data.optString("protocolHostStartUrl", "")
                            if (cloudMeetingId.isNotEmpty()) {
                                Log.d(TAG, "云服务器预约成功，会议ID: $cloudMeetingId, 会议号: $cloudConfNumber, protocolHostStartUrl: $protocolHostStartUrl")
                                
                                // 更新 schedule 的 cloudConfId、cloudConfNumber 和 cloudConfHostUrl
                                val updatedSchedule = schedule.copy(
                                    cloudConfId = cloudMeetingId,
                                    cloudConfNumber = cloudConfNumber,
                                    cloudConfHostUrl = protocolHostStartUrl
                                )
                                
                                // 只有云服务器预约成功后才保存到本地
                                val meetingScheduleManager = MeetingScheduleManager.getInstance()
                                meetingScheduleManager.addMeetingSchedule(updatedSchedule)
                                
                                cloudMessage = "会议预约成功，将在会议开始前3分钟提醒"
                            } else {
                                Log.w(TAG, "云服务器响应中未找到 confId")
                                cloudMessage = "会议预约成功，但未获取到会议ID"
                            }
                        } else {
                            Log.w(TAG, "云服务器响应中未找到 data 字段")
                            cloudMessage = "会议预约成功，但响应格式异常"
                        }
                    } else {
                        cloudSuccess = false
                        val message = responseJson.optString("message", "未知错误")
                        cloudMessage = "云服务器预约失败（状态码: $status）: $message"
                        Log.e(TAG, cloudMessage)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析云服务器响应失败: ${e.message}", e)
                    cloudSuccess = false
                    cloudMessage = "解析云服务器响应失败: ${e.message}"
                }
            }
            
            // 返回结果
            return JSONObject().apply {
                put("success", cloudSuccess)
                put("message", cloudMessage)
                put("meeting_type", meetingType)
                put("meeting_number", meetingNumber)
                put("meeting_time", meetingTime)
                put("meeting_id", cloudMeetingId ?: "")
                put("cloud_result", cloudResult.second)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "执行预约会议工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行预约会议工具失败: ${e.message}")
            }
        }
    }
    
    // 执行取消会议预约工具
    private fun executeCancelMeetingScheduleTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行取消会议预约工具，参数: $toolArgs")
        
        try {
            // 检查参数
            if (toolArgs == null) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少参数")
                }
            }
            
            // 取消会议预约
            val meetingScheduleManager = MeetingScheduleManager.getInstance()
            var cloudMeetingId: String? = null
            var canceled = false
            
            if (toolArgs.has("meeting_id")) {
                // 优先使用 meeting_id 参数（本地ID）取消本地会议
                val localMeetingId = toolArgs.getString("meeting_id")
                val schedule = meetingScheduleManager.getAllMeetingSchedules().find { it.id == localMeetingId }
                if (schedule != null) {
                    cloudMeetingId = schedule.cloudConfId
                    meetingScheduleManager.cancelMeetingSchedule(localMeetingId)
                    canceled = true
                    Log.d(TAG, "通过本地ID取消会议: $localMeetingId, 云服务器ID: $cloudMeetingId")
                } else {
                    return JSONObject().apply {
                        put("success", false)
                        put("error", "未找到匹配的会议预约（本地ID: $localMeetingId）")
                    }
                }
            } else if (toolArgs.has("meeting_number")) {
                // 通过会议号查找会议
                val meetingNumber = toolArgs.getString("meeting_number")
                
                // 检查是否同时提供了会议时间
                if (toolArgs.has("meeting_time")) {
                    val meetingTime = toolArgs.getLong("meeting_time")
                    // 使用会议号和会议时间同时匹配
                    val schedule = meetingScheduleManager.findMeetingScheduleByNumberAndTime(meetingNumber, meetingTime)
                    if (schedule != null) {
                        cloudMeetingId = schedule.cloudConfId
                        meetingScheduleManager.cancelMeetingScheduleByNumberAndTime(meetingNumber, meetingTime)
                        canceled = true
                        Log.d(TAG, "通过会议号和时间取消会议: $meetingNumber, 云服务器ID: $cloudMeetingId")
                    } else {
                        val meetingDate = java.util.Date(meetingTime)
                        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val formattedTime = dateFormat.format(meetingDate)
                        
                        val calendar = java.util.Calendar.getInstance()
                        calendar.time = meetingDate
                        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                        val minute = calendar.get(java.util.Calendar.MINUTE)
                        
                        val timeDescription = when {
                            hour >= 5 && hour < 9 -> "早上${hour}点${if (minute > 0) "${minute}分" else ""}"
                            hour >= 9 && hour < 12 -> "上午${hour}点${if (minute > 0) "${minute}分" else ""}"
                            hour >= 12 && hour < 14 -> "中午${if (hour == 12) "12" else hour}点${if (minute > 0) "${minute}分" else ""}"
                            hour >= 14 && hour < 18 -> "下午${hour - 12}点${if (minute > 0) "${minute}分" else ""}"
                            hour >= 18 && hour < 22 -> "晚上${hour - 12}点${if (minute > 0) "${minute}分" else ""}"
                            else -> "晚上${hour - 12}点${if (minute > 0) "${minute}分" else ""}"
                        }
                        
                        return JSONObject().apply {
                            put("success", false)
                            put("error", "未找到匹配的会议预约（会议号: $meetingNumber, 时间: $formattedTime, 时间描述: $timeDescription）")
                        }
                    }
                } else {
                    // 只通过会议号查找
                    val schedule = meetingScheduleManager.findMeetingScheduleByNumber(meetingNumber)
                    if (schedule != null) {
                        cloudMeetingId = schedule.cloudConfId
                        meetingScheduleManager.cancelMeetingScheduleByNumber(meetingNumber)
                        canceled = true
                        Log.d(TAG, "通过会议号取消会议: $meetingNumber, 云服务器ID: $cloudMeetingId")
                    } else {
                        // 没有找到本地记录，仍然尝试取消
                        meetingScheduleManager.cancelMeetingScheduleByNumber(meetingNumber)
                        canceled = true
                        Log.d(TAG, "通过会议号取消会议（无本地记录）: $meetingNumber")
                    }
                }
            } else {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少meeting_id或meeting_number参数")
                }
            }
            
            // 调用云服务器API取消会议（使用 cloudMeetingId）
            val cloudResult = if (cloudMeetingId != null && cloudMeetingId.isNotEmpty()) {
                McuFun.executeCancelMeetingOnCloudServer(getContext(), cloudMeetingId)
            } else {
                Pair(true, "无云服务器会议ID，跳过云服务器调用")
            }
            
            // 返回结果
            return JSONObject().apply {
                put("success", cloudResult.first && canceled)
                put("message", if (cloudResult.first && canceled) {
                    "会议预约已成功取消"
                } else if (!canceled) {
                    "未找到匹配的会议预约"
                } else {
                    "本地取消成功，但云服务器取消失败：${cloudResult.second}"
                })
                if (cloudMeetingId != null) {
                    put("cloud_meeting_id", cloudMeetingId)
                }
                put("cloud_result", cloudResult.second)
                put("meeting_status", "已取消")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "执行取消会议预约工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行取消会议预约工具失败: ${e.message}")
            }
        }
    }
    
    // 执行批量删除指定时间范围内的会议工具
    private fun executeCancelMeetingsByTimePeriodTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行批量删除会议工具，参数: $toolArgs")
        
        try {
            if (toolArgs == null) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少参数")
                }
            }
            
            val timePeriodStr = toolArgs.optString("time_period", "")
            val dateStr = toolArgs.optString("date", "今天")
            
            if (timePeriodStr.isEmpty()) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少time_period参数")
                }
            }
            
            val meetingScheduleManager = MeetingScheduleManager.getInstance()
            val allSchedules = meetingScheduleManager.getAllMeetingSchedules()
            
            val targetDate = parseNaturalLanguageDate(dateStr) ?: java.util.Date()
            
            val filteredSchedules = allSchedules.filter { schedule ->
                val scheduleDate = java.util.Date(schedule.meetingTime)
                isSameDay(scheduleDate, targetDate) && isInTimePeriod(scheduleDate, timePeriodStr)
            }
            
            if (filteredSchedules.isEmpty()) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "未找到符合时间范围的会议")
                    put("time_period", timePeriodStr)
                    put("date", dateStr)
                }
            }
            
            val canceledMeetings = mutableListOf<JSONObject>()
            val failedMeetings = mutableListOf<JSONObject>()
            
            filteredSchedules.forEach { schedule ->
                try {
                    val cloudMeetingId = schedule.cloudConfId
                    
                    if (cloudMeetingId.isNotEmpty()) {
                        val cloudResult = McuFun.executeCancelMeetingOnCloudServer(getContext(), cloudMeetingId)
                        if (cloudResult.first) {
                            meetingScheduleManager.cancelMeetingSchedule(schedule.id)
                            canceledMeetings.add(JSONObject().apply {
                                put("meeting_id", schedule.id)
                                put("meeting_number", schedule.meetingNumber)
                                put("meeting_theme", schedule.meetingName)
                                put("meeting_time", schedule.meetingTime)
                                put("cloud_meeting_id", cloudMeetingId)
                            })
                        } else {
                            failedMeetings.add(JSONObject().apply {
                                put("meeting_id", schedule.id)
                                put("meeting_number", schedule.meetingNumber)
                                put("meeting_theme", schedule.meetingName)
                                put("error", "云服务器取消失败: ${cloudResult.second}")
                            })
                        }
                    } else {
                        meetingScheduleManager.cancelMeetingSchedule(schedule.id)
                        canceledMeetings.add(JSONObject().apply {
                            put("meeting_id", schedule.id)
                            put("meeting_number", schedule.meetingNumber)
                            put("meeting_theme", schedule.meetingName)
                            put("meeting_time", schedule.meetingTime)
                        })
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "取消会议失败: ${schedule.meetingNumber}, 错误: ${e.message}")
                    failedMeetings.add(JSONObject().apply {
                        put("meeting_id", schedule.id)
                        put("meeting_number", schedule.meetingNumber)
                        put("meeting_theme", schedule.meetingName)
                        put("error", e.message)
                    })
                }
            }
            
            return JSONObject().apply {
                put("success", failedMeetings.isEmpty())
                put("message", if (failedMeetings.isEmpty()) {
                    "已成功取消${canceledMeetings.size}个会议"
                } else {
                    "已取消${canceledMeetings.size}个会议，${failedMeetings.size}个会议取消失败"
                })
                put("canceled_count", canceledMeetings.size)
                put("failed_count", failedMeetings.size)
                put("canceled_meetings", JSONArray().apply {
                    canceledMeetings.forEach { put(it) }
                })
                if (failedMeetings.isNotEmpty()) {
                    put("failed_meetings", JSONArray().apply {
                        failedMeetings.forEach { put(it) }
                    })
                }
                put("time_period", timePeriodStr)
                put("date", dateStr)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "执行批量删除会议工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行批量删除会议工具失败: ${e.message}")
            }
        }
    }
    
    private fun isInTimePeriod(date: java.util.Date, timePeriod: String): Boolean {
        val beijingTimeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
        val calendar = java.util.Calendar.getInstance(beijingTimeZone)
        calendar.time = date
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        
        return when (timePeriod.lowercase()) {
            "今天", "all" -> true
            "全天", "all" -> true
            "全部", "all" -> true
            "早上", "morning" -> hour >= 5 && hour < 9
            "上午", "forenoon" -> hour >= 9 && hour < 12
            "中午", "noon" -> hour >= 12 && hour < 14
            "下午", "afternoon" -> hour >= 14 && hour < 18
            "晚上", "evening" -> hour >= 18 && hour < 22
            "夜里", "night" -> hour >= 22 || hour < 5
            else -> false
        }
    }
    
    // 执行加入会议工具
    private fun executeJoinMeetingTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行加入会议工具，参数: $toolArgs")

        try {
            // 检查必需参数
            if (toolArgs == null) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少参数")
                }
            }

            //1 先查询云平台上的站点会议信息，解析结果得到正在开的会议runConfIds列表和已经预约但未召开的scheduledConfIds列表
            val context = getContext()
            val bizConfApi = BizConfApi(context)
            val currentTime = System.currentTimeMillis()
            val utcDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            utcDateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")

            val beijingDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            beijingDateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")

            val currentFormattedTime = beijingDateFormat.format(java.util.Date(currentTime))
            Log.d(TAG, "当前UTC时间戳: $currentTime, 当前北京时间: $currentFormattedTime")

            val runConfIds = mutableListOf<String>()
            val scheduledConfIds = mutableListOf<String>()

            runBlocking {
                try {
                    val result = bizConfApi.getRoomListAndConfListBySiteSign()
                    if (result.first) {
                        val data = result.second.data
                        data.forEach { room: com.minrray.voicectl.core.mcp.RoomInfo ->
                            room.confList.forEach { conf: com.minrray.voicectl.core.mcp.ConferenceInfo ->
                                try {
                                    val startTime = utcDateFormat.parse(conf.startTime)?.time ?: 0L
                                    val endTime = startTime + conf.duration * 60 * 1000L

                                    val startFormattedTime = beijingDateFormat.format(java.util.Date(startTime))
                                    val endFormattedTime = beijingDateFormat.format(java.util.Date(endTime))

                  /*                  Log.d(TAG, "会议ID=${conf.confId}, 会议号=${conf.confNumber}, 会议名=${conf.confName}")
                                    Log.d(TAG, "  服务器UTC时间字符串: ${conf.startTime}, 解析后UTC时间戳: $startTime, 北京时间: $startFormattedTime")
                                    Log.d(TAG, "  结束UTC时间戳: $endTime, 北京时间: $endFormattedTime, 时长: ${conf.duration}分钟, 状态: ${conf.confStatus}")
                                    Log.d(TAG, "  当前UTC时间戳: $currentTime, 当前北京时间: $currentFormattedTime")
*/
                                    when (conf.confStatus) {
                                        2 -> {
                                            runConfIds.add(conf.confNumber)
                                            if (conf.confId > 0) {
                                                runConfIds.add(conf.confId.toString())
                                            }
                                          //  Log.d(TAG, "  -> 根据confStatus分类为: 正在进行的会议")
                                        }
                                        0 -> {
                                            if (currentTime > endTime) {
                                                //Log.d(TAG, "  -> 根据confStatus=0但时间已过期，分类为: 已过时的会议（已预约但未召开）")
                                            } else {
                                                scheduledConfIds.add(conf.confNumber)
                                                if (conf.confId > 0) {
                                                    scheduledConfIds.add(conf.confId.toString())
                                                }
                                               // Log.d(TAG, "  -> 根据confStatus分类为: 已预约的会议")
                                            }
                                        }
                                        3 -> {
                                           // Log.d(TAG, "  -> 根据confStatus分类为: 已结束的会议")
                                        }
                                        9 -> {
                                           // Log.d(TAG, "  -> 根据confStatus分类为: 已取消的会议")
                                        }
                                        else -> {
                                            if (currentTime >= startTime && currentTime <= endTime) {
                                                runConfIds.add(conf.confNumber)
                                                if (conf.confId > 0) {
                                                    runConfIds.add(conf.confId.toString())
                                                }
                                              //  Log.d(TAG, "  -> 根据时间分类为: 正在进行的会议")
                                            } else if (startTime > currentTime) {
                                                scheduledConfIds.add(conf.confNumber)
                                                if (conf.confId > 0) {
                                                    scheduledConfIds.add(conf.confId.toString())
                                                }
                                               // Log.d(TAG, "  -> 根据时间分类为: 已预约的会议")
                                            } else {
                                               // Log.d(TAG, "  -> 根据时间分类为: 已过时的会议")
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "解析会议时间失败: ${e.message}")
                                }
                            }
                        }
                        Log.d(TAG, "查询站点会议信息成功，正在进行的会议: $runConfIds, 已预约的会议: $scheduledConfIds")
                    } else {
                        Log.w(TAG, "查询站点会议信息失败: ${result.second.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "查询站点会议信息异常: ${e.message}", e)
                }
            }

            //2 检查必需参数，并查询要进入得会议是否是已经预约过的，如果预约过，那么读出schedule.cloudConfId
            val requiredParams = listOf("meeting_number")
            for (param in requiredParams) {
                if (!toolArgs.has(param)) {
                    return JSONObject().apply {
                        put("success", false)
                        put("error", "缺少必需参数: $param")
                    }
                }
            }

            // 获取参数值，如果不提供会议类型，默认使用智云会议
            val meetingType = if (toolArgs.has("meeting_type")) {
                toolArgs.getString("meeting_type")
            } else {
                "智云"
            }
            val meetingNumber = toolArgs.getString("meeting_number")

            Log.d(TAG, "会议类型: $meetingType, 会议号: $meetingNumber")

            // 根据会议号和会议类型查询本地预约记录
            val meetingScheduleManager = MeetingScheduleManager.getInstance()
            var schedule = meetingScheduleManager.findMeetingScheduleByTypeAndNumber(meetingType, meetingNumber)

            // 确定使用的会议号：优先使用云服务器返回的 cloudConfId，否则使用本地会议号
            var canBeHost = false
            var roleJoinConf = 0
            var needUpdateSchedule = false
            val meetingNumberToUse = if (schedule != null && schedule.cloudConfId.isNotEmpty()) {
                Log.d(TAG, "找到本地预约记录，使用云服务器会议ID: ${schedule.cloudConfId}")
                canBeHost = true
                schedule.cloudConfNumber //若有预约则返回预约会议时返回得confId

            } else {
                Log.d(TAG, "未找到本地预约记录或云服务器ID为空，使用会议号: $meetingNumber")
                meetingNumber//非本地预约会议需要主动创建并加入即时会议 或 加入别人创建的会议confId
            }

            //3 如果meetingNumberToUse在云平台正在召开得会议列表里，说明会议已经开了，这说明预约后没有确认参加会议，可能是错过提醒或当时未确认，或者是临时加入别人创建得会议，这时候收到加会命令就什么都不考虑直接加入
            //如果meetingNumberToUse没有在runConfIds里，也不在scheduledConfIds里，说明这可能是需要临时创建并加入得即时会议，要通过云平台预约会议接口isInstantMeeting设置为1 立马在云平台召开会议，再使用返回得confId加入会议
            //如果meetingNumberToUse在scheduledConfIds里，说明这是预约过的会议（不管是本地预约的还是别人预约的），这时候收到加入命令，可能是时间到了要直接加入，也可能是用户直接想提前召开会议，这时候直接取消之前的会议预约（目前智云平台不支持修改预约会议的开始时间），并创建一个即时会议并加入

            var finalMeetingNumber = meetingNumberToUse
            var needCreateInstantMeeting = false

            when {
                runConfIds.contains(meetingNumberToUse) -> {
                    Log.d(TAG, "会议正在召开，直接入会加入")
                    if(canBeHost)
                        roleJoinConf = 1 //主持人模式入会 就立即启动了预约会议
                    else
                        roleJoinConf = 0 //普通入会  会提示会议未真正开始
                }
                scheduledConfIds.contains(meetingNumberToUse) -> {
                    Log.d(TAG, "会议已预约，提前召开会议")
                    if(canBeHost)
                        roleJoinConf = 1 //主持人模式入会 就立即启动了预约会议
                    else
                        roleJoinConf = 0 //普通入会  会提示会议未真正开始

                }
                else -> {
                    Log.d(TAG, "会议未预约或预约会议已召开并在服务端删除，主动创建即时会议")
                    needCreateInstantMeeting = true
                    if(canBeHost)
                    {
                        Log.d(TAG, "本地有预约但服务端查不到，说明已经召开并被服务端删除了，主动创建即时会议")
                        needUpdateSchedule = true
                    }
                    else
                    {
                        Log.d(TAG, "本地未预约过也不是别人在平台约的会，说明是临时召开的会，主动创建即时会议")
                        needUpdateSchedule = false
                    }
                    roleJoinConf = 1
                }
            }

            var errorResult: JSONObject? = null

            if (needCreateInstantMeeting) {
                runBlocking {
                    try {
                        val startTime = utcDateFormat.format(java.util.Date(currentTime))
                        val result = bizConfApi.scheduleMeeting(
                            meetingNumber = meetingNumber,
                            startTime = startTime,
                            duration = 60,
                            confName = "即时会议_$meetingNumber",
                            isInstantMeeting = 1
                        )
                        if (result.first) {
                            try {
                                val responseJson = org.json.JSONObject(result.second)
                                val status = responseJson.optInt("status", -1)
                                if (status == 100) {
                                    val data = responseJson.optJSONObject("data")
                                    if (data != null) {
                                        val cloudMeetingId = data.optString("confId", "")
                                        val cloudConfNumber = data.optString("confNumber", "")
                                        val protocolHostStartUrl = data.optString("protocolHostStartUrl", "")
                                        if (cloudMeetingId.isNotEmpty()) {
                                            finalMeetingNumber = cloudConfNumber
                                            Log.d(TAG, "成功创建即时会议，会议ID: $cloudMeetingId, 会议号: $cloudConfNumber, protocolHostStartUrl: $protocolHostStartUrl")

                                            val meetingScheduleManager = MeetingScheduleManager.getInstance()
                                            val instantSchedule = MeetingSchedule(
                                                id = UUID.randomUUID().toString(),
                                                cloudConfId = cloudMeetingId,
                                                cloudConfNumber = cloudConfNumber,
                                                cloudConfHostUrl = protocolHostStartUrl,
                                                meetingType = meetingType,
                                                meetingNumber = meetingNumber,
                                                meetingTime = currentTime,
                                                meetingName = "即时会议_$meetingNumber",
                                                reminderTime = 0,
                                                duration = 60,
                                                isConfirmed = true,
                                                attendees = emptyList()
                                            )
                                            if(needUpdateSchedule)
                                            {
                                                schedule?.let { meetingScheduleManager.cancelMeetingScheduleByNumber(it.meetingNumber) }
                                            }
                                            meetingScheduleManager.addMeetingSchedule(instantSchedule)

                                            schedule = instantSchedule
                                            Log.d(TAG, "已将即时会议添加到本地预约记录: $cloudMeetingId")
                                        } else {
                                            Log.e(TAG, "创建即时会议失败，返回的cloudMeetingId为空")
                                            errorResult = JSONObject().apply {
                                                put("success", false)
                                                put("error", "创建即时会议失败，返回的会议ID为空")
                                                put("meeting_type", meetingType)
                                                put("meeting_number", meetingNumber)
                                            }
                                        }
                                    } else {
                                        Log.e(TAG, "创建即时会议失败，返回的data为空")
                                        errorResult = JSONObject().apply {
                                            put("success", false)
                                            put("error", "创建即时会议失败，返回的数据为空")
                                            put("meeting_type", meetingType)
                                            put("meeting_number", meetingNumber)
                                        }
                                    }
                                } else {
                                    val message = responseJson.optString("message", "创建即时会议失败")
                                    Log.e(TAG, "创建即时会议失败，状态码: $status, 错误信息: $message")
                                    errorResult = JSONObject().apply {
                                        put("success", false)
                                        put("error", message)
                                        put("meeting_type", meetingType)
                                        put("meeting_number", meetingNumber)
                                        put("status", status)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "解析即时会议响应失败: ${e.message}", e)
                                errorResult = JSONObject().apply {
                                    put("success", false)
                                    put("error", "解析即时会议响应失败: ${e.message}")
                                    put("meeting_type", meetingType)
                                    put("meeting_number", meetingNumber)
                                }
                            }
                        } else {
                            Log.w(TAG, "创建即时会议失败: ${result.second}")
                            val responseJson = org.json.JSONObject(result.second)
                            val status = responseJson.optInt("status", -1)
                            val message = responseJson.optString("message", "创建即时会议失败")
                            Log.e(TAG, "创建即时会议失败，状态码: $status, 错误信息: $message")
                            errorResult = JSONObject().apply {
                                put("success", false)
                                put("error", message)
                                put("meeting_type", meetingType)
                                put("meeting_number", meetingNumber)
                                put("status", status)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "创建即时会议异常: ${e.message}", e)
                        errorResult = JSONObject().apply {
                            put("success", false)
                            put("error", "创建即时会议异常: ${e.message}")
                            put("meeting_type", meetingType)
                            put("meeting_number", meetingNumber)
                        }
                    }
                }

                if (errorResult != null) {
                return errorResult
            }
            }

            // 停止人脸识别，避免与会议软件摄像头冲突
            try {
                com.minrray.voicectl.core.service.FloatingWindowService.sendFaceRecognitionControl("stop")
                Log.d(TAG, "已发送停止人脸识别命令")
                
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.e(TAG, "发送停止人脸识别命令失败: ${e.message}")
            }

            // 关闭bizconfvc.app，防止打开失败
            try {
                forceStopPackage("com.minrray.bizconfvc")
                Log.d(TAG, "已关闭bizconfvc.app")
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.e(TAG, "关闭bizconfvc.app失败: ${e.message}")
            }

            // 根据会议类型启动不同应用
            val result = when {
                // 腾讯会议
                meetingType.contains("腾讯") || meetingType.equals("tencent", ignoreCase = true) -> {
                    Log.d(TAG, "启动腾讯会议加入会议")
                    McuFun.startTencentMeeting(context, finalMeetingNumber)
                }
                // 会畅智云
                meetingType.contains("智云") || meetingType.contains("会畅") || meetingType.equals("bizconf", ignoreCase = true) -> {
                    Log.d(TAG, "启动会畅智云加入会议, roleJoinConf: $roleJoinConf")
                    val protocolHostStartUrl = if (roleJoinConf == 1 && schedule != null && schedule.cloudConfHostUrl.isNotEmpty()) {
                        Log.d(TAG, "使用主持人模式，protocolHostStartUrl: ${schedule.cloudConfHostUrl}")
                        schedule.cloudConfHostUrl
                    } else {
                        Log.d(TAG, "使用普通模式")
                        null
                    }
                    McuFun.startBizConfMeeting(context, finalMeetingNumber, protocolHostStartUrl)
                }
                // 明日会议 (vl.vision.app)
                meetingType.contains("明日") || meetingType.contains("vision", ignoreCase = true) -> {
                    Log.d(TAG, "启动vl.vision.app加入会议")
                    McuFun.startVisionAppMeeting(context, finalMeetingNumber)
                }
                // 钉钉会议
                meetingType.contains("钉钉") || meetingType.contains("dingtalk", ignoreCase = true) -> {
                    Log.d(TAG, "启动钉钉加入会议")
                    McuFun.startDingTalkMeeting(context, finalMeetingNumber)
                }
                // 飞书会议
                meetingType.contains("飞书") || meetingType.contains("feishu", ignoreCase = true) ||
                        meetingType.contains("lark", ignoreCase = true) -> {
                    Log.d(TAG, "启动飞书加入会议")
                    McuFun.startFeishuMeeting(context, finalMeetingNumber)
                }
                // Zoom会议
                meetingType.contains("zoom", ignoreCase = true) -> {
                    Log.d(TAG, "启动Zoom加入会议")
                    McuFun.startZoomMeeting(context, finalMeetingNumber)
                }
                // 其他类型，尝试使用浏览器打开
                else -> {
               /*     Log.d(TAG, "使用浏览器打开会议链接")
                    McuFun.startMeetingInBrowser(context, meetingType, finalMeetingNumber)*/
                    Log.d(TAG, "启动会畅智云加入会议, roleJoinConf: $roleJoinConf")
                    val protocolHostStartUrl = if (roleJoinConf == 1 && schedule != null && schedule.cloudConfHostUrl.isNotEmpty()) {
                        Log.d(TAG, "使用主持人模式，protocolHostStartUrl: ${schedule.cloudConfHostUrl}")
                        schedule.cloudConfHostUrl
                    } else {
                        Log.d(TAG, "使用普通模式")
                        null
                    }
                    McuFun.startBizConfMeeting(context, finalMeetingNumber, protocolHostStartUrl)
                }
            }

            // 如果成功启动应用且是本地预约的会议，更新会议状态为进行中（2）
            if (result.first && schedule != null) {
                meetingScheduleManager.updateMeetingStatus(schedule.cloudConfNumber,2)
                Log.d(TAG, "已更新会议状态为进行中: ${schedule.meetingNumber}")
            }

            // 入会成功，设置会议状态标志
            if (result.first) {
                com.minrray.voicectl.core.service.FloatingWindowService.setInMeeting(true)
                Log.d(TAG, "入会成功，设置 isInMeeting = true")
            }

            // 返回结果
            return JSONObject().apply {
                put("success", result.first)
                put("message", result.second)
                put("meeting_type", meetingType)
                put("meeting_number", meetingNumber)
                put("final_meeting_number", finalMeetingNumber)
                put("created_instant_meeting", needCreateInstantMeeting)
                if (!result.first) {
                    put("error", result.second)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "执行加入会议工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行加入会议工具失败: ${e.message}")
            }
        }
    }
    
    // 执行退出会议工具
    private fun executeExitMeetingTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行退出会议工具")
        
        try {
            val context = getContext()
            
            // 定义会议应用包名映射
            val meetingAppPackages = mapOf(
                "腾讯" to "com.tencent.mm",
                "tencent" to "com.tencent.mm",
                "智云" to "cn.bizconf.bizconfvc",
                "会畅" to "cn.bizconf.bizconfvc",
                "bizconf" to "cn.bizconf.bizconfvc",
                "明日" to "vl.vision.app",
                "vision" to "vl.vision.app",
                "钉钉" to "com.alibaba.android.rimet",
                "dingtalk" to "com.alibaba.android.rimet",
                "飞书" to "com.ss.android.lark",
                "feishu" to "com.ss.android.lark",
                "lark" to "com.ss.android.lark",
                "zoom" to "us.zoom.videomeetings"
            )
            
            // 查找当前正在进行的会议（confStatus=2）
            val meetingScheduleManager = MeetingScheduleManager.getInstance()
            val currentMeeting = meetingScheduleManager.findMeetingScheduleByStatus(2)
            
            var killedApps = mutableListOf<String>()
            
            // 如果找到当前会议，尝试杀死对应的应用
            if (currentMeeting != null) {
                Log.d(TAG, "找到当前会议: ${currentMeeting.meetingType}, 会议号: ${currentMeeting.meetingNumber}, 状态: 进行中")
                
                // 根据会议类型查找对应的包名
                for ((key, packageName) in meetingAppPackages) {
                    if (currentMeeting.meetingType.contains(key, ignoreCase = true)) {
                        if (forceStopPackage(packageName)) {
                            killedApps.add(packageName)
                        }
                        break
                    }
                }
                
                // 调用云服务器API退出会议
                val cloudResult = if (currentMeeting.cloudConfId.isNotEmpty()) {
                    McuFun.executeExitMeetingOnCloudServer(context, currentMeeting.cloudConfId)
                } else {
                    Pair(true, "无云服务器会议ID")
                }
                
                // 更新会议状态为已结束（3）
                meetingScheduleManager.updateMeetingStatus(currentMeeting.cloudConfNumber, 3)
                
                // 在会议结束时启动定时器拉取报告
                if (currentMeeting.cloudConfNumber.isNotEmpty()) {
                    try {
                        Log.d(TAG, "启动会议报告拉取: meetingNumber=${currentMeeting.cloudConfNumber}")
                        MeetingReportManager.getInstance().startFetchingReport(currentMeeting.cloudConfNumber)
                    } catch (e: Exception) {
                        Log.e(TAG, "启动会议报告拉取失败: ${e.message}", e)
                    }
                }

                // 重新启动人脸识别
                try {
                    com.minrray.voicectl.core.service.FloatingWindowService.sendFaceRecognitionControl("start")
                    Log.d(TAG, "已发送启动人脸识别命令")
                } catch (e: Exception) {
                    Log.e(TAG, "发送启动人脸识别命令失败: ${e.message}")
                }

                // 退会成功，设置会议状态标志
                com.minrray.voicectl.core.service.FloatingWindowService.setInMeeting(false)
                Log.d(TAG, "退会成功，设置 isInMeeting = false")

                // 返回结果
                return JSONObject().apply {
                    put("success", cloudResult.first)
                    put("message", if (cloudResult.first) {
                        "已成功退出会议，已关闭${killedApps.size}个会议应用"
                    } else {
                        "本地退出成功，但云服务器退出失败：${cloudResult.second}"
                    })
                    put("killed_apps", killedApps)
                    put("cloud_result", cloudResult.second)
                    put("meeting_type", currentMeeting.meetingType)
                    put("meeting_number", currentMeeting.meetingNumber)
                    put("cloud_meeting_id", currentMeeting.cloudConfId)
                    put("meeting_status", "已结束")
                }
            } else {
                // 未找到进行中的会议，直接杀死所有可能的会议应用
                Log.d(TAG, "未找到进行中的会议，尝试杀死所有可能的会议应用")
                for (packageName in meetingAppPackages.values.distinct()) {
                    if (forceStopPackage(packageName)) {
                        killedApps.add(packageName)
                    }
                }

                // 重新启动人脸识别
                try {
                    com.minrray.voicectl.core.service.FloatingWindowService.sendFaceRecognitionControl("start")
                    Log.d(TAG, "已发送启动人脸识别命令")
                } catch (e: Exception) {
                    Log.e(TAG, "发送启动人脸识别命令失败: ${e.message}")
                }

                // 退会成功，设置会议状态标志
                com.minrray.voicectl.core.service.FloatingWindowService.setInMeeting(false)
                Log.d(TAG, "退会成功（未找到本地会议），设置 isInMeeting = false")

                // 返回结果（不调用云平台结束会议）
                return JSONObject().apply {
                    put("success", true)
                    put("message", "未找到本地会议记录，已关闭${killedApps.size}个会议应用")
                    put("killed_apps", killedApps)
                    put("cloud_result", "未调用云平台结束会议")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "执行退出会议工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行退出会议工具失败: ${e.message}")
            }
        }
    }

    private fun checkRootAccess(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su root echo test")
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
            val success = exitCode == 0 && output.contains("test")
            if (!success) {
                Log.d(TAG, "Root检查失败 - 退出码: $exitCode, 输出: $output, 错误: $errorOutput")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Root检查异常: ${e.message}")
            false
        }
    }

    private fun forceStopPackage(packageName: String): Boolean {
        val context = getContext()
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        
        Log.d(TAG, "========== 开始强制停止应用: $packageName ==========")
        
        val isRooted = checkRootAccess()
        Log.d(TAG, "设备是否已root: $isRooted")
        
        val appInfo = try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (e: Exception) {
            Log.e(TAG, "应用不存在: $packageName")
            return false
        }
        
        val isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val isPrivilegedApp = try {
            val privilegedFlag = android.content.pm.ApplicationInfo::class.java.getDeclaredField("FLAG_PRIVILEGED").getInt(null)
            (appInfo.flags and privilegedFlag) != 0
        } catch (e: Exception) {
            false
        }
        Log.d(TAG, "目标应用信息: $packageName")
        Log.d(TAG, "  - 是否为系统应用: $isSystemApp")
        Log.d(TAG, "  - 是否为特权应用: $isPrivilegedApp")
        Log.d(TAG, "  - UID: ${appInfo.uid}")
        Log.d(TAG, "  - 数据目录: ${appInfo.dataDir}")
        
        val myAppInfo = try {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        
        if (myAppInfo != null) {
            val myIsSystemApp = (myAppInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val myIsPrivilegedApp = try {
                val privilegedFlag = android.content.pm.ApplicationInfo::class.java.getDeclaredField("FLAG_PRIVILEGED").getInt(null)
                (myAppInfo.flags and privilegedFlag) != 0
            } catch (e: Exception) {
                false
            }
            val mySharedUserId = try {
                val sharedUserIdField = android.content.pm.ApplicationInfo::class.java.getDeclaredField("sharedUserId")
                sharedUserIdField.isAccessible = true
                sharedUserIdField.get(myAppInfo) as String?
            } catch (e: Exception) {
                "未知"
            }
            Log.d(TAG, "当前应用信息: ${context.packageName}")
            Log.d(TAG, "  - 是否为系统应用: $myIsSystemApp")
            Log.d(TAG, "  - 是否为特权应用: $myIsPrivilegedApp")
            Log.d(TAG, "  - SharedUserId: $mySharedUserId")
            Log.d(TAG, "  - UID: ${myAppInfo.uid}")
            Log.d(TAG, "  - 数据目录: ${myAppInfo.dataDir}")
            
            if (!myIsSystemApp || !myIsPrivilegedApp) {
                Log.w(TAG, "警告: 当前应用不是系统应用或特权应用，可能无法杀死其他应用！")
            }
            
            val hasForceStopPermission = context.checkCallingOrSelfPermission("android.permission.FORCE_STOP_PACKAGES") == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasKillBackgroundPermission = context.checkCallingOrSelfPermission("android.permission.KILL_BACKGROUND_PROCESSES") == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "当前应用权限:")
            Log.d(TAG, "  - FORCE_STOP_PACKAGES: $hasForceStopPermission")
            Log.d(TAG, "  - KILL_BACKGROUND_PROCESSES: $hasKillBackgroundPermission")
        }
        
        val runningApps = activityManager.runningAppProcesses
        var targetProcess: android.app.ActivityManager.RunningAppProcessInfo? = null
        for (appProcess in runningApps) {
            if (appProcess.processName == packageName) {
                targetProcess = appProcess
                break
            }
        }
        
        if (targetProcess == null) {
            Log.w(TAG, "未找到运行中的进程: $packageName，可能已经停止")
            return true
        }
        
        Log.d(TAG, "找到目标进程: ${targetProcess.processName}, PID: ${targetProcess.pid}")
        Log.d(TAG, "  - UID: ${targetProcess.uid}")
        Log.d(TAG, "  - 重要性: ${targetProcess.importance}")
        Log.d(TAG, "  - 重要性原因: ${targetProcess.importanceReasonCode}")
        Log.d(TAG, "  - 进程名列表: ${targetProcess.processName}")
        Log.d(TAG, "  - 包列表: ${targetProcess.pkgList.joinToString()}")
        
        val methods = mutableListOf<Pair<String, () -> Boolean>>()
        
        methods.add("方法1: am force-stop" to {
            try {
                Log.d(TAG, "尝试方法1: am force-stop $packageName")
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "am force-stop $packageName"))
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                exitCode == 0
            } catch (e: Exception) {
                Log.e(TAG, "  方法1失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法2: ActivityManager.killBackgroundProcesses" to {
            try {
                Log.d(TAG, "尝试方法2: ActivityManager.killBackgroundProcesses")
                activityManager.killBackgroundProcesses(packageName)
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法2失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法3: Process.killProcess" to {
            try {
                Log.d(TAG, "尝试方法3: Process.killProcess PID=${targetProcess!!.pid}")
                Process.killProcess(targetProcess!!.pid)
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法3失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法4: su root am force-stop" to {
            try {
                Log.d(TAG, "尝试方法4: su root am force-stop $packageName")
                val process = Runtime.getRuntime().exec("su root am force-stop $packageName")
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法4失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法5: kill -9" to {
            try {
                Log.d(TAG, "尝试方法5: kill -9 ${targetProcess!!.pid}")
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill -9 ${targetProcess!!.pid}"))
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法5失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法6: su root kill -9" to {
            try {
                Log.d(TAG, "尝试方法6: su root kill -9 ${targetProcess!!.pid}")
                val process = Runtime.getRuntime().exec("su root kill -9 ${targetProcess!!.pid}")
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法6失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法7: su root pkill -9" to {
            try {
                Log.d(TAG, "尝试方法7: su root pkill -9 $packageName")
                val process = Runtime.getRuntime().exec("su root pkill -9 $packageName")
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法7失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法8: su root killall" to {
            try {
                Log.d(TAG, "尝试方法8: su root killall $packageName")
                val process = Runtime.getRuntime().exec("su root killall $packageName")
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法8失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法9: su root killall -9" to {
            try {
                Log.d(TAG, "尝试方法9: su root killall -9 $packageName")
                val process = Runtime.getRuntime().exec("su root killall -9 $packageName")
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法9失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法10: su 0 am force-stop" to {
            try {
                Log.d(TAG, "尝试方法10: su 0 am force-stop $packageName")
                val process = Runtime.getRuntime().exec("su 0 am force-stop $packageName")
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法10失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法11: su 0 kill -9" to {
            try {
                Log.d(TAG, "尝试方法11: su 0 kill -9 ${targetProcess!!.pid}")
                val process = Runtime.getRuntime().exec("su 0 kill -9 ${targetProcess!!.pid}")
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法11失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法12: su am force-stop" to {
            try {
                Log.d(TAG, "尝试方法12: su am force-stop $packageName")
                val process = Runtime.getRuntime().exec("su am force-stop $packageName")
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法12失败: ${e.message}")
                false
            }
        })
        
        methods.add("方法13: su kill -9" to {
            try {
                Log.d(TAG, "尝试方法13: su kill -9 ${targetProcess!!.pid}")
                val process = Runtime.getRuntime().exec("su kill -9 ${targetProcess!!.pid}")
                val exitCode = process.waitFor()
                val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "  退出码: $exitCode")
                if (output.isNotEmpty()) Log.d(TAG, "  输出: $output")
                if (errorOutput.isNotEmpty()) Log.d(TAG, "  错误输出: $errorOutput")
                Thread.sleep(500)
                val stillRunning = activityManager.runningAppProcesses.any { it.processName == packageName }
                Log.d(TAG, "  进程是否仍在运行: $stillRunning")
                !stillRunning
            } catch (e: Exception) {
                Log.e(TAG, "  方法13失败: ${e.message}")
                false
            }
        })
        
        for ((methodName, method) in methods) {
            if (method()) {
                Log.d(TAG, "$methodName 成功")
                Log.d(TAG, "========== 强制停止应用成功: $packageName ==========")
                return true
            }
        }
        
        Log.e(TAG, "========== 所有方法都失败，无法停止应用: $packageName ==========")
        return false
    }

    // 执行确认会议工具
    private fun executeConfirmMeetingTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行确认会议工具，参数: $toolArgs")

        try {
            // 检查参数
            if (toolArgs == null || !toolArgs.has("confirm")) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少confirm参数")
                }
            }

            // 获取参数值
            val confirm = toolArgs.getBoolean("confirm")
            val meetingId: String?
            val meetingNumber: String?
            var meetingTime: Long? = null

            // 更新会议确认状态
            val meetingScheduleManager = MeetingScheduleManager.getInstance()
            if (toolArgs.has("meeting_number")) {
                // 通过会议号确认会议
                meetingNumber = toolArgs.getString("meeting_number")
                meetingId = null
                
                // 检查是否同时提供了会议时间
                if (toolArgs.has("meeting_time")) {
                    meetingTime = toolArgs.getLong("meeting_time")
                    // 使用会议号和会议时间同时匹配
                    val confirmed = meetingScheduleManager.confirmMeetingScheduleByNumberAndTime(meetingNumber, meetingTime, confirm)
                    if (!confirmed) {
                        val meetingDate = java.util.Date(meetingTime)
                        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        val formattedTime = dateFormat.format(meetingDate)
                        
                        val calendar = java.util.Calendar.getInstance()
                        calendar.time = meetingDate
                        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
                        val minute = calendar.get(java.util.Calendar.MINUTE)
                        
                        val timeDescription = when {
                            hour >= 5 && hour < 9 -> "早上${hour}点${if (minute > 0) "${minute}分" else ""}"
                            hour >= 9 && hour < 12 -> "上午${hour}点${if (minute > 0) "${minute}分" else ""}"
                            hour >= 12 && hour < 14 -> "中午${if (hour == 12) "12" else hour}点${if (minute > 0) "${minute}分" else ""}"
                            hour >= 14 && hour < 18 -> "下午${hour - 12}点${if (minute > 0) "${minute}分" else ""}"
                            hour >= 18 && hour < 22 -> "晚上${hour - 12}点${if (minute > 0) "${minute}分" else ""}"
                            else -> "晚上${hour - 12}点${if (minute > 0) "${minute}分" else ""}"
                        }
                        
                        return JSONObject().apply {
                            put("success", false)
                            put("error", "未找到匹配的会议预约（会议号: $meetingNumber, 时间: $formattedTime, 时间描述: $timeDescription）")
                        }
                    }
                } else {
                    // 只通过会议号确认（原有逻辑）
                    meetingScheduleManager.confirmMeetingScheduleByNumber(meetingNumber, confirm)
                }
            } else if (toolArgs.has("meeting_id")) {
                // 通过会议ID确认会议
                meetingId = toolArgs.getString("meeting_id")
                meetingNumber = null
                meetingScheduleManager.confirmMeetingSchedule(meetingId, confirm)
            } else {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少meeting_id或meeting_number参数")
                }
            }

            // 返回结果
            return JSONObject().apply {
                put("success", true)
                put("message", if (confirm) "会议已确认参加" else "会议已标记为不参加")
                if (meetingId != null) {
                    put("meeting_id", meetingId)
                }
                if (meetingNumber != null) {
                    put("meeting_number", meetingNumber)
                }
                if (meetingTime != null) {
                    put("meeting_time", meetingTime)
                }
                put("confirm", confirm)
            }

        } catch (e: Exception) {
            Log.e(TAG, "执行确认会议工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行确认会议工具失败: ${e.message}")
            }
        }
    }

    // 执行修改会议预约工具
    private fun executeUpdateMeetingScheduleTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行修改会议预约工具，参数: $toolArgs")

        try {
            // 检查参数
            if (toolArgs == null) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少参数")
                }
            }

            // 获取参数值
            val meetingTimeStr = toolArgs.optString("meeting_time", "")
            val meetingId: String?
            val meetingNumber: String?

            // 解析新的会议时间（如果提供）
            val newMeetingTime = if (meetingTimeStr.isNotEmpty()) {
                parseNaturalLanguageTime(meetingTimeStr)
            } else {
                0L
            }

            // 获取现有的会议预约
            val meetingScheduleManager = MeetingScheduleManager.getInstance()
            val allSchedules = meetingScheduleManager.getAllMeetingSchedules()
            val schedule = if (toolArgs.has("meeting_number")) {
                // 通过会议号查找
                meetingNumber = toolArgs.getString("meeting_number")
                meetingId = null
                allSchedules.find { it.meetingNumber == meetingNumber }
            } else if (toolArgs.has("meeting_id")) {
                // 通过会议ID查找
                meetingId = toolArgs.getString("meeting_id")
                meetingNumber = null
                allSchedules.find { it.id == meetingId }
            } else {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少meeting_id或meeting_number参数")
                }
            }

            if (schedule == null) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "未找到会议预约")
                }
            }

            // 更新会议预约（仅本地）
            var localUpdateSuccess = false
            if (meetingTimeStr.isNotEmpty()) {
                // 如果提供了新的会议时间，更新本地预约
                if (toolArgs.has("meeting_number")) {
                    // 通过会议号更新
                    meetingScheduleManager.updateMeetingScheduleByNumber(schedule.meetingNumber, newMeetingTime)
                } else {
                    // 通过会议ID更新
                    // 创建更新后的会议预约
                    val updatedSchedule = MeetingSchedule(
                        id = schedule.id,
                        cloudConfId = schedule.cloudConfId,
                        cloudConfNumber = schedule.cloudConfNumber,
                        cloudConfHostUrl = schedule.cloudConfHostUrl,
                        meetingType = schedule.meetingType,
                        meetingNumber = schedule.meetingNumber,
                        meetingTime = newMeetingTime,
                        meetingName = schedule.meetingName,
                        reminderTime = newMeetingTime - 5 * 60 * 1000L, // 新时间前5分钟提醒
                        isConfirmed = schedule.isConfirmed
                    )
                    meetingScheduleManager.updateMeetingSchedule(updatedSchedule)
                }
                localUpdateSuccess = true
            }

            // 调用云服务器API修改会议信息
            // 注意：云服务器API不支持修改会议开始时间、会议时长、会议方数
            val context = getContext()
            
            // 准备修改会议的参数
            val confName = toolArgs.optString("meeting_name", schedule.meetingName ?: "会议_${schedule.meetingNumber}")
            val confPassword = toolArgs.optString("meeting_password", "").takeIf { it.isNotEmpty() }
            val optionJbh = toolArgs.optInt("option_jbh", 0)
            val optionVideoHost = toolArgs.optInt("option_video_host", 0)
            val optionVideoParticipants = toolArgs.optInt("option_video_participants", 0)
            val isTuiliu = if (toolArgs.has("is_tuiliu")) toolArgs.optInt("is_tuiliu") else null
            val streamDiy = toolArgs.optInt("stream_diy", 0)
            val streamUrl = toolArgs.optString("stream_url", "").takeIf { it.isNotEmpty() }
            val streamKey = toolArgs.optString("stream_key", "").takeIf { it.isNotEmpty() }
            val watchUrl = toolArgs.optString("watch_url", "").takeIf { it.isNotEmpty() }
            val channelId = if (toolArgs.has("channel_id")) toolArgs.getLong("channel_id") else null
            val ignoreSpecial = toolArgs.optInt("ignore_special", 0)
            val muteWhenJoining = toolArgs.optInt("mute_when_joining", 0)
            
            // 只有当提供了需要修改的参数时，才调用云服务器API
            val cloudResult = if (toolArgs.has("meeting_name") || 
                                  toolArgs.has("meeting_password") ||
                                  toolArgs.has("option_jbh") ||
                                  toolArgs.has("option_video_host") ||
                                  toolArgs.has("option_video_participants") ||
                                  toolArgs.has("is_tuiliu") ||
                                  toolArgs.has("stream_diy") ||
                                  toolArgs.has("stream_url") ||
                                  toolArgs.has("stream_key") ||
                                  toolArgs.has("watch_url") ||
                                  toolArgs.has("channel_id") ||
                                  toolArgs.has("ignore_special") ||
                                  toolArgs.has("mute_when_joining")) {
                McuFun.executeUpdateMeetingOnCloudServer(
                    context,
                    schedule.cloudConfId,
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
            } else {
                Pair(true, "仅本地更新，未调用云服务器")
            }

            // 返回结果
            return JSONObject().apply {
                put("success", cloudResult.first)
                put("message", if (cloudResult.first) {
                    if (localUpdateSuccess) {
                        "本地和云服务器会议信息已修改"
                    } else {
                        "云服务器会议信息已修改"
                    }
                } else {
                    if (localUpdateSuccess) {
                        "本地更新成功，但云服务器修改失败：${cloudResult.second}"
                    } else {
                        "云服务器修改失败：${cloudResult.second}"
                    }
                })
                if (meetingId != null) {
                    put("meeting_id", meetingId)
                }
                if (meetingNumber != null) {
                    put("meeting_number", meetingNumber)
                }
                if (newMeetingTime > 0) {
                    put("new_meeting_time", newMeetingTime)
                }
                put("cloud_result", cloudResult.second)
            }

        } catch (e: Exception) {
            Log.e(TAG, "执行修改会议预约工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行修改会议预约工具失败: ${e.message}")
            }
        }
    }

    private fun executeGetSiteMeetingInfoTool(toolArgs: JSONObject?): JSONObject {
        return try {
            val siteSign = /*toolArgs?.optString("site_sign", "kexinyun") ?:*/ "kexinyun"
            val pageNo = toolArgs?.optInt("page_no", 1) ?: 1
            val pageSize = toolArgs?.optInt("page_size", 10) ?: 10

            val context = getContext()
            val bizConfApi = BizConfApi(context)

            val result = kotlinx.coroutines.runBlocking {
                bizConfApi.getRoomListAndConfListBySiteSign(siteSign, pageNo, pageSize)
            }

            if (result.first) {
                val response = result.second
                val data = response.data

                val meetingsArray = JSONArray()
                val currentMeetings = JSONArray()
                val upcomingMeetings = JSONArray()
                val pastMeetings = JSONArray()
                val allMeetings = JSONArray()

                val currentTime = System.currentTimeMillis()
                val utcDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                utcDateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")

                val beijingDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                beijingDateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")

                val currentFormattedTime = beijingDateFormat.format(java.util.Date(currentTime))
               // Log.d(TAG, "当前UTC时间戳: $currentTime, 当前北京时间: $currentFormattedTime")

                data class MeetingWithTime(val json: JSONObject, val startTime: Long, val endTime: Long, val confStatus: Int)
                val meetingsWithTime = mutableListOf<MeetingWithTime>()

                data.forEach { room ->
                            room.confList.forEach { conf ->
                                try {
                                    val startTime = utcDateFormat.parse(conf.startTime)?.time ?: 0L
                                    val endTime = startTime + conf.duration * 60 * 1000L

                                    val startFormattedTime = beijingDateFormat.format(java.util.Date(startTime))
                                    val endFormattedTime = beijingDateFormat.format(java.util.Date(endTime))

                                    val meetingJson = JSONObject().apply {
                                        put("conf_id", conf.confId)
                                        put("conf_name", conf.confName)
                                        put("conf_number", conf.confNumber)
                                        put("duration", conf.duration)
                                        put("conf_status", conf.confStatus)
                                        put("start_time", conf.startTime)
                                        put("start_time_beijing", startFormattedTime)
                                        put("end_time_beijing", endFormattedTime)
                                        put("start_time_timestamp", startTime)
                                        put("end_time_timestamp", endTime)
                                        put("room_id", room.roomId)
                                        put("room_parties", room.roomParties)
                                    }

                                    meetingsWithTime.add(MeetingWithTime(meetingJson, startTime, endTime, conf.confStatus))
                                } catch (e: Exception) {
                                    Log.e(TAG, "解析会议时间失败: ${e.message}, 会议时间: ${conf.startTime}")
                                }
                            }
                        }

                meetingsWithTime.sortBy { it.startTime }

                meetingsWithTime.forEach { meeting ->
                    val meetingJson = meeting.json
                    val startTime = meeting.startTime
                    val endTime = meeting.endTime
                    val confStatus = meeting.confStatus

                    allMeetings.put(meetingJson)

                    try {
                        val startFormattedTime = beijingDateFormat.format(java.util.Date(startTime))
                        val endFormattedTime = beijingDateFormat.format(java.util.Date(endTime))

    /*                    Log.d(TAG, "会议: ${meetingJson.optString("conf_name")}, 会议ID: ${meetingJson.optInt("conf_id")}, 会议号: ${meetingJson.optString("conf_number")}")
                        Log.d(TAG, "  服务器UTC时间字符串: ${meetingJson.optString("start_time")}, 解析后UTC时间戳: $startTime, 北京时间: $startFormattedTime")
                        Log.d(TAG, "  结束UTC时间戳: $endTime, 北京时间: $endFormattedTime, 时长: ${meetingJson.optInt("duration")}分钟, 状态: $confStatus")
                        Log.d(TAG, "  当前UTC时间戳: $currentTime, 当前北京时间: $currentFormattedTime")
*/
                        when (confStatus) {
                            2 -> {
                                currentMeetings.put(meetingJson)
                               // Log.d(TAG, "  -> 根据confStatus分类为: 正在进行的会议")
                            }
                            0 -> {
                                if (currentTime > endTime) {
                                    pastMeetings.put(meetingJson)
                                 //   Log.d(TAG, "  -> 根据confStatus=0但时间已过期，分类为: 已过时的会议（已预约但未召开）")
                                } else {
                                    upcomingMeetings.put(meetingJson)
                                 //   Log.d(TAG, "  -> 根据confStatus分类为: 即将开始的会议（已预约）")
                                }
                            }
                            3 -> {
                                pastMeetings.put(meetingJson)
                               // Log.d(TAG, "  -> 根据confStatus分类为: 已过时的会议（已结束）")
                            }
                            9 -> {
                                pastMeetings.put(meetingJson)
                               // Log.d(TAG, "  -> 根据confStatus分类为: 已取消的会议")
                            }
                            else -> {
                                if (currentTime >= startTime && currentTime <= endTime) {
                                    currentMeetings.put(meetingJson)
                                  //  Log.d(TAG, "  -> 根据时间分类为: 正在进行的会议")
                                } else if (startTime > currentTime) {
                                    upcomingMeetings.put(meetingJson)
                                  //  Log.d(TAG, "  -> 根据时间分类为: 即将开始的会议")
                                } else {
                                    pastMeetings.put(meetingJson)
                                  //  Log.d(TAG, "  -> 根据时间分类为: 已过时的会议")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "处理会议信息失败: ${e.message}")
                    }
                }

                val recommendation = generateMeetingRecommendation(currentMeetings, upcomingMeetings)

                // 发送广播到 FloatingWindowService 显示会议查询结果
                try {
                    val intent = Intent("com.minrray.voicectl.MEETING_RESULT").apply {
                        setPackage(context.packageName)
                        putExtra("current_meetings", currentMeetings.toString())
                        putExtra("upcoming_meetings", upcomingMeetings.toString())
                        putExtra("past_meetings", pastMeetings.toString())
                        putExtra("recommendation", recommendation)
                    }
                    context.sendBroadcast(intent)
                    Log.d(TAG, "已发送会议查询结果广播到 FloatingWindowService")
                } catch (e: Exception) {
                    Log.e(TAG, "发送会议查询结果广播失败: ${e.message}")
                }

                return JSONObject().apply {
                    put("success", true)
                    put("message", "已成功获取站点会议信息")
                    put("site_sign", siteSign)
                    put("total_rooms", data.size)
                    put("total_meetings", allMeetings.length())
                    put("current_page", response.currentPage)
                    put("total_page", response.totalPage)
                    put("total_items", response.totalItems)
                    put("page_size", response.pageSize)
                    put("current_meetings", currentMeetings)
                    put("upcoming_meetings", upcomingMeetings)
                    put("past_meetings", pastMeetings)
                    put("all_meetings", allMeetings)
                    put("recommendation", recommendation)
                }
            } else {
                return JSONObject().apply {
                    put("success", false)
                    put("error", result.second.message)
                    put("status", result.second.status)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行查询站点会议信息工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行查询站点会议信息工具失败: ${e.message}")
            }
        }
    }

    private fun executeGetMeetingReportTool(toolArgs: JSONObject?): JSONObject {
        return try {
            val confNo = toolArgs?.optString("conf_no", "") ?: ""

            if (confNo.isEmpty()) {
                Log.e(TAG, "会议号不能为空")
                return JSONObject().apply {
                    put("success", false)
                    put("error", "会议号不能为空")
                }
            }

            Log.d(TAG, "执行获取会议报告工具: conf_no=$confNo")

            val context = getContext()
            val bizConfApi = BizConfApi(context)
            
            val meetingScheduleManager = com.minrray.voicectl.core.meeting.MeetingScheduleManager.getInstance()
            
            val cloudConfNo = meetingScheduleManager.findMeetingScheduleByNumber(confNo)?.cloudConfNumber ?: confNo
            
            if (cloudConfNo != confNo) {
                Log.d(TAG, "找到本地会议记录，使用云会议号查询: $cloudConfNo")
            }

            val result = kotlinx.coroutines.runBlocking {
                bizConfApi.getContentFileList(cloudConfNo)
            }

            if (result.first) {
                val response = result.second
                val data = response.data

                if (data != null && data.datas.isNotEmpty()) {
                    val reportData = data.datas[0]
                    val filesArray = JSONArray()
                    reportData.files.forEach { file ->
                        val fileJson = JSONObject().apply {
                            put("download_url", file.downloadUrl)
                            put("file_size", file.fileSize)
                            put("file_type", file.fileType)
                            put("record_type", file.recordType)
                        }
                        filesArray.put(fileJson)
                    }

                    val utcDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    utcDateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")

                    val beijingDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    beijingDateFormat.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")

                    val recordStartTimestamp = try {
                        utcDateFormat.parse(reportData.recordStart)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }

                    val recordEndTimestamp = try {
                        utcDateFormat.parse(reportData.recordEnd)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }

                    val recordStartBeijing = if (recordStartTimestamp > 0) {
                        beijingDateFormat.format(java.util.Date(recordStartTimestamp))
                    } else {
                        reportData.recordStart
                    }

                    val recordEndBeijing = if (recordEndTimestamp > 0) {
                        beijingDateFormat.format(java.util.Date(recordEndTimestamp))
                    } else {
                        reportData.recordEnd
                    }

                    return JSONObject().apply {
                        put("success", true)
                        put("message", "已成功获取会议报告")
                        put("conf_no", reportData.confNo)
                        put("topic", reportData.topic)
                        put("record_start", reportData.recordStart)
                        put("record_end", reportData.recordEnd)
                        put("record_start_beijing", recordStartBeijing)
                        put("record_end_beijing", recordEndBeijing)
                        put("record_start_timestamp", recordStartTimestamp)
                        put("record_end_timestamp", recordEndTimestamp)
                        put("files", filesArray)
                    }
                } else {
                    return JSONObject().apply {
                        put("success", false)
                        put("error", "会议报告数据为空")
                    }
                }
            } else {
                return JSONObject().apply {
                    put("success", false)
                    put("error", result.second.message)
                    put("status", result.second.status)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行获取会议报告工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行获取会议报告工具失败: ${e.message}")
            }
        }
    }

    private fun generateMeetingRecommendation(currentMeetings: JSONArray, upcomingMeetings: JSONArray): String {
        val recommendations = mutableListOf<String>()

        if (currentMeetings.length() > 0) {
            recommendations.add("当前有 ${currentMeetings.length()} 个会议正在进行中")
            for (i in 0 until minOf(currentMeetings.length(), 3)) {
                val meeting = currentMeetings.getJSONObject(i)
                val confName = meeting.optString("conf_name", "未命名会议")
                val confNumber = meeting.optString("conf_number", "")
                recommendations.add("  - $confName (会议号: $confNumber)")
            }
        }

        if (upcomingMeetings.length() > 0) {
            recommendations.add("即将开始 ${upcomingMeetings.length()} 个会议")
            for (i in 0 until minOf(upcomingMeetings.length(), 3)) {
                val meeting = upcomingMeetings.getJSONObject(i)
                val confName = meeting.optString("conf_name", "未命名会议")
                val confNumber = meeting.optString("conf_number", "")
                val startTimeBeijing = meeting.optString("start_time_beijing", "")
                recommendations.add("  - $confName (会议号: $confNumber, 开始时间: $startTimeBeijing)")
            }
        }

        if (recommendations.isEmpty()) {
            return "当前没有正在进行的会议，也没有即将开始的会议"
        }

        return recommendations.joinToString("\n")
    }

    private fun executeFaceRegisterTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行人脸注册工具，参数: $toolArgs")
        
        try {
            val identityId = toolArgs?.optString("identity_id", "") ?: ""
            val rolePermission = toolArgs?.optString("role_permission", "master") ?: "master"
            val name = toolArgs?.optString("name", "") ?: ""
            
            if (name.isEmpty()) {
                Log.d(TAG, "缺少姓名参数，需要询问用户")
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少必要参数：name")
                    put("need_ask", true)
                    put("ask_message", "请问您的姓名是什么？我需要知道您的姓名才能完成人脸注册。")
                }
            }
            
            val context = getContext()
            val faceRecognitionManager = com.minrray.voicectl.core.face.FaceRecognitionManager.getInstance(context)
            val speakerIdentificationManager = com.minrray.voicectl.core.speaker.SpeakerIdentificationManager.getInstance(context)
            
            Log.d(TAG, "FaceRecognitionManager instance: $faceRecognitionManager")
            Log.d(TAG, "FaceRecognitionManager initialized: ${faceRecognitionManager.isInitialized()}")
            Log.d(TAG, "SpeakerIdentificationManager instance: $speakerIdentificationManager")
            Log.d(TAG, "SpeakerIdentificationManager ready: ${speakerIdentificationManager.isReady()}")
            
            val result = JSONObject()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            
            val role = if (rolePermission == "master") 0 else 1
            Log.d(TAG, "转换角色权限: $rolePermission -> $role (0=master, 1=user)")
            
            val finalIdentityId = if (identityId.isEmpty()) {
                name.toByteArray().joinToString("") { "%02x".format(it) }.take(8)
            } else {
                identityId
            }
            Log.d(TAG, "使用身份ID: $finalIdentityId")

            val faceDatabaseSize = faceRecognitionManager.getDatabaseSize()

            val permissionMessage = if (role == 0) {
                "${name}已设置为master权限，识别到该用户时会自动触发唤醒词问候。"
            } else {
                "${name}已设置为普通用户权限。"
            }

            var faceRegisterSuccess = false
            var faceRegisterMessage = ""

            faceRecognitionManager.enterRegisterMode(name, role, object : com.minrray.voicectl.core.face.FaceRegisterListener {
                override fun onFaceRegisterSuccess(faceName: String) {
                    Log.d(TAG, "人脸注册成功: $faceName")
                    faceRegisterSuccess = true
                    faceRegisterMessage = "人脸注册成功！${faceName}已成功注册。$permissionMessage"

                    Log.d(TAG, "发送人脸注册成功通知到服务端")
                    result.put("success", true)
                    result.put("message", faceRegisterMessage)
                    result.put("identity_id", finalIdentityId)
                    result.put("name", faceName)
                    result.put("role_permission", rolePermission)
                    result.put("role", role)
                    result.put("database_size", faceDatabaseSize)
                    
                    handler.post {
                        sendToolResult(result)
                        WebSocketHandler.getInstance().updateFaceRegistrationStatus(true)
                        WebSocketHandler.getInstance().sendListenModeUpdate()
                    }
                }
                
                override fun onFaceRegisterFailed(reason: String) {
                    Log.d(TAG, "人脸注册失败: $reason")
                    
                    result.put("success", false)
                    result.put("error", reason)
                    result.put("message", reason)
                    
                    handler.post {
                        sendToolResult(result)
                    }
                }
            })
            
            return JSONObject().apply {
                put("success", true)
                put("message", "人脸注册已成功，即将进行声纹注册")
                put("status", "registered")
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行人脸注册工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行人脸注册工具失败: ${e.message}")
                put("message", "人脸注册失败，请正对镜头并离近一点")
            }
        }
    }
    
    private fun executeResetUserInfoTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行重置用户信息工具，参数: $toolArgs")
        
        try {
            val confirm = toolArgs?.optBoolean("confirm", false) ?: false
            
            if (!confirm) {
                Log.d(TAG, "用户未确认重置操作")
                return JSONObject().apply {
                    put("success", false)
                    put("need_confirm", true)
                    put("message", "确定要重置用户信息吗？这将删除所有人脸和声纹数据。")
                }
            }
            
            val context = getContext()
            val faceRecognitionManager = com.minrray.voicectl.core.face.FaceRecognitionManager.getInstance(context)
            val speakerIdentificationManager = com.minrray.voicectl.core.speaker.SpeakerIdentificationManager.getInstance(context)
            
            Log.d(TAG, "FaceRecognitionManager instance: $faceRecognitionManager")
            Log.d(TAG, "FaceRecognitionManager initialized: ${faceRecognitionManager.isInitialized()}")
            Log.d(TAG, "SpeakerIdentificationManager instance: $speakerIdentificationManager")
            Log.d(TAG, "SpeakerIdentificationManager ready: ${speakerIdentificationManager.isReady()}")
            
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val result = JSONObject()
            
            val databaseSize = faceRecognitionManager.getDatabaseSize()
            val speakerCount = speakerIdentificationManager.getSpeakerCount()
            Log.d(TAG, "当前人脸数据库大小: $databaseSize")
            Log.d(TAG, "当前声纹数量: $speakerCount")
            
            faceRecognitionManager.clearDatabase()
            Log.d(TAG, "人脸数据库已清空")
            
            if (speakerIdentificationManager.isReady()) {
                speakerIdentificationManager.clearAllSpeakers()
                Log.d(TAG, "声纹数据库已清空")
            }
            
            result.put("success", true)
            result.put("message", "用户信息重置成功！已删除 $databaseSize 个人脸数据和 $speakerCount 个声纹数据，系统正在重启...")
            result.put("deleted_face_count", databaseSize)
            result.put("deleted_speaker_count", speakerCount)
            
            Log.d(TAG, "准备发送工具结果")
            sendToolResult(result)
            Log.d(TAG, "工具结果已发送")
            
            handler.post {
                WebSocketHandler.getInstance().updateFaceRegistrationStatus(false)
                
                Log.d(TAG, "延迟3秒后重启系统，确保消息已发送到服务端")
                
                handler.postDelayed({
                    Log.d(TAG, "开始重启系统")
                    
                    com.minrray.voicectl.core.face.FaceRecognitionManager.resetInstance()
                    com.minrray.voicectl.core.speaker.SpeakerIdentificationManager.resetInstance()
                    
                    val intent = android.content.Intent(context, com.minrray.voicectl.MainActivity::class.java)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(intent)
                    
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 5000)
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "执行重置用户信息工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行重置用户信息工具失败: ${e.message}")
                put("message", "重置用户信息失败，请重试")
            }
        }
    }
    
    private fun sendToolResult(result: JSONObject) {
        Log.d(TAG, "发送工具结果: $result")
        
        try {
            val wsHandler = WebSocketHandler.getInstance()
            
            if (currentSessionId.isNotEmpty() && currentMcpCallId.isNotEmpty()) {
                val contentArray = org.json.JSONArray()
                val textContent = JSONObject().apply {
                    put("type", "text")
                    put("text", result.toString())
                }
                contentArray.put(textContent)

                val replyMessage = JSONObject().apply {
                    put("session_id", currentSessionId)
                    put("type", "mcp")
                    put("payload", JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", currentMcpCallId)
                        put("result", JSONObject().apply {
                            put("content", contentArray)
                            put("isError", false)
                        })
                    })
                }

                Log.d(TAG, "发送异步工具结果到服务器: $replyMessage")
                wsHandler.sendTextMessage(replyMessage.toString())
                
                // 清除当前调用信息
                currentSessionId = ""
                currentMcpCallId = ""
            } else {
                Log.w(TAG, "没有保存的 sessionId 或 mcpCallId，无法发送工具结果")
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送工具结果失败: ${e.message}", e)
        }
    }
    
    fun setCurrentMcpCallInfo(sessionId: String, mcpCallId: String) {
        currentSessionId = sessionId
        currentMcpCallId = mcpCallId
        Log.d(TAG, "设置 MCP 调用信息: sessionId=$sessionId, mcpCallId=$mcpCallId")
    }
    
    private fun executeWeatherQueryTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行天气查询工具，参数: $toolArgs")
        
        try {
            val dateStr = toolArgs?.optString("date", "") ?: ""
            
            val context = getContext()
            val weatherManager = com.minrray.voicectl.core.weather.WeatherManager.getInstance(context)
            
            if (!weatherManager.isConfigured()) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "天气服务未配置")
                    put("message", "天气服务未配置，请先配置和风天气API")
                }
            }
            
            val targetDate = if (dateStr.isNotEmpty()) {
                parseNaturalLanguageDate(dateStr) ?: java.util.Date()
            } else {
                java.util.Date()
            }
            
            val today = java.util.Date()
            val isToday = isSameDay(today, targetDate)
            
            val result = JSONObject()
            
            if (isToday) {
                val currentWeather = weatherManager.getCurrentWeather()
                if (currentWeather != null) {
                    result.put("success", true)
                    result.put("date", dateStr.ifEmpty { "今天" })
                    result.put("formatted_date", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(targetDate))
                    result.put("weather", currentWeather.text)
                    result.put("temperature", currentWeather.temp)
                    result.put("feels_like", currentWeather.feelsLike)
                    result.put("humidity", currentWeather.humidity)
                    result.put("wind_direction", currentWeather.windDir)
                    result.put("wind_scale", currentWeather.windScale)
                    result.put("wind_speed", currentWeather.windSpeed)
                    result.put("pressure", currentWeather.pressure)
                    result.put("visibility", currentWeather.vis)
                    result.put("icon", currentWeather.icon)
                    
                    val weatherText = "${currentWeather.text} ${currentWeather.temp}°C 湿度${currentWeather.humidity}% ${currentWeather.windDir}${currentWeather.windScale}级"
                    result.put("message", weatherText)
                    
                    Log.d(TAG, "天气查询成功: $weatherText")
                } else {
                    weatherManager.refreshWeather()
                    Thread.sleep(2000)
                    
                    val refreshedWeather = weatherManager.getCurrentWeather()
                    if (refreshedWeather != null) {
                        result.put("success", true)
                        result.put("date", dateStr.ifEmpty { "今天" })
                        result.put("formatted_date", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(targetDate))
                        result.put("weather", refreshedWeather.text)
                        result.put("temperature", refreshedWeather.temp)
                        result.put("feels_like", refreshedWeather.feelsLike)
                        result.put("humidity", refreshedWeather.humidity)
                        result.put("wind_direction", refreshedWeather.windDir)
                        result.put("wind_scale", refreshedWeather.windScale)
                        result.put("wind_speed", refreshedWeather.windSpeed)
                        result.put("pressure", refreshedWeather.pressure)
                        result.put("visibility", refreshedWeather.vis)
                        result.put("icon", refreshedWeather.icon)
                        
                        val weatherText = "${refreshedWeather.text} ${refreshedWeather.temp}°C 湿度${refreshedWeather.humidity}% ${refreshedWeather.windDir}${refreshedWeather.windScale}级"
                        result.put("message", weatherText)
                        
                        Log.d(TAG, "天气查询成功（刷新后）: $weatherText")
                    } else {
                        result.put("success", false)
                        result.put("error", "无法获取天气数据")
                        result.put("message", "无法获取天气数据，请稍后再试")
                    }
                }
            } else {
                val dailyWeather = weatherManager.getDailyWeather()
                if (dailyWeather != null && dailyWeather.isNotEmpty()) {
                    val targetDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(targetDate)
                    val targetWeather = dailyWeather.find { it.fxDate == targetDateStr }
                    
                    if (targetWeather != null) {
                        result.put("success", true)
                        result.put("date", dateStr)
                        result.put("formatted_date", targetDateStr)
                        result.put("weather_day", targetWeather.textDay)
                        result.put("weather_night", targetWeather.textNight)
                        result.put("temperature_max", targetWeather.tempMax)
                        result.put("temperature_min", targetWeather.tempMin)
                        result.put("humidity", targetWeather.humidity)
                        result.put("wind_direction_day", targetWeather.windDirDay)
                        result.put("wind_scale_day", targetWeather.windScaleDay)
                        result.put("wind_speed_day", targetWeather.windSpeedDay)
                        result.put("pressure", targetWeather.pressure)
                        result.put("visibility", targetWeather.vis)
                        result.put("icon_day", targetWeather.iconDay)
                        result.put("icon_night", targetWeather.iconNight)
                        
                        val weatherText = "${targetWeather.textDay} ${targetWeather.tempMin}-${targetWeather.tempMax}°C 湿度${targetWeather.humidity}% ${targetWeather.windDirDay}${targetWeather.windScaleDay}级"
                        result.put("message", weatherText)
                        
                        Log.d(TAG, "天气查询成功（未来日期）: $weatherText")
                    } else {
                        result.put("success", false)
                        result.put("error", "未找到指定日期的天气数据")
                        result.put("message", "未找到${dateStr}的天气数据，仅支持查询未来7天的天气")
                    }
                } else {
                    weatherManager.refreshWeather()
                    Thread.sleep(2000)
                    
                    val refreshedDailyWeather = weatherManager.getDailyWeather()
                    if (refreshedDailyWeather != null && refreshedDailyWeather.isNotEmpty()) {
                        val targetDateStr = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(targetDate)
                        val targetWeather = refreshedDailyWeather.find { it.fxDate == targetDateStr }
                        
                        if (targetWeather != null) {
                            result.put("success", true)
                            result.put("date", dateStr)
                            result.put("formatted_date", targetDateStr)
                            result.put("weather_day", targetWeather.textDay)
                            result.put("weather_night", targetWeather.textNight)
                            result.put("temperature_max", targetWeather.tempMax)
                            result.put("temperature_min", targetWeather.tempMin)
                            result.put("humidity", targetWeather.humidity)
                            result.put("wind_direction_day", targetWeather.windDirDay)
                            result.put("wind_scale_day", targetWeather.windScaleDay)
                            result.put("wind_speed_day", targetWeather.windSpeedDay)
                            result.put("pressure", targetWeather.pressure)
                            result.put("visibility", targetWeather.vis)
                            result.put("icon_day", targetWeather.iconDay)
                            result.put("icon_night", targetWeather.iconNight)
                            
                            val weatherText = "${targetWeather.textDay} ${targetWeather.tempMin}-${targetWeather.tempMax}°C 湿度${targetWeather.humidity}% ${targetWeather.windDirDay}${targetWeather.windScaleDay}级"
                            result.put("message", weatherText)
                            
                            Log.d(TAG, "天气查询成功（未来日期，刷新后）: $weatherText")
                        } else {
                            result.put("success", false)
                            result.put("error", "未找到指定日期的天气数据")
                            result.put("message", "未找到${dateStr}的天气数据，仅支持查询未来7天的天气")
                        }
                    } else {
                        result.put("success", false)
                        result.put("error", "无法获取天气数据")
                        result.put("message", "无法获取天气数据，请稍后再试")
                    }
                }
            }
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "执行天气查询工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行天气查询工具失败: ${e.message}")
                put("message", "天气查询失败，请稍后再试")
            }
        }
    }
    
    // 执行设置终端模式工具
    private fun executeSetTerminalModeTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行设置终端模式工具，参数: $toolArgs")
        
        try {
            val mode = toolArgs?.optString("mode", "")
            
            if (mode.isNullOrEmpty()) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少mode参数")
                }
            }
            
            if (mode != "sleep" && mode != "work") {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "mode参数必须是'sleep'或'work'")
                }
            }
            
            val service = com.minrray.voicectl.core.service.FloatingWindowService.getInstance()
            if (service == null) {
                Log.e(TAG, "FloatingWindowService实例为空")
                return JSONObject().apply {
                    put("success", false)
                    put("error", "FloatingWindowService未运行")
                }
            }
            
            // 客户端设置自己的工作模式，并返回实际设置的结果
            val actualMode = service.setTerminalMode(mode)
            
            Log.d(TAG, "客户端设置终端模式完成: 请求模式=$mode, 实际设置模式=$actualMode")
            
            return JSONObject().apply {
                put("success", true)
                put("mode", actualMode)
                put("message", if (actualMode == "sleep") "已进入休息模式" else "已进入工作模式")
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行设置终端模式工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行设置终端模式工具失败: ${e.message}")
            }
        }
    }
    
    private fun executeSpeakerRegisterTool(toolArgs: JSONObject?): JSONObject {
        Log.d(TAG, "执行声纹注册工具，参数: $toolArgs")
        
        try {
            val name = toolArgs?.optString("name", "") ?: ""
            
            if (name.isEmpty()) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "缺少必要参数：name")
                    put("message", "请提供用户姓名")
                }
            }
            
            val context = getContext()
            val speakerIdentificationManager = com.minrray.voicectl.core.speaker.SpeakerIdentificationManager.getInstance(context)
            
            Log.d(TAG, "SpeakerIdentificationManager instance: $speakerIdentificationManager")
            Log.d(TAG, "SpeakerIdentificationManager ready: ${speakerIdentificationManager.isReady()}")
            
            if (!speakerIdentificationManager.isReady()) {
                return JSONObject().apply {
                    put("success", false)
                    put("error", "声纹识别未初始化")
                    put("message", "声纹识别服务未初始化")
                }
            }
            
            speakerIdentificationManager.enterRegisterMode(name, object : com.minrray.voicectl.core.speaker.SpeakerIdentificationListener {
                override fun onSpeakerRegistered(speakerName: String) {
                    Log.d(TAG, "声纹注册成功: $speakerName")
                    
                    val successResult = JSONObject().apply {
                        put("success", true)
                        put("message", "" +
                                "" +
                                "" +
                                "" +
                                "！$speakerName 的声纹已成功注册")
                        put("name", speakerName)
                        put("speaker_count", speakerIdentificationManager.getSpeakerCount())
                    }
                    
                    Log.d(TAG, "发送声纹注册成功消息到服务端: $successResult")
                    WebSocketHandler.getInstance().sendTextMessage(successResult.toString())
                    
                    speakerRegisterProgressListener?.onSpeakerRegisterComplete(true, "声纹注册成功！$speakerName 的声纹已成功注册")
                }
                
                override fun onSpeakerRegistrationFailed(reason: String) {
                    Log.d(TAG, "声纹注册失败: $reason")
                    
                    val failResult = JSONObject().apply {
                        put("success", false)
                        put("error", reason)
                        put("message", "声纹注册失败：$reason")
                    }
                    
                    Log.d(TAG, "发送声纹注册失败消息到服务端: $failResult")
                    WebSocketHandler.getInstance().sendTextMessage(failResult.toString())
                    
                    speakerRegisterProgressListener?.onSpeakerRegisterComplete(false, "声纹注册失败：$reason")
                }
                
                override fun onSpeakerRegistrationProgress(progress: Int, maxProgress: Int) {
                    Log.d(TAG, "声纹注册进度: $progress/$maxProgress")
                    speakerRegisterProgressListener?.onSpeakerRegisterProgress(progress, maxProgress)
                }
            })
            
            Log.d(TAG, "声纹注册工具执行完成，立即返回注册中状态")
            return JSONObject().apply {
                put("success", true)
                put("status", "registering")
                put("message", "声纹注册中，请数数123...")
                put("name", name)
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行声纹注册工具失败: ${e.message}", e)
            return JSONObject().apply {
                put("success", false)
                put("error", "执行声纹注册工具失败: ${e.message}")
                put("message", "声纹注册失败，请稍后再试")
            }
        }
    }
}