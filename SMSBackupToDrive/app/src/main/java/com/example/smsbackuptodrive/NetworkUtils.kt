package com.example.smsbackuptodrive

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Utility object for network-related operations.
 */
object NetworkUtils {
    /**
     * Checks if a network connection is available on the device.
     * This method handles differences in API levels for network state checking.
     * For API level 23 (Marshmallow) and above, it uses [ConnectivityManager.getNetworkCapabilities].
     * For older API levels, it uses the deprecated [ConnectivityManager.activeNetworkInfo].
     *
     * @param context The application context used to access system services.
     * @return True if a network connection (Wi-Fi, Cellular, Ethernet, Bluetooth) is available and connected, false otherwise.
     */
    @Suppress("DEPRECATION") // Suppress deprecation warning for activeNetworkInfo on older APIs
    fun isNetworkAvailable(context: Context): Boolean {
        // Get an instance of ConnectivityManager system service.
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // For Android M (API 23) and above, use NetworkCapabilities API.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Get the currently active network.
            val network = connectivityManager.activeNetwork ?: return false // If no active network, return false.
            // Get the capabilities of the active network.
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false // If no capabilities, return false.

            // Check if the active network has one of the common transport types.
            return when {
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true // Wi-Fi connection
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true // Cellular data connection
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true // Ethernet connection
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> true // Bluetooth PAN connection
                else -> false // Other transport types are considered not connected for this app's purpose.
            }
        } else {
            // For versions older than Android M, use the deprecated activeNetworkInfo.
            @Suppress("DEPRECATION") // Local suppression for this specific usage
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false // If no active network info, return false.
            @Suppress("DEPRECATION")
            return networkInfo.isConnected // Check if the network is connected.
        }
    }
}
