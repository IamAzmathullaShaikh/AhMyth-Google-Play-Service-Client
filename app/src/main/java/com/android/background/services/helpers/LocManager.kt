package com.android.background.services.helpers

import android.content.Context
import android.content.Context.LOCATION_SERVICE
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log

class LocManager : LocationListener {
    private val mContext: Context?
    var isGPSEnabled = false
    var isNetworkEnabled = false
    private var canGetLocation = false
    private var _location: Location? = null
    private var _latitude = 0.0
    private var _longitude = 0.0

    protected var locationManager: LocationManager? = null

    constructor() {
        mContext = null
    }

    constructor(context: Context) {
        mContext = context
        getLocation()
    }

    fun getLocation(): Location? {
        try {
            locationManager = mContext?.getSystemService(LOCATION_SERVICE) as LocationManager?
            isGPSEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
            isNetworkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true

            if (isGPSEnabled || isNetworkEnabled) {
                canGetLocation = true
                if (isNetworkEnabled) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        MIN_TIME_BW_UPDATES,
                        MIN_DISTANCE_CHANGE_FOR_UPDATES.toFloat(),
                        this
                    )
                    Log.d("Network", "Network")
                    if (locationManager != null) {
                        _location = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        _location?.let {
                            _latitude = it.latitude
                            _longitude = it.longitude
                        }
                    }
                }
                if (isGPSEnabled) {
                    if (_location == null) {
                        locationManager?.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            MIN_TIME_BW_UPDATES,
                            MIN_DISTANCE_CHANGE_FOR_UPDATES.toFloat(),
                            this
                        )
                        if (locationManager != null) {
                            _location = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            _location?.let {
                                _latitude = it.latitude
                                _longitude = it.longitude
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stopUsingGPS()
        return _location
    }

    fun stopUsingGPS() {
        locationManager?.removeUpdates(this)
    }

    fun getLatitude(): Double {
        _location?.let {
            _latitude = it.latitude
        }
        return _latitude
    }

    fun getLongitude(): Double {
        _location?.let {
            _longitude = it.longitude
        }
        return _longitude
    }

    fun canGetLocation(): Boolean {
        return canGetLocation
    }

    override fun onLocationChanged(location: Location) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {}

    companion object {
        private const val MIN_DISTANCE_CHANGE_FOR_UPDATES: Long = 10
        private const val MIN_TIME_BW_UPDATES = (1000 * 60 * 1).toLong()
    }
}
