package com.minrray.voicectl.core.weather

data class WeatherResponse(
    val code: String,
    val updateTime: String,
    val now: WeatherNow,
    val refer: Refer
)

data class WeatherNow(
    val obsTime: String,
    val temp: String,
    val feelsLike: String,
    val icon: String,
    val text: String,
    val wind360: String,
    val windDir: String,
    val windScale: String,
    val windSpeed: String,
    val humidity: String,
    val precip: String,
    val pressure: String,
    val vis: String,
    val cloud: String,
    val dew: String
)

data class Refer(
    val sources: List<String>,
    val license: List<String>
)

data class GeoCityLookupResponse(
    val code: String,
    val location: List<GeoLocation>,
    val refer: Refer
)

data class GeoLocation(
    val name: String,
    val id: String,
    val lat: String,
    val lon: String,
    val adm2: String,
    val adm1: String,
    val country: String,
    val tz: String,
    val utcOffset: String,
    val isDst: String,
    val type: String,
    val rank: String,
    val fxLink: String
)

data class WeatherDailyResponse(
    val code: String,
    val updateTime: String,
    val daily: List<WeatherDaily>,
    val refer: Refer
)

data class WeatherDaily(
    val fxDate: String,
    val sunrise: String,
    val sunset: String,
    val moonrise: String,
    val moonset: String,
    val moonPhase: String,
    val moonPhaseIcon: String,
    val tempMax: String,
    val tempMin: String,
    val iconDay: String,
    val textDay: String,
    val iconNight: String,
    val textNight: String,
    val wind360Day: String,
    val windDirDay: String,
    val windScaleDay: String,
    val windSpeedDay: String,
    val wind360Night: String,
    val windDirNight: String,
    val windScaleNight: String,
    val windSpeedNight: String,
    val humidity: String,
    val precip: String,
    val pressure: String,
    val vis: String,
    val cloud: String,
    val uvIndex: String
)

enum class WeatherType(val iconCode: String, val description: String) {
    CLEAR_DAY("100", "晴"),
    CLEAR_NIGHT("150", "晴"),
    CLOUDY("101", "多云"),
    PARTLY_CLOUDY_DAY("102", "晴间多云"),
    PARTLY_CLOUDY_NIGHT("152", "晴间多云"),
    OVERCAST("103", "阴"),
    LIGHT_RAIN("300", "小雨"),
    MODERATE_RAIN("301", "中雨"),
    HEAVY_RAIN("302", "大雨"),
    STORM_RAIN("303", "暴雨"),
    THUNDERSHOWER("304", "雷阵雨"),
    LIGHT_SNOW("400", "小雪"),
    MODERATE_SNOW("401", "中雪"),
    HEAVY_SNOW("402", "大雪"),
    STORM_SNOW("403", "暴雪"),
    FOG("500", "雾"),
    HAZE("501", "霾"),
    WIND("502", "大风"),
    UNKNOWN("999", "未知");

    companion object {
        fun fromIconCode(code: String): WeatherType {
            return values().find { it.iconCode == code } ?: UNKNOWN
        }
    }
}
