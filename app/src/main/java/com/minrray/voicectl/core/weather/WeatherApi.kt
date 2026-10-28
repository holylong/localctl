package com.minrray.voicectl.core.weather

import android.content.Context
import android.util.Log
import com.qweather.sdk.Callback
import com.qweather.sdk.JWTGenerator
import com.qweather.sdk.QWeather
import com.qweather.sdk.basic.Lang
import com.qweather.sdk.basic.Unit
import com.qweather.sdk.parameter.weather.WeatherParameter
import com.qweather.sdk.parameter.geo.GeoCityLookupParameter
import com.qweather.sdk.response.error.ErrorResponse
import com.qweather.sdk.response.weather.WeatherDailyResponse
import com.qweather.sdk.response.weather.WeatherNowResponse
import com.qweather.sdk.response.geo.GeoCityLookupResponse as QWeatherGeoCityLookupResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WeatherApi(private val context: Context) {
    companion object {
        private const val TAG = "WeatherApi"
        private const val DEFAULT_LOCATION = "101010100"
        private const val DEFAULT_HOST = "devapi.qweather.com"
    }

    private var currentLocation: String = DEFAULT_LOCATION
    private var isInitialized: Boolean = false

    fun getLocation(): String = currentLocation

    fun setLocation(location: String) {
        currentLocation = location
        Log.d(TAG, "Location set to: $currentLocation")
    }
    
    fun isConfigured(): Boolean {
        return isInitialized
    }

    fun initialize(host: String = DEFAULT_HOST) {
        if (isInitialized) {
            Log.d(TAG, "QWeather already initialized")
            return
        }

        try {
            QWeather.getInstance(context, host).setLogEnable(true)
            isInitialized = true
            Log.d(TAG, "QWeather initialized successfully with host: $host")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize QWeather", e)
            throw e
        }
    }

    fun initializeWithJWT(
        host: String = DEFAULT_HOST,
        privateKey: String,
        projectId: String,
        kid: String
    ) {
        if (isInitialized) {
            Log.d(TAG, "QWeather already initialized")
            return
        }

        try {
            val jwt = JWTGenerator(privateKey, projectId, kid)
            QWeather.getInstance(context, host)
                .setTokenGenerator(jwt)
                .setLogEnable(true)
            isInitialized = true
            Log.d(TAG, "QWeather initialized successfully with JWT authentication")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize QWeather with JWT", e)
            throw e
        }
    }

    suspend fun getCurrentWeather(): Result<WeatherNow> {
        if (!isInitialized) {
            Log.e(TAG, "QWeather not initialized")
            return Result.failure(IllegalStateException("QWeather not initialized"))
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val parameter = WeatherParameter(currentLocation)
                    .lang(Lang.ZH_HANS)
                    .unit(Unit.METRIC)

                Log.d(TAG, "Requesting current weather for location: $currentLocation")

                QWeather.instance.weatherNow(parameter, object : Callback<WeatherNowResponse> {
                    override fun onSuccess(response: WeatherNowResponse) {
                        Log.d(TAG, "WeatherNowResponse received: ${response.code}")
                        
                        if (response.code != "200") {
                            val errorMsg = "Weather API returned error code: ${response.code}"
                            Log.e(TAG, errorMsg)
                            continuation.resume(Result.failure(Exception(errorMsg)))
                            return
                        }

                        val now = response.now
                        val weatherNow = WeatherNow(
                            obsTime = now.obsTime,
                            temp = now.temp,
                            feelsLike = now.feelsLike,
                            icon = now.icon,
                            text = now.text,
                            wind360 = now.wind360,
                            windDir = now.windDir,
                            windScale = now.windScale,
                            windSpeed = now.windSpeed,
                            humidity = now.humidity,
                            precip = now.precip,
                            pressure = now.pressure,
                            vis = now.vis,
                            cloud = now.cloud,
                            dew = now.dew
                        )

                        Log.d(TAG, "Current weather updated: ${weatherNow.text} ${weatherNow.temp}°C")
                        continuation.resume(Result.success(weatherNow))
                    }

                    override fun onFailure(errorResponse: ErrorResponse) {
                        val error = errorResponse.error
                        val errorMsg = "Weather API failure: ${error.status} - ${error.detail}"
                        Log.e(TAG, errorMsg)
                        continuation.resume(Result.failure(Exception(errorMsg)))
                    }

                    override fun onException(e: Throwable) {
                        Log.e(TAG, "Weather API exception", e)
                        continuation.resumeWithException(e)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Exception in getCurrentWeather", e)
                continuation.resumeWithException(e)
            }
        }
    }

    suspend fun getDailyWeather(): Result<List<WeatherDaily>> {
        if (!isInitialized) {
            Log.e(TAG, "QWeather not initialized")
            return Result.failure(IllegalStateException("QWeather not initialized"))
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val parameter = WeatherParameter(currentLocation)
                    .lang(Lang.ZH_HANS)
                    .unit(Unit.METRIC)

                Log.d(TAG, "Requesting daily weather for location: $currentLocation")

                QWeather.instance.weather7d(parameter, object : Callback<WeatherDailyResponse> {
                    override fun onSuccess(response: WeatherDailyResponse) {
                        Log.d(TAG, "WeatherDailyResponse received: ${response.code}")
                        
                        if (response.code != "200") {
                            val errorMsg = "Daily weather API returned error code: ${response.code}"
                            Log.e(TAG, errorMsg)
                            continuation.resume(Result.failure(Exception(errorMsg)))
                            return
                        }

                        val dailyList = response.daily.map { daily ->
                            WeatherDaily(
                                fxDate = daily.fxDate,
                                sunrise = daily.sunrise,
                                sunset = daily.sunset,
                                moonrise = daily.moonrise,
                                moonset = daily.moonset,
                                moonPhase = daily.moonPhase,
                                moonPhaseIcon = daily.moonPhaseIcon,
                                tempMax = daily.tempMax,
                                tempMin = daily.tempMin,
                                iconDay = daily.iconDay,
                                textDay = daily.textDay,
                                iconNight = daily.iconNight,
                                textNight = daily.textNight,
                                wind360Day = daily.wind360Day,
                                windDirDay = daily.windDirDay,
                                windScaleDay = daily.windScaleDay,
                                windSpeedDay = daily.windSpeedDay,
                                wind360Night = daily.wind360Night,
                                windDirNight = daily.windDirNight,
                                windScaleNight = daily.windScaleNight,
                                windSpeedNight = daily.windSpeedNight,
                                humidity = daily.humidity,
                                precip = daily.precip,
                                pressure = daily.pressure,
                                vis = daily.vis,
                                cloud = daily.cloud,
                                uvIndex = daily.uvIndex
                            )
                        }

                        Log.d(TAG, "Daily weather updated: ${dailyList.size} days")
                        continuation.resume(Result.success(dailyList))
                    }

                    override fun onFailure(errorResponse: ErrorResponse) {
                        val error = errorResponse.error
                        val errorMsg = "Daily weather API failure: ${error.status} - ${error.detail}"
                        Log.e(TAG, errorMsg)
                        continuation.resume(Result.failure(Exception(errorMsg)))
                    }

                    override fun onException(e: Throwable) {
                        Log.e(TAG, "Daily weather API exception", e)
                        continuation.resumeWithException(e)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Exception in getDailyWeather", e)
                continuation.resumeWithException(e)
            }
        }
    }

    suspend fun lookupCity(location: String): Result<GeoLocation> {
        if (!isInitialized) {
            Log.e(TAG, "QWeather not initialized")
            return Result.failure(IllegalStateException("QWeather not initialized"))
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                val parameter = GeoCityLookupParameter(location)
                    .lang(Lang.ZH_HANS)
                    .number(1)

                Log.d(TAG, "Requesting city lookup for location: $location")

                QWeather.instance.geoCityLookup(parameter, object : Callback<QWeatherGeoCityLookupResponse> {
                    override fun onSuccess(response: QWeatherGeoCityLookupResponse) {
                        Log.d(TAG, "GeoCityLookupResponse received: ${response.code}")
                        
                        if (response.code != "200") {
                            val errorMsg = "City lookup API returned error code: ${response.code}"
                            Log.e(TAG, errorMsg)
                            continuation.resume(Result.failure(Exception(errorMsg)))
                            return
                        }

                        val locationList = response.location
                        if (locationList.isEmpty()) {
                            val errorMsg = "No city found for location: $location"
                            Log.e(TAG, errorMsg)
                            continuation.resume(Result.failure(Exception(errorMsg)))
                            return
                        }

                        val qWeatherLocation = locationList[0]
                        val geoLocation = GeoLocation(
                            name = qWeatherLocation.name,
                            id = qWeatherLocation.id,
                            lat = qWeatherLocation.lat,
                            lon = qWeatherLocation.lon,
                            adm2 = qWeatherLocation.adm2,
                            adm1 = qWeatherLocation.adm1,
                            country = qWeatherLocation.country,
                            tz = qWeatherLocation.tz,
                            utcOffset = qWeatherLocation.utcOffset,
                            isDst = qWeatherLocation.isDst,
                            type = qWeatherLocation.type,
                            rank = qWeatherLocation.rank,
                            fxLink = qWeatherLocation.fxLink
                        )

                        Log.d(TAG, "City lookup successful: ${geoLocation.name} (${geoLocation.adm1})")
                        continuation.resume(Result.success(geoLocation))
                    }

                    override fun onFailure(errorResponse: ErrorResponse) {
                        val error = errorResponse.error
                        val errorMsg = "City lookup API failure: ${error.status} - ${error.detail}"
                        Log.e(TAG, errorMsg)
                        continuation.resume(Result.failure(Exception(errorMsg)))
                    }

                    override fun onException(e: Throwable) {
                        Log.e(TAG, "City lookup API exception", e)
                        continuation.resumeWithException(e)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "Exception in lookupCity", e)
                continuation.resumeWithException(e)
            }
        }
    }

    fun getWeatherText(weather: WeatherNow): String {
        val weatherType = WeatherType.fromIconCode(weather.icon)
        val temp = weather.temp
        val text = weather.text
        val humidity = weather.humidity
        val windDir = weather.windDir
        val windScale = weather.windScale

        return "$text $temp°C 湿度${humidity}% $windDir${windScale}级"
    }
}
