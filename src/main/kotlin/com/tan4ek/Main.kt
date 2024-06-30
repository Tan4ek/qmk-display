package com.tan4ek

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.hid4java.HidManager
import org.hid4java.HidServices
import org.hid4java.HidServicesListener
import org.hid4java.HidServicesSpecification
import org.hid4java.event.HidServicesEvent

const val version = "1.0.0"

const val DEFAULT_PRODUCT_ID: Int = 0xb4c2
const val DEFAULT_VENDOR_ID: Int = 0x8d1d
const val DEFAULT_USAGE_ID: Int = 0x61

interface KeyboardListener {
    fun onKeyboardAttached(keyboardClient: KeyboardClient)
    fun onKeyboardDetached(keyboardClient: KeyboardClient)
}

class HidKeyboardListener(
    private val searchHidInfo: HidInfo,
    private val keyboardListener: KeyboardListener
) : HidServicesListener {
    private var keyboardClientRef: AtomicReference<KeyboardClient?> = AtomicReference(null)

    override fun hidDeviceAttached(event: HidServicesEvent) = underSync(this) {
        if (event.hidDevice.hidInfo() == searchHidInfo) {
            logger.info("Keyboard device is attached: ${event.hidDevice}")
            val keyboardClient = KeyboardClient(event.hidDevice)
            keyboardClientRef.set(keyboardClient)
            keyboardListener.onKeyboardAttached(keyboardClient)
        }
    }

    override fun hidDeviceDetached(event: HidServicesEvent) = underSync(this) {
        if (keyboardClientRef.get()?.hidInfo == event.hidDevice.hidInfo()) {
            logger.info("Keyboard device is detached: ${event.hidDevice}")

            keyboardListener.onKeyboardDetached(keyboardClientRef.get()!!)
            keyboardClientRef.set(null)
        }
    }

    override fun hidFailure(event: HidServicesEvent) = underSync(this) {
        if (keyboardClientRef.get()?.hidInfo == event.hidDevice.hidInfo()) {
            logger.info("Keyboard failure: $event")
        }
    }

    override fun hidDataReceived(event: HidServicesEvent) {}

    companion object {
        private val logger = Logger("HidKeyboardListener")
    }
}


