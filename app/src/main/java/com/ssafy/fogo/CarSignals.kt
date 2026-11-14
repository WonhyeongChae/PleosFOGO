package com.ssafy.fogo

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.util.Log

class CarSignals(private val app: FogoApplication) {
    private var car: Car? = null
    private var properties: CarPropertyManager? = null
    private var restrictions: CarUxRestrictionsManager? = null
    private var vehicle: Vehicle? = null
    private var ambientArea: SeatArea? = null

    private val gearListener = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val gear = (value.value as? Int).takeIf { value.status == CarPropertyValue.STATUS_AVAILABLE }
            app.handler.post { updateGear(gear) }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            app.handler.post { app.updateGear(Gear.UNKNOWN) }
        }
    }

    fun connect() {
        runCatching {
            car = Car.createCar(app, app.handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connection, ready ->
                if (ready) connectAaos(connection) else {
                    properties = null
                    restrictions = null
                    app.updateGear(Gear.UNKNOWN)
                    app.updateRestrictions(null)
                }
            }
        }.onFailure(::log)
        runCatching {
            val sdk = Vehicle(app)
            sdk.initialize()
            vehicle = sdk
            subscribeDoor(sdk)
            subscribeWiper(sdk)
            subscribeLights(sdk)
            subscribeOdometer(sdk)
        }.onFailure(::log)
    }

    private fun connectAaos(connection: Car) {
        runCatching {
            val manager = connection.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            properties = manager
            manager.registerCallback(gearListener, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            updateGear(manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0))
        }.onFailure(::log)
        runCatching {
            val manager = connection.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            restrictions = manager
            manager.registerListener { state ->
                app.handler.post { app.updateRestrictions(state.isRequiresDistractionOptimization) }
            }
            app.updateRestrictions(manager.currentCarUxRestrictions?.isRequiresDistractionOptimization)
        }.onFailure(::log)
    }

    private fun updateGear(value: Int?) {
        val gear = when (value) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_DRIVE -> Gear.DRIVE
            VehicleGear.GEAR_NEUTRAL, VehicleGear.GEAR_REVERSE -> Gear.OTHER
            else -> Gear.UNKNOWN
        }
        app.updateGear(gear)
    }

    /** Front passenger door → 둘이서; either rear door → 가족과. */
    private fun subscribeDoor(sdk: Vehicle) = runCatching {
        val door = sdk.getDoor()
        val rearOpen = mutableSetOf<String>()
        fun watch(area: DoorArea, state: (Boolean) -> Unit) = runCatching {
            fun update(position: Int?) { app.handler.post { state((position ?: 0) > 0) } }
            door.registerDoorPosition(area, object : DoorPositionListener {
                override fun onPositionUpdated(pos: Int?) = update(pos)
                override fun onFailed(e: Exception) = log(e)
            })
            door.getCurrentDoorPosition(area, ::update, ::log)
        }.onFailure(::log)
        watch(DoorArea(DoorValue.ROW_1_RIGHT)) { open -> app.updateVehicleTags(companion = open) }
        fun rear(side: String, area: DoorArea) = watch(area) { open ->
            if (open) rearOpen += side else rearOpen -= side
            app.updateVehicleTags(family = rearOpen.isNotEmpty())
        }
        rear("left", DoorArea(DoorValue.ROW_2_LEFT))
        rear("right", DoorArea(DoorValue.ROW_2_RIGHT))
    }.onFailure(::log)

    /** 누적 주행거리 (km) for 차 생일 (permission CAR_DRIVE); Virtual Console's Odometer card publishes it on the emulator. */
    private fun subscribeOdometer(sdk: Vehicle) = runCatching {
        val odometer = sdk.getOdometer()
        fun state(km: Float?) { if (km != null) app.handler.post { app.updateOdometer(km.toDouble()) } }
        odometer.registerDistanceDriven(object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(value: Float?) = state(value)
            override fun onFailed(e: Exception) = log(e)
        })
        odometer.getCurrentDistanceDriven(::state, ::log)
    }.onFailure(::log)

    private fun subscribeWiper(sdk: Vehicle) = runCatching {
        val area = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiper = sdk.getWiper()
        fun state(value: WiperState?) { app.handler.post { app.updateVehicleTags(rain = value == WiperState.ON) } }
        wiper.registerWiperState(area, object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) = state(state)
            override fun onFailed(e: Exception) = log(e)
        })
        wiper.getCurrentWiperState(area, ::state, ::log)
    }.onFailure(::log)

    private fun subscribeLights(sdk: Vehicle) = runCatching {
        val light = sdk.getLight()
        fun state(value: LightState?) { app.handler.post { app.updateVehicleTags(night = value == LightState.ON) } }
        light.registerLowBeamLightState(object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) = state(state)
            override fun onFailed(e: Exception) = log(e)
        })
        light.getCurrentLowBeamLightState(::state, ::log)
        light.checkAmbientLightBrightnessColorCapabilities({ _, areas ->
            app.handler.post { ambientArea = areas.firstOrNull() }
        }, ::log)
    }.onFailure(::log)

    fun ambient(color: Int, result: (Boolean) -> Unit) {
        val light = vehicle?.getLight() ?: return result(false)
        val area = ambientArea ?: return result(false)
        val rgb = color and 0x00ffffff
        var complete = false
        fun finish(applied: Boolean) {
            app.handler.post {
                if (!complete) {
                    complete = true
                    Log.i("FOGO", "Vehicle ambient readback matched: $applied")
                    result(applied)
                }
            }
        }
        app.handler.postDelayed({ finish(false) }, 2500)
        runCatching {
            light.setAmbientLightBrightnessColor(area, rgb, { _, _ ->
                app.handler.postDelayed({
                    runCatching {
                        light.getAmbientLightBrightnessColor(area,
                            { value -> finish(value != null && (value and 0x00ffffff) == rgb) },
                            { error -> log(error); finish(false) })
                    }.onFailure { log(it); finish(false) }
                }, 300)
            }, { error -> log(error); finish(false) })
        }.onFailure { log(it); finish(false) }
    }

    private fun log(error: Throwable) {
        Log.w("FOGO", "Vehicle service: ${error.javaClass.simpleName}")
    }
}
