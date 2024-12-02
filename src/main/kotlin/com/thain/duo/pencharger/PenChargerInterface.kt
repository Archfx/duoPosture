package com.thain.duo.pencharger

import com.thain.duo.MSPenCharger
import android.util.Log

public class PenChargerInterface {
    private var msPenCharger : MSPenCharger? = null
    
    public enum class PowerState(val value: Int) {
        POWER_ON(0),
        POWER_OFF(1);
        
        companion object {
            fun fromInt(incomingValue: Int) = PowerState.values().first { it.value == incomingValue }
            fun fromString(incomingValue: String?) = PowerState.values().first { it.value.equals(incomingValue?.toInt())}
        }
    }

    companion object {
        const val TAG = "WIRELESS PEN CHARGING"
    }

    constructor() {
        msPenCharger = MSPenCharger()
    }

    /*
        Read state, if it's different from incoming state, then write.
    */
    public fun SetPowerState(state: PowerState){
        val currentPowerState : PowerState = GetPowerState()
        try{
            if(currentPowerState != state){
                msPenCharger?.writeSysfs(state.value.toString())
                Log.d(TAG, "Set power state to ${state} through pencharger interface.")
            }
            else{
                Log.d(TAG, "Skipping write because values are the same.")
            }
        } catch (e: Exception){
            e.printStackTrace()
        }
    }

    public fun GetPowerState() : PowerState{
        try{        
            return PowerState.fromString(msPenCharger?.readSysfs())            
        } catch (e : Exception){
            e.printStackTrace()
            return PowerState.POWER_OFF //Default value if unknown.
        }
    }
}