class KeyboardProcedure(private val keyboardClient: KeyboardClient, private val hardwareMonitor: HardwareMonitor) :
    Runnable {
    private val isRunning = AtomicReference(true)

    private val refreshInterval = Duration.ofSeconds(1)
    private val waitAfterFailure = Duration.ofSeconds(2)
    private val waitAfterFirstMessage = Duration.ofSeconds(2)

    override fun run() = retry {
        if (!isRunning.get()) {
            return@retry
        }
        logger.info("Starting communication")
        if (!keyboardClient.isOpen()) {
            logger.info("Opening device ...")
            val isOpened = keyboardClient.open()
            if (!isOpened) {
                logger.info("Failed to open device")
                return@retry
            }
        }
        keyboardClient.send(Message.Version(version))
        Thread.sleep(waitAfterFirstMessage)
        while (isRunning.get()) {
            try {
                val cpuUsage = hardwareMonitor.cpuUsage()
                val availableRam = hardwareMonitor.ramUsage()

                try {
                    keyboardClient.send(
                        Message.HardwareState(
                            cpuUsage.toInt().toShort(),
                            availableRam.toInt().toShort()
                        )
                    )
                } catch (e: Exception) {
                    logger.info("Error reading data: $e")
                }

                Thread.sleep(refreshInterval)
            } catch (e: Exception) {
                logger.info("Error: $e")
                Thread.sleep(waitAfterFailure)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        if (keyboardClient.isOpen()) {
            keyboardClient.send(Message.StopCommunication)
        }
    }

    private fun retry(block: () -> Unit) {
        while (isRunning.get()) {
            try {
                block()
                return
            } catch (e: Exception) {
                logger.warning("Unexpected error: $e . Retrying procedure ...")
                Thread.sleep(waitAfterFailure)
            }
        }
    }

    companion object {
        private val logger = Logger("KeyboardProcedure")
    }
}

class KeyboardProcedureManager : AutoCloseable, KeyboardListener {
    private val keyboardCommunicationExecutor = Executors.newSingleThreadExecutor()
    private val keyboardProcedureRef = AtomicReference<KeyboardProcedure?>()
    private val hardwareMonitor = HardwareMonitor()

    override fun onKeyboardAttached(keyboardClient: KeyboardClient) = underSync<Unit>(this) {
        if (keyboardProcedureRef.get() == null) {
            val keyboardProcedure = KeyboardProcedure(keyboardClient, hardwareMonitor)
            keyboardProcedureRef.set(keyboardProcedure)
            keyboardCommunicationExecutor.execute(keyboardProcedure)
        } else {
            logger.info("Keyboard device already attached, stop previous communication and start new one")
            keyboardProcedureRef.get()?.stop()
            val keyboardProcedure = KeyboardProcedure(keyboardClient, hardwareMonitor)
            keyboardProcedureRef.set(keyboardProcedure)
            keyboardCommunicationExecutor.execute(keyboardProcedure)
        }
    }

    override fun onKeyboardDetached(keyboardClient: KeyboardClient) = underSync<Unit>(this) {
        keyboardProcedureRef.get()?.stop()
        keyboardProcedureRef.set(null)
    }

    override fun close() {
        keyboardProcedureRef.get()?.stop()
        keyboardCommunicationExecutor.shutdown()
        keyboardCommunicationExecutor.awaitTermination(10, TimeUnit.SECONDS)
    }

    companion object {
        private val logger = Logger("KeyboardProcedureManager")
    }
}

// convert string like 0x55 to integer 85
fun hexStringToInt(hexString: String): Int {
    return hexString.substring(2).toInt(16)
}

// convert integer 85 to string like 0x55
fun Int.hexString(): String {
    return "0x${this.toString(16)}"
}

fun readHidInfoFromArgs(args: Array<String>): HidInfo {
    fun readPropertyValue(key: String): String? {
        return args.find { it.startsWith(key) }?.split("=")?.getOrNull(1)
    }

    val vendorId = readPropertyValue("--vendor-id")?.let { hexStringToInt(it) } ?: DEFAULT_VENDOR_ID
    val productId = readPropertyValue("--product-id")?.let { hexStringToInt(it) } ?: DEFAULT_PRODUCT_ID
    val usageId = readPropertyValue("--usage-id")?.let { hexStringToInt(it) } ?: DEFAULT_USAGE_ID
    return HidInfo(productId, vendorId, usageId)
}

private fun createHidService(): HidServices {
    return HidServicesSpecification()
        .apply {
            isAutoStart = false
            isAutoShutdown = false
        }
        .let { HidManager.getHidServices(it) }
}

fun main(args: Array<String>) {
    val logger = Logger("Main")

    if (args.contains("--verbose")) {
        Logger.enableDebug()
    }

    logger.info("Starting QMK Display $version")

    val hidInfo = readHidInfoFromArgs(args)

    logger.info(
        "Searching for device with vendorId=${hidInfo.vendorId.hexString()}, " +
                "productId=${hidInfo.productId.hexString()}, usageId=${hidInfo.usageId.hexString()}"
    )
    KeyboardProcedureManager().use { keyboardProcedureManager ->
        val hidKeyboardListener = HidKeyboardListener(hidInfo, keyboardProcedureManager)

        val hidServices = createHidService()
        hidServices.addHidServicesListener(hidKeyboardListener)

        logger.info("Starting listening for device")
        hidServices.start()

        Runtime.getRuntime().addShutdownHook(
            Thread {
                logger.info("Shutting dow the application")
                keyboardProcedureManager.close()
                hidServices.shutdown()
            }
        )

        waitIndefinitely()
    }
}
