package com.natatometry.hellowatch

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.CRC32

/**
 * Transfer test, stage 3 (project doc claude/HelloWatch_transfer_test_plan.md): the phone end of
 * the socket method. While started, it listens for the watches on HelloWatch's own Bluetooth
 * service; each watch connects once per recording and sends (watch file CsvSocketSender.kt):
 *
 *   header line, ASCII, ended by '\n':  HWCSV1 <file name> <length> <crc32, hex> <handoverMs>
 *   <length> bytes, the CSV as it is
 *
 * The CSV is saved by SwimFileStorage.saveCsv, under the same name as by the Data Layer, and
 * logged in transfer_log.csv with method Socket. Only then is "OK\n" answered; any problem is
 * answered with "ERR <reason>\n" and the watch keeps its copy and tries again.
 * Each connection is served on its own thread, so both watches can send at the same time.
 */
class PhoneSocketReceiver(
    private val storage: SwimFileStorage,
    private val onCsvSaved: (fileName: String, rowCount: Int, preview: String) -> Unit,
    private val onError: (prefix: String, message: String?) -> Unit
) {
    companion object {
        /** Same ID as CsvSocketProtocol.SERVICE_UUID on the watch. */
        val SERVICE_UUID: UUID = UUID.fromString("7d0c5b0e-3f6a-4c58-9a51-6b1d2f4e8a90")
        private const val SERVICE_NAME = "HelloWatch CSV"
        private const val MAGIC = "HWCSV1"
        private const val MAX_BYTES = 200L * 1024 * 1024
        private const val MAX_HEADER_CHARS = 300
        private const val RESTART_MS = 5_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val transferLog = TransferLog(storage.getLogsDirectory())

    @Volatile private var running = false
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread({ acceptLoop() }, "HelloWatch-socket-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        closeServer()
        acceptThread?.interrupt()
        acceptThread = null
    }

    private fun closeServer() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        serverSocket = null
    }

    private fun post(block: () -> Unit) {
        mainHandler.post { block() }
    }

    /** Listens until stopped; if listening fails (Bluetooth off, no permission) it tries again every 5 s. */
    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        var reported = false // a listening failure is shown once, until listening works again
        while (running) {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter() ?: throw IllegalStateException("no Bluetooth")
                val server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                serverSocket = server
                reported = false
                while (running) {
                    val socket = server.accept()
                    Thread({ serve(socket) }, "HelloWatch-socket-rx").apply {
                        isDaemon = true
                        start()
                    }
                }
            } catch (e: Exception) {
                if (running && !reported) {
                    reported = true
                    val reason = if (e is SecurityException) "no Bluetooth permission" else e.message
                    post { onError("Socket receiver not listening", reason) }
                }
            } finally {
                closeServer()
            }
            if (!running) return
            try {
                Thread.sleep(RESTART_MS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun serve(socket: BluetoothSocket) {
        try {
            socket.use { receive(it.inputStream, it.outputStream) }
        } catch (e: Exception) {
            post { onError("Socket transfer failed", e.message) }
        }
    }

    /** One recording from one connection: reads, checks, saves, answers. Worker thread. */
    fun receive(input: InputStream, out: OutputStream) {
        val startMs = System.currentTimeMillis()
        val answer = try {
            readAndSave(input, startMs)
        } catch (e: EOFException) {
            "ERR connection ended early"
        }
        if (answer != "OK") post { onError("Socket transfer failed", answer) }
        out.write("$answer\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /** "OK" when the file is saved and logged, otherwise "ERR <reason>". */
    private fun readAndSave(input: InputStream, startMs: Long): String {
        val parts = readHeader(input)?.split(" ")
        if (parts == null || parts.size != 5 || parts[0] != MAGIC) return "ERR bad header"
        val length = parts[2].toLongOrNull()
        val crc = parts[3].toLongOrNull(16)
        val handoverMs = parts[4].toLongOrNull() ?: 0L
        if (length == null || length < 0 || length > MAX_BYTES || crc == null) return "ERR bad header"

        val data = ByteArray(length.toInt())
        DataInputStream(input).readFully(data)
        if (CRC32().apply { update(data) }.value != crc) return "ERR checksum"

        val csvContent = String(data, Charsets.UTF_8)
        val (fileName, preview) = try {
            storage.saveCsv(csvContent)
        } catch (e: Exception) {
            return "ERR not saved: ${e.message}"
        }
        transferLog.add(fileName, TransferLog.METHOD_SOCKET, handoverMs, startMs, System.currentTimeMillis())
        // Rows: lines after line 1 and the column header.
        val rowCount = maxOf(0, csvContent.count { it == '\n' } - 2)
        post { onCsvSaved(fileName, rowCount, preview) }
        return "OK"
    }

    /** Header line without its '\n'; null if the stream ends first or the line is too long. */
    private fun readHeader(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length <= MAX_HEADER_CHARS) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return sb.toString()
            sb.append(b.toChar())
        }
        return null
    }
}
