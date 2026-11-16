package com.tuproyecto.smartirrigation.ui

// Autor: Benjamín Maldonado

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tuproyecto.smartirrigation.R
import com.tuproyecto.smartirrigation.data.AppDatabase
import com.tuproyecto.smartirrigation.data.LoginResult
import com.tuproyecto.smartirrigation.security.Session

// Pantalla de login. Valida contra SQLite y bloquea la cuenta 60 s tras 5 intentos fallidos
class LoginActivity : AppCompatActivity() {

    private val regexUsuario = Regex("^[a-z0-9_]{3,20}$")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val db = AppDatabase.get(this)
        val etUsuario = findViewById<EditText>(R.id.etUsuario)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val tvMensaje = findViewById<TextView>(R.id.tvMensaje)

        findViewById<Button>(R.id.btnIngresar).setOnClickListener {
            val usuario = etUsuario.text.toString().trim().lowercase()
            val password = etPassword.text.toString().toCharArray()
            etPassword.text.clear()

            when (val resultado = db.login(usuario, password)) {
                is LoginResult.Ok -> {
                    Session.usuario = usuario
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
                is LoginResult.Bloqueado ->
                    tvMensaje.text = "Demasiados intentos. Espera ${resultado.segundos} s."
                is LoginResult.Invalido ->
                    tvMensaje.text = "Usuario o contraseña incorrectos"
            }
            password.fill('0')
        }

        findViewById<Button>(R.id.btnRegistrar).setOnClickListener {
            val usuario = etUsuario.text.toString().trim().lowercase()
            val password = etPassword.text.toString().toCharArray()
            etPassword.text.clear()

            when {
                !regexUsuario.matches(usuario) ->
                    tvMensaje.text = "Usuario: 3 a 20 caracteres (letras, números o _)"
                password.size < 8 ->
                    tvMensaje.text = "La contraseña debe tener al menos 8 caracteres"
                db.registrar(usuario, password) -> {
                    tvMensaje.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
                    tvMensaje.text = "Cuenta creada. Ahora puedes ingresar."
                }
                else -> {
                    tvMensaje.setTextColor(android.graphics.Color.parseColor("#C62828"))
                    tvMensaje.text = "Ese usuario ya existe"
                }
            }
            password.fill('0')
        }
    }
}
