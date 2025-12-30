package com.sdk.glassessdksample

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import org.json.JSONObject
import java.io.IOException

data class WeatherResult(
    val locationName: String,
    val temperatureC: Double,
    val condition: String,
    val humidity: Int,
    val windSpeed: Double,
    val rawJson: String
)

class WeatherService(private val apiKey: String) {
    private val TAG = "WeatherService"
    private val client = OkHttpClient()

    private fun hasKey(): Boolean = apiKey.isNotBlank() && apiKey != "YOUR_API_KEY_HERE"

    fun getWeatherByCity(city: String, callback: (WeatherResult?, String?) -> Unit) {
        if (!hasKey()) {
            callback(null, "Weather API key not configured. Set 'weather_api_key' in settings.")
            return
        }

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.openweathermap.org")
            .addPathSegment("data")
            .addPathSegment("2.5")
            .addPathSegment("weather")
            .addQueryParameter("q", city)
            .addQueryParameter("appid", apiKey)
            .addQueryParameter("units", "metric")
            .build()

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Weather fetch failed: ${e.message}")
                callback(null, "Failed to fetch weather: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Weather API error: ${response.code}")
                        callback(null, "Weather API error ${response.code}")
                        return
                    }

                    val body = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        val name = json.optString("name", city)
                        val main = json.getJSONObject("main")
                        val temp = main.optDouble("temp", Double.NaN)
                        val humidity = main.optInt("humidity", -1)
                        val weatherArr = json.getJSONArray("weather")
                        val condition = if (weatherArr.length() > 0) weatherArr.getJSONObject(0).optString("description", "") else ""
                        val wind = json.optJSONObject("wind")
                        val windSpeed = wind?.optDouble("speed", 0.0) ?: 0.0

                        val result = WeatherResult(
                            locationName = name,
                            temperatureC = temp,
                            condition = condition,
                            humidity = humidity,
                            windSpeed = windSpeed,
                            rawJson = body
                        )

                        callback(result, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing weather response: ${e.message}")
                        callback(null, "Error parsing weather data")
                    }
                }
            }
        })
    }

    fun getWeatherByCoords(lat: Double, lon: Double, callback: (WeatherResult?, String?) -> Unit) {
        if (!hasKey()) {
            callback(null, "Weather API key not configured. Set 'weather_api_key' in settings.")
            return
        }

        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.openweathermap.org")
            .addPathSegment("data")
            .addPathSegment("2.5")
            .addPathSegment("weather")
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lon", lon.toString())
            .addQueryParameter("appid", apiKey)
            .addQueryParameter("units", "metric")
            .build()

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Weather fetch failed: ${e.message}")
                callback(null, "Failed to fetch weather: ${e.message}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Weather API error: ${response.code}")
                        callback(null, "Weather API error ${response.code}")
                        return
                    }

                    val body = response.body?.string() ?: ""
                    try {
                        val json = JSONObject(body)
                        val name = json.optString("name", "")
                        val main = json.getJSONObject("main")
                        val temp = main.optDouble("temp", Double.NaN)
                        val humidity = main.optInt("humidity", -1)
                        val weatherArr = json.getJSONArray("weather")
                        val condition = if (weatherArr.length() > 0) weatherArr.getJSONObject(0).optString("description", "") else ""
                        val wind = json.optJSONObject("wind")
                        val windSpeed = wind?.optDouble("speed", 0.0) ?: 0.0

                        val result = WeatherResult(
                            locationName = name,
                            temperatureC = temp,
                            condition = condition,
                            humidity = humidity,
                            windSpeed = windSpeed,
                            rawJson = body
                        )

                        callback(result, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing weather response: ${e.message}")
                        callback(null, "Error parsing weather data")
                    }
                }
            }
        })
    }
}
