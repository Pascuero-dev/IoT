package com.tuproyecto.smartirrigation.ui

// Autor: Benjamín Maldonado

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tuproyecto.smartirrigation.R
import com.tuproyecto.smartirrigation.data.AppDatabase
import com.tuproyecto.smartirrigation.security.Session
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Muestra lo que se guardó en SQLite: humedad, estado de la bomba y órdenes de riego
class HistoryActivity : AppCompatActivity() {

    private val formato = SimpleDateFormat("dd/MM HH:mm:ss", Locale("es", "CL"))
    private lateinit var lista: ListView
    private lateinit var tvVacio: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Session.usuario == null) { finish(); return }
        setContentView(R.layout.activity_history)

        lista = findViewById(R.id.lvHistorial)
        tvVacio = findViewById(R.id.tvVacio)
        findViewById<Button>(R.id.btnLimpiar).setOnClickListener {
            AppDatabase.get(this).borrarRegistros()
            cargar()
        }
        cargar()
    }

    private fun cargar() {
        val registros = AppDatabase.get(this).ultimosRegistros()
        val textos = registros.map {
            val etiqueta = when (it.tipo) {
                "HUM" -> "Humedad: ${it.valor}%"
                "BOMBA" -> "Bomba ${if (it.valor == "ON") "encendida" else "apagada"}"
                "ORDEN" -> "Orden de riego (${it.valor})"
                else -> "${it.tipo}: ${it.valor}"
            }
            "${formato.format(Date(it.ts))}   $etiqueta"
        }
        lista.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, textos)
        tvVacio.visibility = if (textos.isEmpty()) View.VISIBLE else View.GONE
    }
}
