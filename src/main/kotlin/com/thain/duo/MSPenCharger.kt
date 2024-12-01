package com.thain.duo

// This needs to be here, as the Native functions 
// refer to the package name as well! 
// Will move this into pencharger when we change the function names on the native files.
public class MSPenCharger {
    companion object {
        init {
            System.loadLibrary("ms_pen_charger")
        }
    }

    public external fun readSysfs(): String
    public external fun writeSysfs(value: String)
}