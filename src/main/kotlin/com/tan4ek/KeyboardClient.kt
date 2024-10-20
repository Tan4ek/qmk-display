package com.tan4ek

import java.time.Duration
import org.hid4java.HidDevice

sealed interface Message {
    data class Version(val major: Short, val minor: Short, val patch: Short) : Message {
        constructor(version: String) : this(
            version.substringBefore('.').toShort(),
            version.substringAfter('.').substringBefore('.').toShort(),
            version.substringAfterLast('.').toShort()
        )
    }

    // To notify the keyboard that the communication will be stopped
    data object StopCommunication : Message

    data class HardwareState(
        // CPU usage in percents, from 0 to 100
        val cpuUsage: Short,
        // RAM usage in percents, from 0 to 100
        val ramUsage: Short
    ) : Message

    /**
     * 16 * 32 = 512 bytes - pixel image
     */
    data class Image(val image: ByteArray) : Message {
        override fun toString(): String {
            return "Image(${image.size} bytes)"
        }
    }
}

sealed interface Response {
    data class Acknowledge(val message: Message) : Response
    data class Error(val message: String, val error: Throwable? = null) : Response
}

class KeyboardClient(
    private val device: HidDevice
) : AutoCloseable {

    val hidInfo: HidInfo by lazy { HidInfo(device.productId, device.vendorId, device.usage) }

    init {
        if (!device.open()) {
            throw IllegalStateException("Failed to open device")
        }
    }

    fun isOpen(): Boolean = !device.isClosed

    fun send(message: Message, timeout: Duration = Duration.ofSeconds(1)): Response = underSync(this) {
        if (message is Message.Image) {
            if (message.image.size != 512) {
                return@underSync Response.Error("Invalid image size: ${message.image.size}. Expected 512")
            }
            // 16 * 32 bytes to send  - 512 bytes
            // split it into chunks with equal
            val chunked: List<ByteArray> = message.image.asList().chunked(16) { it.toByteArray() }

            // send each chunk of image sequentially
            chunked.forEachIndexed { index, imageBytes ->
                val messagePayload = ByteArray(HID_PACKET_SIZE)
                messagePayload[0] = 0x04
                messagePayload[1] = index.toShort().toByte()
                imageBytes.copyInto(messagePayload, 2)

                val response = sendToDevice(message, messagePayload, timeout)
                if (response is Response.Error) {
                    return@underSync response
                }
            }

            return@underSync Response.Acknowledge(message)
        } else {
            val messageBytes: ByteArray = when (message) {
                is Message.Version -> with(message) {
                    ByteArray(HID_PACKET_SIZE).apply {
                        this[0] = 0x01
                        this[1] = major.toByte()
                        this[2] = minor.toByte()
                        this[3] = patch.toByte()
                    }
                }

                is Message.HardwareState -> with(message) {
                    ByteArray(HID_PACKET_SIZE).apply {
                        this[0] = 0x02
                        this[1] = cpuUsage.toByte()
                        this[2] = ramUsage.toByte()
                    }
                }

                is Message.StopCommunication -> {
                    ByteArray(HID_PACKET_SIZE).apply {
                        this[0] = 0x03
                    }
                }

                else -> return@underSync Response.Error("Unsupported message type: $message")
            }

            return@underSync sendToDevice(message, messageBytes, timeout)
        }
    }

    fun open() = device.open()

    override fun close() {
        device.close()
    }

    private fun sendToDevice(message: Message, messageBytes: ByteArray, timeout: Duration): Response {
        try {
            logger.debug("Sending message: $message")
            val writeResult = device.write(messageBytes, HID_PACKET_SIZE, 0x0)
            if (writeResult < 0) {
                return Response.Error("Failed to write message")
            }
            val response = device.read(HID_PACKET_SIZE, timeout.toMillis().toInt()).toByteArray()
            if (response.isNotEmpty() && response.size == messageBytes.size && response[0] == ACK_OK) {
                logger.debug("Received acknowledge")
                return Response.Acknowledge(message)
            } else {
                logger.warning("Invalid response received: $response . Request: $messageBytes")
                return Response.Error("Invalid response")
            }
        } catch (e: Exception) {
            logger.warning("Error: $e")
            return Response.Error(e.message ?: "Unknown error", e)
        }
    }

    companion object {
        private val logger = Logger("KeyboardClient")

        // default fixed value for qmk, details https://docs.qmk.fm/features/rawhid#sending-data-to-the-keyboard
        private const val HID_PACKET_SIZE = 32
        private const val ACK_OK: Byte = 0x00
    }
}
