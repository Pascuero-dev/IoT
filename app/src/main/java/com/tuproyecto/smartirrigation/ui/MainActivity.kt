package com.tuproyecto.smartirrigation.ui

// Autor: Benjamín Maldonado

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.tuproyecto.smartirrigation.R
import com.tuproyecto.smartirrigation.security.SecurityUtils
import com.tuproyecto.smartirrigation.security.Session
import android.widget.TextView

// Pantalla de inicio: se escribe la clave compartida y se elige qué papel cumple el teléfono
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.usuario == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish(); return
        }
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.tvSesion).text = "Sesión iniciada como: ${Session.usuario}"
        findViewById<Button>(R.id.btnSalir).setOnClickListener {
            Session.logout()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }

        val etClave = findViewById<EditText>(R.id.etClave)

        fun iniciar(destino: Class<*>) {
            val clave = etClave.text.toString()
            if (clave.length < 8) {
                etClave.error = "Mínimo 8 caracteres"
                return
            }
            SecurityUtils.setPassphrase(clave.toCharArray())
            startActivity(Intent(this, destino))
        }

        findViewById<Button>(R.id.btnSensor).setOnClickListener { iniciar(SensorNodeActivity::class.java) }
        findViewById<Button>(R.id.btnUsuario).setOnClickListener { iniciar(UserDashboardActivity::class.java) }
    }
}
