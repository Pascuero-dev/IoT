package com.tuproyecto.smartirrigation.connection

// Autor: Benjamín Maldonado

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/*
 * Maneja la conexión Bluetooth entre los dos teléfonos (RFCOMM seguro).
 * El nodo sensor se queda esperando con startServer() y el panel del usuario se conecta
 * con connect(device). Los mensajes se mandan como una línea de texto terminada en "\n",
 * y ya van cifrados desde SecureProtocol. Los permisos los pido en las Activities.
 */
@SuppressLint("MissingPermission")
class BluetoothController(
    private val adapter: BluetoothAdapter,
    private val handler: Handler
) {
    companion object {
        const val MSG_STATE = 1      // arg1 trae el estado
        const val MSG_RECEIVED = 2   // obj trae la línea recibida

        const val STATE_NONE = 0
        const val STATE_LISTENING = 1
        const val STATE_CONNECTING = 2
        const val STATE_CONNECTED = 3

        private const val SERVICE_NAME = "RiegoIoT"
        private const val MAX_LINE = 4096
        val APP_UUID: UUID = UUID.fromString("8f1b2c3d-4e5f-4a6b-9c7d-0e1f2a3b4c5d")
    }

    @Volatile var state = STATE_NONE
        private set

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null
    private val writer = Executors.newSingleThreadExecutor()

    private fun setState(s: Int) {
        state = s
        handler.obtainMessage(MSG_STATE, s, -1).sendToTarget()
    }

    @Synchronized
    fun startServer() {
        cancelAll()
        acceptThread = AcceptThread().also { it.start() }
        setState(STATE_LISTENING)
    }

    @Synchronized
    fun connect(device: BluetoothDevice) {
        cancelAll()
        connectThread = ConnectThread(device).also { it.start() }
        setState(STATE_CONNECTING)
    }

    @Synchronized
    private fun onConnected(socket: BluetoothSocket) {
        connectThread = null                      // desde acá el socket lo usa ConnectedThread
        acceptThread?.cancel(); acceptThread = null
        connectedThread?.cancel()
        connectedThread = ConnectedThread(socket).also { it.start() }
        setState(STATE_CONNECTED)
    }

    @Synchronized
    private fun cancelAll() {
        connectThread?.cancel(); connectThread = null
        acceptThread?.cancel(); acceptThread = null
        connectedThread?.cancel(); connectedThread = null
    }

    // Se llama cuando un hilo termina por error o desconexión. Si ya lo reemplazamos, lo ignoro
    @Synchronized
    private fun threadEnded(t: Thread) {
        when {
            t === acceptThread -> acceptThread = null
            t === connectThread -> connectThread = null
            t === connectedThread -> connectedThread = null
            else -> return
        }
        setState(STATE_NONE)
    }

    fun write(line: String) {
        val t = synchronized(this) { connectedThread } ?: return
        try {
            writer.execute { t.write(line) }
        } catch (e: RejectedExecutionException) { /* el controlador ya se detuvo */ }
    }

    @Synchronized
    fun stop() {
        cancelAll()
        setState(STATE_NONE)
        writer.shutdown()
    }

    // ---- Servidor (lo usa el nodo sensor) ----
    private inner class AcceptThread : Thread() {
        private val server: BluetoothServerSocket? = try {
            adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
        } catch (e: Exception) { null }

        override fun run() {
            val s = server
            if (s == null) { threadEnded(this); return }
            try {
                val socket = s.accept()
                s.close()
                onConnected(socket)
            } catch (e: Exception) {
                cancel()
                threadEnded(this)
            }
        }

        fun cancel() { try { server?.close() } catch (_: IOException) {} }
    }

    // ---- Cliente (lo usa el panel del usuario) ----
    private inner class ConnectThread(device: BluetoothDevice) : Thread() {
        private val socket: BluetoothSocket? = try {
            device.createRfcommSocketToServiceRecord(APP_UUID)
        } catch (e: Exception) { null }

        override fun run() {
            val s = socket
            if (s == null) { threadEnded(this); return }
            // No llamo a cancelDiscovery(): nunca inicio una búsqueda, y en Android 12+
            // esa función exigiría el permiso BLUETOOTH_SCAN, que la app no necesita.
            try {
                s.connect()
                onConnected(s)
            } catch (e: Exception) {
                cancel()
                threadEnded(this)
            }
        }

        fun cancel() { try { socket?.close() } catch (_: IOException) {} }
    }

    // ---- Envío y recepción de datos ----
    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        private val out = socket.outputStream

        override fun run() {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank() && line.length <= MAX_LINE) {
                        handler.obtainMessage(MSG_RECEIVED, line).sendToTarget()
                    }
                }
            } catch (e: IOException) { /* se cayó la conexión */ }
            cancel()
            threadEnded(this)
        }

        fun write(line: String) {
            try {
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (e: IOException) {
                cancel()
            }
        }

        fun cancel() { try { socket.close() } catch (_: IOException) {} }
    }
}
