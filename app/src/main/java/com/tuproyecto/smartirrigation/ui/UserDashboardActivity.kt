package com.tuproyecto.smartirrigation.ui

// Autor: Benjamín Maldonado

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.tuproyecto.smartirrigation.R
import com.tuproyecto.smartirrigation.connection.BluetoothController
import com.tuproyecto.smartirrigation.data.AppDatabase
import com.tuproyecto.smartirrigation.security.SecureProtocol
import com.tuproyecto.smartirrigation.security.Session
import com.tuproyecto.smartirrigation.security.SecurityUtils

// Panel del usuario: muestra la humedad y el estado de la bomba, y permite mandar la orden de riego
class UserDashboardActivity : BaseBluetoothActivity() {

    private lateinit var tvConexion: TextView
    private lateinit var tvHumedad: TextView
    private lateinit var tvBomba: TextView
    private lateinit var btnRegar: Button
    private lateinit var btnConectar: Button

    private var controller: BluetoothController? = null
    private lateinit var db: AppDatabase
    private var ultimoGuardado = 0L

    private val btHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            BluetoothController.MSG_STATE -> onEstado(msg.arg1)
            BluetoothController.MSG_RECEIVED -> onLinea(msg.obj as String)
        }
        true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SecurityUtils.isReady() || Session.usuario == null) { finish(); return }
        setContentView(R.layout.activity_user_dashboard)

        tvConexion = findViewById(R.id.tvConexion)
        tvHumedad = findViewById(R.id.tvHumedadMonitor)
        tvBomba = findViewById(R.id.tvBombaRemota)
        btnRegar = findViewById(R.id.btnRegarManual)
        btnConectar = findViewById(R.id.btnConectar)
        btnRegar.isEnabled = false
        db = AppDatabase.get(this)

        findViewById<Button>(R.id.btnHistorial).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        val adapter = bluetoothAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Este dispositivo no tiene Bluetooth", Toast.LENGTH_LONG).show()
            finish(); return
        }
        controller = BluetoothController(adapter, btHandler)

        btnConectar.setOnClickListener {
            withBluetoothPermissions { if (bluetoothListo()) mostrarDispositivos() }
        }

        btnRegar.setOnClickListener {
            // La orden viaja cifrada y con marca de tiempo para que no se pueda reutilizar
            controller?.write(SecureProtocol.build("CMD", "REGAR"))
            db.guardarRegistro("ORDEN", "enviada por ${Session.usuario}")
            Toast.makeText(this, "Orden de riego enviada", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun mostrarDispositivos() {
        val dispositivos = bluetoothAdapter()?.bondedDevices?.toList().orEmpty()
        if (dispositivos.isEmpty()) {
            Toast.makeText(
                this, "No hay dispositivos emparejados. Empareja el nodo sensor en Ajustes > Bluetooth.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val nombres = dispositivos.map { "${it.name ?: "Sin nombre"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Selecciona el nodo sensor")
            .setItems(nombres) { _, i -> controller?.connect(dispositivos[i]) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun onEstado(state: Int) {
        tvConexion.text = textoEstado(state)
        val conectado = state == BluetoothController.STATE_CONNECTED
        btnRegar.isEnabled = conectado
        btnConectar.isEnabled = state != BluetoothController.STATE_CONNECTING
        if (!conectado) {
            tvHumedad.text = "Humedad recibida: --%"
            tvBomba.text = "Bomba: --"
        }
    }

    private fun onLinea(linea: String) {
        val mensaje = SecureProtocol.parse(linea) ?: return   // si el mensaje no es válido, lo descarto
        when (mensaje.type) {
            "HUM" -> {
                val h = mensaje.value.toIntOrNull() ?: return
                tvHumedad.text = "Humedad recibida: $h%"
                tvHumedad.setTextColor(if (h < 30) Color.parseColor("#C62828") else Color.parseColor("#2E7D32"))
                // Guardo una lectura cada 5 segundos para no llenar la base de datos
                val ahora = System.currentTimeMillis()
                if (ahora - ultimoGuardado >= 5000) {
                    db.guardarRegistro("HUM", h.toString())
                    ultimoGuardado = ahora
                }
            }
            "PUMP" -> {
                tvBomba.text = if (mensaje.value == "ON") "Bomba: ENCENDIDA" else "Bomba: APAGADA"
                db.guardarRegistro("BOMBA", mensaje.value)
            }
        }
    }

    override fun onDestroy() {
        controller?.stop()
        btHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
