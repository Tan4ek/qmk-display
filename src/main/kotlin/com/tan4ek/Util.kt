package com.tan4ek


import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.hid4java.HidDevice

data class HidInfo(
    val productId: Int,
    val vendorId: Int,
    val usageId: Int
)

fun HidDevice.hidInfo(): HidInfo = HidInfo(productId, vendorId, usage)

fun <T> underSync(sync: Any, block: () -> T): T {
    synchronized(sync) {
        return block()
    }
}

fun waitIndefinitely() {
    var interrupted = false
    try {
        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE)
                return
            } catch (e: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }
}

class Logger(private val tag: String) {
    fun info(message: String) {
        println("INFO " + Instant.now().toString() + " $tag " + message)
    }

    fun debug(message: String) {
        if (isDebugEnabled.get()) {
            println("DEBUG " + Instant.now().toString() + " $tag " + message)
        }
    }

    fun warning(message: String) {
        println("WARNING " + Instant.now().toString() + " $tag " + message)
    }

    companion object {
        private val isDebugEnabled = AtomicBoolean(false)

        fun enableDebug() {
            isDebugEnabled.set(true)
        }
    }
}


