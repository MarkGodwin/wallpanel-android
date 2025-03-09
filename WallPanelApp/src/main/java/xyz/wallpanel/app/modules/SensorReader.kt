/*
 * Copyright (c) 2022 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.app.modules

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.SensorManager.SENSOR_DELAY_NORMAL
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import xyz.wallpanel.app.R
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import xyz.wallpanel.app.persistence.Configuration
import java.util.*
import javax.inject.Inject

data class SensorInfo(val sensorType: String, val unit: String?, val deviceClass: String?, val displayName: String?, val binary: Boolean, val active: Boolean )

class SensorReader @Inject
constructor(private val context: Context){

    private val mSensorManager: SensorManager
    private val activeSensors = ArrayList<Sensor>()
    private val sensorDiscoveryInfo = ArrayList<SensorInfo>()
    private val handler = Handler(Looper.getMainLooper())
    private var updateFrequencyMilliSeconds: Int = 0
    private var callback: SensorCallback? = null
    private var proximityDistance: Boolean = false
    private var proximityOccupancy: Boolean = false
    private var lastOccupancyMode: Boolean = false
    private var occupancyThreshold: Float = 15.0f
    private var lastDistance: Float = 100.0f

    private val batteryHandlerRunnable = object : Runnable {
        override fun run() {
            if (updateFrequencyMilliSeconds > 0) {
                Timber.d("Updating Battery")
                getBatteryReading()
                handler.postDelayed(this, updateFrequencyMilliSeconds.toLong())
            }
        }
    }

    init {
        Timber.d("Creating SensorReader")
        mSensorManager = context.getSystemService(SENSOR_SERVICE) as SensorManager
    }

    fun getSensorDiscoveryInfo(): List<SensorInfo> {
        return sensorDiscoveryInfo
    }

    fun startReadings(freqSeconds: Int, callback: SensorCallback, configuration: Configuration) {
        Timber.d("startReadings")

        this.callback = callback
        if (freqSeconds >= 0) {
            if(configuration.batterySensorsEnabled) {
                updateFrequencyMilliSeconds = 1000 * freqSeconds
                handler.postDelayed( batteryHandlerRunnable, updateFrequencyMilliSeconds.toLong())
            }
        }
        startSensorReadings(configuration)
    }

    fun refreshSensors() {
        if(updateFrequencyMilliSeconds > 0) {
            handler.removeCallbacksAndMessages(batteryHandlerRunnable)
            handler.post(batteryHandlerRunnable)
        }
    }

    fun stopReadings() {
        Timber.d("stopReadings")
        updateFrequencyMilliSeconds = 0
        handler.removeCallbacksAndMessages(batteryHandlerRunnable)
        stopSensorReading()
    }

    private fun publishSensorData(event: SensorEvent) {
        Timber.d("publishSensorData")

        when(event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                if(proximityDistance) {

                    val distance = event.values[0];
                    if(Math.abs(lastDistance - distance) > 2.0) {
                        lastDistance = distance

                        val data = JSONObject()
                        data.put(VALUE, distance)
                        data.put(UNIT, UNIT_CM)
                        data.put(ID, event.sensor.name)
                        callback?.publishSensorData("proximity", data)
                    }
                }
                if(proximityOccupancy) {
                    val distance = event.values[0];
                    val close = distance < occupancyThreshold
                    if(close != lastOccupancyMode) {
                        lastOccupancyMode = close
                        val data = JSONObject()
                        data.put(VALUE, close)
                        data.put(ID, event.sensor.name)
                        callback?.publishSensorData("occupancy", data)
                    }
                }
            }
            else -> {
                val sensorName = getSensorName(event.sensor.type)!!
                val data = JSONObject()
                data.put(VALUE, event.values[0])
                data.put(UNIT, getSensorUnit(event.sensor.type))
                data.put(ID, event.sensor.name)

                callback?.publishSensorData(sensorName, data)
            }

        }

    }

    private fun getSensorName(sensorType: Int): String? {
        when (sensorType) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> return TEMPERATURE
            Sensor.TYPE_LIGHT -> return LIGHT
            Sensor.TYPE_MAGNETIC_FIELD -> return MAGNETIC_FIELD
            Sensor.TYPE_PRESSURE -> return PRESSURE
            Sensor.TYPE_RELATIVE_HUMIDITY -> return HUMIDITY
            Sensor.TYPE_PROXIMITY -> return PROXIMITY
        }
        return null
    }

    private fun getSensorDisplayName(sensorType: Int): String? {
        when (sensorType) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> return context.getString(R.string.mqtt_sensor_temperature)
            Sensor.TYPE_LIGHT -> return context.getString(R.string.mqtt_sensor_light)
            Sensor.TYPE_MAGNETIC_FIELD -> return context.getString(R.string.mqtt_sensor_magnetic_field)
            Sensor.TYPE_PRESSURE -> return context.getString(R.string.mqtt_sensor_pressure)
            Sensor.TYPE_RELATIVE_HUMIDITY -> return context.getString(R.string.mqtt_sensor_humidity)
            Sensor.TYPE_PROXIMITY -> return context.getString(R.string.mqtt_sensor_proximity)
        }
        return null
    }

    private fun getSensorUnit(sensorType: Int): String? {
        when (sensorType) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> return UNIT_C
            Sensor.TYPE_LIGHT -> return UNIT_LX
            Sensor.TYPE_MAGNETIC_FIELD -> return UNIT_UT
            Sensor.TYPE_PRESSURE -> return UNIT_HPA
            Sensor.TYPE_RELATIVE_HUMIDITY -> return UNIT_PERCENTAGE
        }
        return null
    }

    /**
     * Map to Home Assistant device class for sensors
     */
    private fun getSensorDeviceClass(sensorType: Int): String? {
        when(sensorType) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> return "temperature"
            Sensor.TYPE_LIGHT -> return "illuminance"
            Sensor.TYPE_PRESSURE -> return "pressure"
            Sensor.TYPE_RELATIVE_HUMIDITY -> return "humidity"
        }
        return null
    }



    /**
     * Start all sensor readings.
     */
    private fun startSensorReadings(configuration: Configuration) {
        Timber.d("startSensorReadings")

        occupancyThreshold = configuration.proximityOccupancySensorThreshold
        proximityDistance = configuration.sensorsEnabled && configuration.proximityDistanceSensorEnabled
        proximityOccupancy = (configuration.sensorsEnabled || configuration.proximityOccupancySensorWakeScreen) && configuration.proximityOccupancySensorEnabled

        for (s in mSensorManager.getSensorList(Sensor.TYPE_ALL)) {
            val sensorName = getSensorName(s.type)
            if (sensorName != null) {
                var add = false
                when(s.type) {
                    Sensor.TYPE_PROXIMITY -> {
                        if(proximityDistance || proximityOccupancy)
                            activeSensors.add(s)
                        sensorDiscoveryInfo.add(SensorInfo("proximity", UNIT_CM, "distance", context.getString(R.string.mqtt_sensor_proximity), false, proximityDistance))
                        sensorDiscoveryInfo.add(SensorInfo("occupancy", null, "occupancy", context.getString(R.string.mqtt_sensor_occupancy), true, proximityOccupancy))
                    }
                    Sensor.TYPE_LIGHT -> {
                        val enable = configuration.lightSensorEnabled && configuration.sensorsEnabled
                        if(enable)
                            activeSensors.add(s)
                        sensorDiscoveryInfo.add(SensorInfo(sensorName, getSensorUnit(s.type), getSensorDeviceClass(s.type), getSensorDisplayName(s.type), false, enable))
                    }
                    else -> {
                        if(configuration.sensorsEnabled)
                            activeSensors.add(s)
                        sensorDiscoveryInfo.add(SensorInfo(sensorName, getSensorUnit(s.type), getSensorDeviceClass(s.type), getSensorDisplayName(s.type),
                            binary = false,
                            active = configuration.sensorsEnabled
                        ))
                    }
                }
            }
        }

        for (sensor in activeSensors) {
            val ok = mSensorManager.registerListener(sensorListener, sensor, SENSOR_DELAY_NORMAL)
        }
    }

    /**
     * Stop all sensor readings.
     */
    private fun stopSensorReading() {
        Timber.d("stopSensorReading")
        for (sensor in activeSensors) {
            mSensorManager.unregisterListener(sensorListener, sensor)
        }

        activeSensors.clear()
        sensorDiscoveryInfo.clear()
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if(event != null) {
                publishSensorData(event)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        }
    }

    private fun getBatteryReading() {
        Timber.d("getBatteryReading")
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        val batteryStatusIntExtra = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_CHARGING || batteryStatusIntExtra == BatteryManager.BATTERY_STATUS_FULL
        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val usbCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_USB
        val acCharge = chargePlug == BatteryManager.BATTERY_PLUGGED_AC
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val data = JSONObject()
        try {
            data.put(VALUE, level)
            data.put(UNIT, UNIT_PERCENTAGE)
            data.put(CHARGING, isCharging)
            data.put(AC_PLUGGED, acCharge)
            data.put(USB_PLUGGED, usbCharge)
        } catch (ex: JSONException) {
            ex.printStackTrace()
        }

        callback?.publishSensorData(BATTERY, data)
    }


    companion object {
        const val BATTERY: String = "battery"
        const val CHARGING: String = "charging"
        const val AC_PLUGGED: String = "acPlugged"
        const val USB_PLUGGED: String = "usbPlugged"
        const val HUMIDITY: String = "humidity"
        const val PROXIMITY: String = "proximity"
        const val LIGHT: String = "light"
        const val PRESSURE: String = "pressure"
        const val TEMPERATURE: String = "temperature"
        const val MAGNETIC_FIELD: String = "magneticField"
        const val UNIT_C: String = "°C"
        const val UNIT_PERCENTAGE: String = "%"
        const val UNIT_HPA: String = "hPa"
        const val UNIT_UT: String = "uT"
        const val UNIT_LX: String = "lx"
        const val UNIT_CM: String = "cm"
        const val VALUE = "value"
        const val UNIT = "unit"
        const val ID = "id"
    }
}