package com.tuproyecto.smartirrigation.ui

// Autor: Benjamín Maldonado

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.tuproyecto.smartirrigation.connection.BluetoothController

// Cosas que usan las dos pantallas: obtener el Bluetooth y pedir el permiso (Android 12+)
abstract class BaseBluetoothActivity : AppCompatActivity() {

    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                pendingAction?.invoke()
            } else {
                Toast.makeText(this, "Se necesita el permiso de Bluetooth", Toast.LENGTH_LONG).show()
            }
            pendingAction = null
        }

    protected fun bluetoothAdapter(): BluetoothAdapter? =
        getSystemService(BluetoothManager::class.java)?.adapter

    // Ejecuta la acción solo cuando ya tenemos el permiso BLUETOOTH_CONNECT
    protected fun withBluetoothPermissions(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { action(); return }
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        }
    }

    protected fun bluetoothListo(): Boolean {
        val adapter = bluetoothAdapter()
        return if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Activa el Bluetooth del teléfono", Toast.LENGTH_LONG).show()
            false
        } else true
    }

    protected fun textoEstado(state: Int): String = when (state) {
        BluetoothController.STATE_LISTENING -> "Esperando conexión del panel de control..."
        BluetoothController.STATE_CONNECTING -> "Conectando..."
        BluetoothController.STATE_CONNECTED -> "Conectado (canal cifrado)"
        else -> "Desconectado"
    }
}
