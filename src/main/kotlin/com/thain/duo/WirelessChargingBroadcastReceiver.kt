package com.thain.duo

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.Context
import android.os.SystemProperties
import android.util.Log
import android.widget.Toast
import java.io.FileOutputStream
import java.io.DataOutputStream

public class WirelessChargingBroadcastReceiver : BroadcastReceiver() {
    private var isDuo2: Boolean = false

    companion object {
        const val TAG = "WIRELESS PEN CHARGING"
        const val penChargerFile = "/sys/devices/platform/soc/soc:surface_util/ms_pen_charger/ms_pen_charger"
    }

    override fun onReceive(context: Context, intent: Intent){
        val action: String? = intent.getAction()
        isDuo2 = SystemProperties.get("ro.hardware", "N/A") == "surfaceduo2"
        
        // 0 is OFF, 1 is ON for system properties!
        val chargingValue = SystemProperties.get("persist.sys.phh.duo.wireless_pen_charging", "0")
        
        if(action.equals("com.thain.duo.broadcast.SET_WIRELESS_CHARGING_STATE") && isDuo2){
            /*  
                We know the device is a duo2, and we have received the broadcast
                Write 0 (ON) or 1 (OFF) into the specified file in duo2! Yes, this is
                different to the charging value set from sys props.
            */ 
            var valueToWrite: String = "1" // default OFF

            if(chargingValue.equals("1")){
                valueToWrite = "0"
            }
            
            try {
                // This doesn't work, we will need some sort of HAL to interface with this file.
                // FileOutputStream(penChargerFile, false).use { fos ->
                //     DataOutputStream(fos).use { dos ->
                //         dos.writeBytes(valueToWrite)
                //         Log.d(TAG, "Set pen charger to state ${valueToWrite}")
                //     }
                // }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        if(!isDuo2){
            Log.d(TAG, "Disallowed from running this action, not a Duo2")
            return
        }
    }
} 