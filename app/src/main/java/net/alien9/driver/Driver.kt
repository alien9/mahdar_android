package net.alien9.driver

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

class Driver: Application() {
    private var lastLocation: Location? = null
    lateinit var fusedLocationClient: FusedLocationProviderClient
    lateinit var loca: LocationManager
    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        loca= applicationContext.getSystemService(LOCATION_SERVICE) as LocationManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationClient.lastLocation.addOnSuccessListener {
            lastLocation=it
        }

    }
    fun getLastKnownLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        var loc: Location?=null
        if (loca.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            loc = loca.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } else {
            if (loca.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                loc = loca.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        }
        Log.d("DriverData", "return location ${loc?.latitude} ${loc?.longitude}")
        return loc

    }
}