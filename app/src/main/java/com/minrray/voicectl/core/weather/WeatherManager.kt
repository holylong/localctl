package com.minrray.voicectl.core.weather

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

class WeatherManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "WeatherManager"
        
        @Volatile
        private var instance: WeatherManager? = null
        
        fun getInstance(context: Context): WeatherManager {
            return instance ?: synchronized(this) {
                instance ?: WeatherManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val weatherApi = WeatherApi(context)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentWeather: WeatherNow? = null
    private var dailyWeather: List<WeatherDaily>? = null
    private var cityName: String = ""
    private var lastUpdateTime: Long = 0
    private val updateInterval = 30 * 60 * 1000L

    private var weatherUpdateListener: ((WeatherNow) -> Unit)? = null
    
    fun initialize(host: String = "devapi.qweather.com") {
        Log.d(TAG, "Initializing WeatherManager with host: $host")
        weatherApi.initialize(host)
    }
    
    fun initializeWithJWT(
        host: String = "devapi.qweather.com",
        privateKey: String,
        projectId: String,
        kid: String
    ) {
        Log.d(TAG, "Initializing WeatherManager with JWT authentication")
        weatherApi.initializeWithJWT(host, privateKey, projectId, kid)
    }
    
    fun setLocation(location: String) {
        Log.d(TAG, "Setting location to: $location")
        weatherApi.setLocation(location)
    }
    
    fun isConfigured(): Boolean {
        val configured = weatherApi.isConfigured()
        Log.d(TAG, "检查配置状态: $configured")
        return configured
    }

    fun setWeatherUpdateListener(listener: (WeatherNow) -> Unit) {
        weatherUpdateListener = listener
    }

    fun getCurrentWeather(): WeatherNow? = currentWeather

    fun getDailyWeather(): List<WeatherDaily>? = dailyWeather

    fun getCityName(): String = cityName

    fun refreshWeather() {
        Log.d(TAG, "refreshWeather: 开始刷新天气数据")
        scope.launch {
            try {
                if (!weatherApi.isConfigured()) {
                    Log.w(TAG, "Weather API is not configured, skipping update")
                    return@launch
                }
                
                val now = System.currentTimeMillis()
                if (now - lastUpdateTime < updateInterval && currentWeather != null) {
                    Log.d(TAG, "Weather data is still fresh, skipping update")
                    return@launch
                }

                Log.d(TAG, "Refreshing weather data...")
                
                val cityResult = weatherApi.lookupCity(weatherApi.getLocation())
                cityResult.onSuccess { geoLocation ->
                    cityName = geoLocation.name
                    Log.d(TAG, "City name updated: $cityName")
                }.onFailure { e ->
                    Log.e(TAG, "Failed to lookup city", e)
                }
                
                val currentResult = weatherApi.getCurrentWeather()
                currentResult.onSuccess { weather ->
                    currentWeather = weather
                    lastUpdateTime = now
                    Log.d(TAG, "Current weather updated: ${weather.text} ${weather.temp}°C ${weather.icon}")
                    weatherUpdateListener?.invoke(weather)
                }.onFailure { e ->
                    Log.e(TAG, "Failed to get current weather", e)
                }

                val dailyResult = weatherApi.getDailyWeather()
                dailyResult.onSuccess { daily ->
                    dailyWeather = daily
                    Log.d(TAG, "Daily weather updated: ${daily.size} days")
                }.onFailure { e ->
                    Log.e(TAG, "Failed to get daily weather", e)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing weather", e)
            }
        }
    }

    fun startAutoRefresh(intervalMillis: Long = updateInterval) {
        scope.launch {
            while (isActive) {
                refreshWeather()
                delay(intervalMillis)
            }
        }
    }

    fun stopAutoRefresh() {
        scope.coroutineContext.cancelChildren()
    }

    suspend fun getWeatherText(): String {
        return withContext(Dispatchers.IO) {
            if (currentWeather != null) {
                weatherApi.getWeatherText(currentWeather!!)
            } else {
                "加载中..."
            }
        }
    }

    fun getWeatherType(): WeatherType {
        return currentWeather?.let { WeatherType.fromIconCode(it.icon) } ?: WeatherType.UNKNOWN
    }
}