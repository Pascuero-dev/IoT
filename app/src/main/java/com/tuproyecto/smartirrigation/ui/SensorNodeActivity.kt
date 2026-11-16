package com.tuproyecto.smartirrigation.ui

// Autor: Benjamín Maldonado

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.tuproyecto.smartirrigation.R
import com.tuproyecto.smartirrigation.connection.BluetoothController
import com.tuproyecto.smartirrigation.security.SecureProtocol
import com.tuproyecto.smartirrigation.security.SecurityUtils
import com.tuproyecto.smartirrigation.security.Session

/*
 * Este teléfono hace de invernadero. La humedad se cambia a mano con la barra y se manda
 * cifrada al panel cada segundo. La bomba se prende sola si la humedad baja del umbral,
 * o cuando llega una orden de riego válida desde el panel.
 */
class SensorNodeActivity : BaseBluetoothActivity() {

    companion object {
        private const val UMBRAL_HUMEDAD = 30
        private const val DURACION_BOMBA_MS = 5000L
        private const val TICK_MS = 1000L
        private const val INCREMENTO_RIEGO = 4
    }

    private lateinit var tvHumedad: TextView
    private lateinit var tvBomba: TextView
    private lateinit var tvConexion: TextView
    private lateinit var sbHumedad: SeekBar

    private var controller: BluetoothController? = null
    private var bombaEncendida = false
    private val ui = Handler(Looper.getMainLooper())

    private val btHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            BluetoothController.MSG_STATE -> onEstado(msg.arg1)
            BluetoothController.MSG_RECEIVED -> onLinea(msg.obj as String)
        }
        true
    }

    private val apagarBomba = Runnable {
        bombaEncendida = false
        mostrarBomba(null)
        enviarBomba()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (bombaEncendida) {
                // Simulo que al regar la humedad va subiendo
                sbHumedad.progress = minOf(100, sbHumedad.progress + INCREMENTO_RIEGO)
            } else if (sbHumedad.progress < UMBRAL_HUMEDAD) {
                activarBomba("automático")
            }
            enviarHumedad()
            ui.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SecurityUtils.isReady() || Session.usuario == null) { finish(); return }
        setContentView(R.layout.activity_sensor_node)

        tvHumedad = findViewById(R.id.tvHumedadActual)
        tvBomba = findViewById(R.id.tvEstadoBomba)
        tvConexion = findViewById(R.id.tvConexion)
        sbHumedad = findViewById(R.id.sbHumedad)

        sbHumedad.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvHumedad.text = "Humedad del Suelo: $progress%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val adapter = bluetoothAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Este dispositivo no tiene Bluetooth", Toast.LENGTH_LONG).show()
            finish(); return
        }
        controller = BluetoothController(adapter, btHandler)
        withBluetoothPermissions { if (bluetoothListo()) controller?.startServer() }

        ui.postDelayed(tick, TICK_MS)
    }

    private fun onEstado(state: Int) {
        tvConexion.text = textoEstado(state)
        when (state) {
            BluetoothController.STATE_CONNECTED -> { enviarHumedad(); enviarBomba() }
            BluetoothController.STATE_NONE -> {
                // Se perdió la conexión, así que vuelvo a esperar al panel
                if (!isFinishing && !isDestroyed) {
                    ui.postDelayed({
                        if (!isFinishing && !isDestroyed && bluetoothAdapter()?.isEnabled == true) {
                            controller?.startServer()
                        }
                    }, 1500)
                }
            }
        }
    }

    private fun onLinea(linea: String) {
        val mensaje = SecureProtocol.parse(linea)
        if (mensaje == null) {
            tvConexion.text = "Mensaje rechazado (clave inválida, alterado o repetido)"
            return
        }
        if (mensaje.type == "CMD" && mensaje.value == "REGAR") {
            activarBomba("manual")
        }
    }

    private fun activarBomba(origen: String) {
        if (bombaEncendida) return
        bombaEncendida = true
        mostrarBomba(origen)
        enviarBomba()
        ui.postDelayed(apagarBomba, DURACION_BOMBA_MS)
    }

    private fun mostrarBomba(origen: String?) {
        if (bombaEncendida) {
            tvBomba.text = "ESTADO BOMBA: ENCENDIDA ($origen)"
            tvBomba.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            tvBomba.text = "ESTADO BOMBA: APAGADA"
            tvBomba.setTextColor(Color.parseColor("#C62828"))
        }
    }

    private fun conectado() = controller?.state == BluetoothController.STATE_CONNECTED

    private fun enviarHumedad() {
        if (conectado()) controller?.write(SecureProtocol.build("HUM", sbHumedad.progress.toString()))
    }

    private fun enviarBomba() {
        if (conectado()) controller?.write(SecureProtocol.build("PUMP", if (bombaEncendida) "ON" else "OFF"))
    }

    override fun onDestroy() {
        controller?.stop()
        ui.removeCallbacksAndMessages(null)
        btHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
