package com.tan4ek

import oshi.SystemInfo
import oshi.hardware.CentralProcessor

class HardwareMonitor {
    private val si = SystemInfo()
    private val hal = si.hardware
    private val centralProcessor = hal.processor
    private val globalMemory = hal.memory

    private var oldTicks: LongArray = LongArray(CentralProcessor.TickType.entries.size)

    // Returns the percentage of used CPU, from 0 to 100
    fun cpuUsage(): Double {
        val d: Double = centralProcessor.getSystemCpuLoadBetweenTicks(oldTicks)
        oldTicks = centralProcessor.systemCpuLoadTicks
        return d * 100
    }

    // Returns the percentage of used RAM, from 0 to 100
    fun ramUsage(): Double {
        val totalMemory = globalMemory.total
        val usedRam = ((totalMemory - globalMemory.available).toDouble() / totalMemory) * 100
        return usedRam
    }
}
