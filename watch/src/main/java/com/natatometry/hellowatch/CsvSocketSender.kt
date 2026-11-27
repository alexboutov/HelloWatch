package com.natatometry.hellowatch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.zip.CRC32

/**
 * Transfer test, stage 3 (project doc claude/HelloWatch_transfer_test_plan.md): the socket method.
 * One Bluetooth connection per recording, watch to phone:
 *
 *   watch: header line, ASCII, ended by '\n':  HWCSV1 <file name> <length> <crc32, hex> <handoverMs>
 *   watch: <length> bytes, the file as it is
 *   phone: "OK\n" once the file is saved with the same length and CRC, otherwise "ERR <reason>\n"
 *
 * handoverMs is the watch's clock when the recording was handed over for sending (for the
 * phone's transfer_log.csv). The watch keeps the file until it has read "OK".
 */
object CsvSocketProtocol {
    /** Service ID of HelloWatch's own socket; the phone listens under the same ID. */
    val SERVICE_UUID: UUID = UUID.fromString("7d0c5b0e-3f6a-4c58-9a51-6b1d2f4e8a90")
    const val MAGIC = "HWCSV1"
    const val OK = "OK"
    private const val CHUNK_BYTES = 8192
    private const val MAX_ANSWER_BYTES = 200

    fun crc32(file: File): Long {
        val crc = CRC32()
        val buffer = ByteArray(CHUNK_BYTES)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
            }
        }
        return crc.value
    }

    fun header(fileName: String, length: Long, crc: Long, handoverMs: Long): String =
        "$MAGIC $fileName $length ${java.lang.Long.toHexString(crc)} $handoverMs\n"

    /** Sends the header and the file, then waits for the phone's answer line and returns it. */
    fun send(file: File, handoverMs: Long, out: OutputStream, input: InputStream): String {
        require(file.name.none { it.isWhitespace() }) { "file name with a space" }
        out.write(header(file.name, file.length(), crc32(file), handoverMs).toByteArray(Charsets.US_ASCII))
        val buffer = ByteArray(CHUNK_BYTES)
        file.inputStream().use { src ->
            while (true) {
                val n = src.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        out.flush()
        return readLine(input)
    }

    /** One answer line without its '\n'; what was read so far if the stream ends first. */
    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < MAX_ANSWER_BYTES) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}

/** Connects to the paired phone and sends one file. Blocking: never call it on the main thread. */
class CsvSocketSender {

    companion object {
        /** A transfer is given up after 60 s plus the time the file needs at 20 KB/s. */
        private const val BASE_LIMIT_MS = 60_000L
        private const val SLOWEST_BYTES_PER_MS = 20L
    }

    /**
     * Returns null when the phone has confirmed the file, otherwise the reason of the failure.
     * Every paired device is tried in turn; the phone is the one that answers.
     */
    @SuppressLint("MissingPermission")
    fun send(file: File, handoverMs: Long): String? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "no Bluetooth"
        if (!adapter.isEnabled) return "Bluetooth is off"
        val devices = try {
            adapter.bondedDevices?.toList().orEmpty()
        } catch (e: SecurityException) {
            return "no Bluetooth permission"
        }
        if (devices.isEmpty()) return "no paired phone"

        var lastError = "not sent"
        for (device in devices) {
            var socket: BluetoothSocket? = null
            val watchdog = Timer(true)
            try {
                socket = device.createRfcommSocketToServiceRecord(CsvSocketProtocol.SERVICE_UUID)
                val toClose = socket
                // The socket has no time limit of its own: closing it ends a transfer that hangs.
                val limitMs = BASE_LIMIT_MS + file.length() / SLOWEST_BYTES_PER_MS
                watchdog.schedule(object : TimerTask() {
                    override fun run() {
                        try { toClose.close() } catch (e: Exception) { }
                    }
                }, limitMs)
                socket.connect()
                val answer = CsvSocketProtocol.send(file, handoverMs, socket.outputStream, socket.inputStream)
                if (answer == CsvSocketProtocol.OK) return null
                lastError = if (answer.isEmpty()) "no answer from the phone" else answer
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            } finally {
                watchdog.cancel()
                try { socket?.close() } catch (e: Exception) { }
            }
        }
        return lastError
    }
}
