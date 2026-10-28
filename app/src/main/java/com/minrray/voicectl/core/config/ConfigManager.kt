package com.minrray.voicectl.core.config

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import org.json.JSONObject
import java.io.File

class ConfigManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ConfigManager"
        private lateinit var instance: ConfigManager
        fun getInstance(context: Context): ConfigManager {
            if (!::instance.isInitialized) {
                instance = ConfigManager(context)
            }
            return instance
        }
    }

    private var cachedAllConfigs: AllConfigs? = null
    private val configFile: File = File(context.filesDir, "device_config.json")

    // 设备配置数据类
    data class DeviceConfig(
        val deviceId: String,
        val deviceName: String,
        val clientId: String,
        val token: String,
        val otaUrl: String = "http://10.1.70.248:8003/xiaozhi/ota/",
        val serverUrl: String = ""
    )

    // 天气配置数据类
    data class WeatherConfig(
        val location: String = "101010100",
        val privateKey: String = "MC4CAQAwBQYDK2VwBCIEIIgR1P8fOX3eXEw5cVT50h5h28+FEzE0e3+eftPFtGrw",
        val projectId: String = "4DKTNWT84J",
        val kid: String = "K8WJX92CAU",
        val host: String = "p35hv5xegy.re.qweatherapi.com"
    )

    // BizConf配置数据类
    data class BizConfConfig(
        val userId: String = "2665917",
        val userName: String = "会会",
        val siteSign: String = "kexinyun",
        val confParties: Int = 25,
        val apiKey: String = "8466e4a47d1babf6eeaf5cf1be2f0409"
    )

    // 所有配置数据类
    data class AllConfigs(
        val deviceConfig: DeviceConfig,
        val weatherConfig: WeatherConfig,
        val bizConfConfig: BizConfConfig
    )

    // 获取设备唯一ID
    private fun getDeviceId(): String {
        return try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )
        } catch (e: Exception) {
            Log.e(TAG, "获取设备ID失败: ${e.message}", e)
            "unknown_device_${System.currentTimeMillis()}"
        }
    }

    // 使用JSON保存配置文件（确保按模块分组）
    private fun writeConfigFile(configs: AllConfigs) {
        try {
            val root = JSONObject()
            
            // 设备配置
            val device = JSONObject()
            device.put("device_id", configs.deviceConfig.deviceId)
            device.put("device_name", configs.deviceConfig.deviceName)
            device.put("client_id", configs.deviceConfig.clientId)
            device.put("token", configs.deviceConfig.token)
            device.put("ota_url", configs.deviceConfig.otaUrl)
            device.put("server_url", configs.deviceConfig.serverUrl)
            root.put("device", device)
            
            // 天气配置
            val weather = JSONObject()
            weather.put("location", configs.weatherConfig.location)
            weather.put("private_key", configs.weatherConfig.privateKey)
            weather.put("project_id", configs.weatherConfig.projectId)
            weather.put("kid", configs.weatherConfig.kid)
            weather.put("host", configs.weatherConfig.host)
            root.put("weather", weather)
            
            // BizConf配置
            val bizconf = JSONObject()
            bizconf.put("user_id", configs.bizConfConfig.userId)
            bizconf.put("user_name", configs.bizConfConfig.userName)
            bizconf.put("site_sign", configs.bizConfConfig.siteSign)
            bizconf.put("conf_parties", configs.bizConfConfig.confParties)
            bizconf.put("api_key", configs.bizConfConfig.apiKey)
            root.put("bizconf", bizconf)
            
            configFile.writeText(root.toString(2))
            
            Log.d(TAG, "配置文件写入成功: ${configFile.absolutePath}")
            Log.d(TAG, "配置文件大小: ${configFile.length()} bytes")
        } catch (e: Exception) {
            Log.e(TAG, "配置文件写入失败: ${e.message}", e)
            e.printStackTrace()
        }
    }

    // 从配置文件读取设备配置
    private fun loadDeviceConfig(): DeviceConfig {
        val defaultConfig = createDefaultDeviceConfig()
        
        return if (configFile.exists()) {
            try {
                val content = configFile.readText()
                val root = JSONObject(content)
                val device = root.getJSONObject("device")
                
                val deviceId = if (device.has("device_id") && device.optString("device_id").isNotEmpty()) {
                    device.optString("device_id")
                } else {
                    defaultConfig.deviceId
                }
                
                val deviceName = if (device.has("device_name") && device.optString("device_name").isNotEmpty()) {
                    device.optString("device_name")
                } else {
                    defaultConfig.deviceName
                }
                
                val clientId = if (device.has("client_id") && device.optString("client_id").isNotEmpty()) {
                    device.optString("client_id")
                } else {
                    defaultConfig.clientId
                }
                
                val token = if (device.has("token") && device.optString("token").isNotEmpty()) {
                    device.optString("token")
                } else {
                    defaultConfig.token
                }
                
                val otaUrl = if (device.has("ota_url") && device.optString("ota_url").isNotEmpty()) {
                    device.optString("ota_url")
                } else {
                    defaultConfig.otaUrl
                }
                
                val serverUrl = if (device.has("server_url")) {
                    device.optString("server_url")
                } else {
                    defaultConfig.serverUrl
                }
                
                Log.d(TAG, "从配置文件读取设备配置: deviceId=$deviceId, deviceName=$deviceName, clientId=$clientId, token=$token, otaUrl=$otaUrl, serverUrl=$serverUrl")
                
                DeviceConfig(deviceId, deviceName, clientId, token, otaUrl, serverUrl)
            } catch (e: Exception) {
                Log.e(TAG, "加载设备配置失败: ${e.message}", e)
                e.printStackTrace()
                defaultConfig
            }
        } else {
            Log.d(TAG, "配置文件不存在，使用默认配置")
            defaultConfig
        }
    }

    // 创建默认设备配置
    private fun createDefaultDeviceConfig(): DeviceConfig {
        return DeviceConfig(
            deviceId = getDeviceId(),
            deviceName = "Android测试设备",
            clientId = "android_test_client",
            token = "your-token1",
            otaUrl = "http://10.1.70.248:8003/xiaozhi/ota/",
            serverUrl = ""
        )
    }

    // 从配置文件读取天气配置
    private fun loadWeatherConfig(): WeatherConfig {
        val defaultConfig = WeatherConfig()
        
        return if (configFile.exists()) {
            try {
                val content = configFile.readText()
                val root = JSONObject(content)
                val weather = root.getJSONObject("weather")
                
                val location = if (weather.has("location") && weather.optString("location").isNotEmpty()) {
                    weather.optString("location")
                } else {
                    defaultConfig.location
                }
                
                val privateKey = if (weather.has("private_key") && weather.optString("private_key").isNotEmpty()) {
                    weather.optString("private_key")
                } else {
                    defaultConfig.privateKey
                }
                
                val projectId = if (weather.has("project_id") && weather.optString("project_id").isNotEmpty()) {
                    weather.optString("project_id")
                } else {
                    defaultConfig.projectId
                }
                
                val kid = if (weather.has("kid") && weather.optString("kid").isNotEmpty()) {
                    weather.optString("kid")
                } else {
                    defaultConfig.kid
                }
                
                val host = if (weather.has("host") && weather.optString("host").isNotEmpty()) {
                    weather.optString("host")
                } else {
                    defaultConfig.host
                }
                
                Log.d(TAG, "从配置文件读取天气配置: location=$location, host=$host")
                
                WeatherConfig(location, privateKey, projectId, kid, host)
            } catch (e: Exception) {
                Log.e(TAG, "加载天气配置失败: ${e.message}", e)
                e.printStackTrace()
                defaultConfig
            }
        } else {
            Log.d(TAG, "配置文件不存在，使用默认天气配置")
            defaultConfig
        }
    }

    // 从配置文件读取BizConf配置
    private fun loadBizConfConfig(): BizConfConfig {
        val defaultConfig = BizConfConfig()
        
        return if (configFile.exists()) {
            try {
                val content = configFile.readText()
                val root = JSONObject(content)
                val bizconf = root.getJSONObject("bizconf")
                
                val userId = if (bizconf.has("user_id") && bizconf.optString("user_id").isNotEmpty()) {
                    bizconf.optString("user_id")
                } else {
                    defaultConfig.userId
                }
                
                val userName = if (bizconf.has("user_name") && bizconf.optString("user_name").isNotEmpty()) {
                    bizconf.optString("user_name")
                } else {
                    defaultConfig.userName
                }
                
                val siteSign = if (bizconf.has("site_sign") && bizconf.optString("site_sign").isNotEmpty()) {
                    bizconf.optString("site_sign")
                } else {
                    defaultConfig.siteSign
                }
                
                val confParties = if (bizconf.has("conf_parties")) {
                    bizconf.optInt("conf_parties", defaultConfig.confParties)
                } else {
                    defaultConfig.confParties
                }
                
                val apiKey = if (bizconf.has("api_key") && bizconf.optString("api_key").isNotEmpty()) {
                    bizconf.optString("api_key")
                } else {
                    defaultConfig.apiKey
                }
                
                Log.d(TAG, "从配置文件读取BizConf配置: userId=$userId, userName=$userName")
                
                BizConfConfig(userId, userName, siteSign, confParties, apiKey)
            } catch (e: Exception) {
                Log.e(TAG, "加载BizConf配置失败: ${e.message}", e)
                e.printStackTrace()
                defaultConfig
            }
        } else {
            Log.d(TAG, "配置文件不存在，使用默认BizConf配置")
            defaultConfig
        }
    }

    // 统一加载所有配置（首次加载时从文件读取，后续直接返回缓存）
    fun loadAllConfigs(): AllConfigs {
        if (cachedAllConfigs != null) {
            Log.d(TAG, "使用缓存的配置")
            return cachedAllConfigs!!
        }
        
        Log.d(TAG, "开始加载所有配置")
        Log.d(TAG, "配置文件路径: ${configFile.absolutePath}")
        Log.d(TAG, "配置文件存在: ${configFile.exists()}")
        
        val deviceConfig = loadDeviceConfig()
        val weatherConfig = loadWeatherConfig()
        val bizConfConfig = loadBizConfConfig()
        
        val configs = AllConfigs(deviceConfig, weatherConfig, bizConfConfig)
        
        // 检查是否需要保存配置文件（配置文件不存在或有的配置项为空）
        val needSave = !configFile.exists() || 
                      deviceConfig.deviceId.isEmpty() || 
                      deviceConfig.deviceName.isEmpty() || 
                      deviceConfig.clientId.isEmpty() || 
                      deviceConfig.token.isEmpty() || 
                      deviceConfig.otaUrl.isEmpty() ||
                      weatherConfig.location.isEmpty() ||
                      weatherConfig.privateKey.isEmpty() ||
                      weatherConfig.projectId.isEmpty() ||
                      weatherConfig.kid.isEmpty() ||
                      weatherConfig.host.isEmpty() ||
                      bizConfConfig.userId.isEmpty() ||
                      bizConfConfig.userName.isEmpty() ||
                      bizConfConfig.siteSign.isEmpty() ||
                      bizConfConfig.apiKey.isEmpty()
        
        if (needSave) {
            Log.d(TAG, "配置文件不存在或配置项为空，保存配置文件")
            writeConfigFile(configs)
        } else {
            Log.d(TAG, "配置文件已存在且完整，跳过保存")
        }
        
        Log.d(TAG, "设备配置: $deviceConfig")
        Log.d(TAG, "天气配置: $weatherConfig")
        Log.d(TAG, "BizConf配置: $bizConfConfig")
        
        cachedAllConfigs = configs
        return configs
    }

    // 更新serverUrl并保存配置文件（用于人脸或语音唤醒建立连接后）
    fun updateServerUrl(newServerUrl: String) {
        val configs = cachedAllConfigs ?: loadAllConfigs()
        val newDeviceConfig = configs.deviceConfig.copy(serverUrl = newServerUrl)
        val newConfigs = configs.copy(deviceConfig = newDeviceConfig)
        
        writeConfigFile(newConfigs)
        cachedAllConfigs = newConfigs
        
        Log.d(TAG, "已更新serverUrl: $newServerUrl")
    }

    // 统一保存所有配置
    fun saveAllConfigs(configs: AllConfigs) {
        writeConfigFile(configs)
        cachedAllConfigs = configs
    }
}
