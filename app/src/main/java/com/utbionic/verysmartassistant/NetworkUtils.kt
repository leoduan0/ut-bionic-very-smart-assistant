package com.utbionic.verysmartassistant

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat.getSystemService

object NetworkUtils {

    fun getSSID(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        // On Android 14+, returns "<unknown ssid>" if Location permission is missing
        return info.ssid.replace("\"", "")
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun getLocalIpAddress(context: Context): String {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val linkProperties = connectivityManager.getLinkProperties(connectivityManager.activeNetwork)
        val ipAddress = linkProperties?.linkAddresses?.firstOrNull { it.address.isSiteLocalAddress }?.address
        return ipAddress?.hostAddress ?: "0.0.0.0"
    }
}