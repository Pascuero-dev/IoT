package com.tuproyecto.smartirrigation.data

// Autor: Benjamín Maldonado

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.tuproyecto.smartirrigation.security.PasswordHasher
import android.util.Base64

sealed class LoginResult {
    object Ok : LoginResult()
    object Invalido : LoginResult()
    data class Bloqueado(val segundos: Long) : LoginResult()
}

data class Registro(val ts: Long, val tipo: String, val valor: String)

/*
 * Base de datos local (SQLite). Tiene dos tablas:
 *  - usuarios: para el login sin conexión (guarda hash y sal, nunca la contraseña).
 *  - registros: historial de humedad, estado de la bomba y órdenes de riego.
 * Todas las consultas usan parámetros (?) para evitar inyección SQL.
 */
class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, "riego_iot.db", null, 1) {

    companion object {
        private const val MAX_INTENTOS = 5
        private const val BLOQUEO_MS = 60_000L
        private const val MAX_REGISTROS = 1000

        @Volatile private var instancia: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instancia ?: synchronized(this) {
                instancia ?: AppDatabase(context.applicationContext).also { instancia = it }
            }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE usuarios (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                usuario TEXT NOT NULL UNIQUE,
                salt TEXT NOT NULL,
                hash TEXT NOT NULL,
                intentos INTEGER NOT NULL DEFAULT 0,
                bloqueado_hasta INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE registros (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ts INTEGER NOT NULL,
                tipo TEXT NOT NULL,
                valor TEXT NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS usuarios")
        db.execSQL("DROP TABLE IF EXISTS registros")
        onCreate(db)
    }

    // ---------------- Usuarios ----------------

    /** Devuelve false si el usuario ya existe. */
    fun registrar(usuario: String, password: CharArray): Boolean {
        val salt = PasswordHasher.newSalt()
        val values = ContentValues().apply {
            put("usuario", usuario)
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("hash", PasswordHasher.hash(password, salt))
        }
        return writableDatabase.insert("usuarios", null, values) != -1L
    }

    fun login(usuario: String, password: CharArray): LoginResult {
        val db = writableDatabase
        val ahora = System.currentTimeMillis()

        var salt: String? = null
        var hash = ""
        var intentos = 0
        var bloqueadoHasta = 0L
        db.rawQuery(
            "SELECT salt, hash, intentos, bloqueado_hasta FROM usuarios WHERE usuario = ?",
            arrayOf(usuario)
        ).use { c ->
            if (c.moveToFirst()) {
                salt = c.getString(0); hash = c.getString(1)
                intentos = c.getInt(2); bloqueadoHasta = c.getLong(3)
            }
        }

        val saltGuardada = salt
        if (saltGuardada == null) {
            // Si el usuario no existe igual calculo un hash para que no se note la diferencia de tiempo
            PasswordHasher.hash(password, ByteArray(16))
            return LoginResult.Invalido
        }

        if (bloqueadoHasta > ahora) {
            return LoginResult.Bloqueado((bloqueadoHasta - ahora) / 1000 + 1)
        }

        val saltBytes = Base64.decode(saltGuardada, Base64.NO_WRAP)
        if (PasswordHasher.matches(password, saltBytes, hash)) {
            db.execSQL(
                "UPDATE usuarios SET intentos = 0, bloqueado_hasta = 0 WHERE usuario = ?",
                arrayOf(usuario)
            )
            return LoginResult.Ok
        }

        intentos++
        return if (intentos >= MAX_INTENTOS) {
            db.execSQL(
                "UPDATE usuarios SET intentos = 0, bloqueado_hasta = ? WHERE usuario = ?",
                arrayOf<Any>(ahora + BLOQUEO_MS, usuario)
            )
            LoginResult.Bloqueado(BLOQUEO_MS / 1000)
        } else {
            db.execSQL(
                "UPDATE usuarios SET intentos = ? WHERE usuario = ?",
                arrayOf<Any>(intentos, usuario)
            )
            LoginResult.Invalido
        }
    }

    // ---------------- Registros del sensor ----------------

    fun guardarRegistro(tipo: String, valor: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("tipo", tipo)
            put("valor", valor)
        }
        db.insert("registros", null, values)
        // Para que la base no crezca sin límite, dejo solo los últimos registros
        db.execSQL(
            "DELETE FROM registros WHERE id <= (SELECT MAX(id) FROM registros) - ?",
            arrayOf<Any>(MAX_REGISTROS)
        )
    }

    fun ultimosRegistros(limite: Int = 100): List<Registro> {
        val lista = mutableListOf<Registro>()
        readableDatabase.rawQuery(
            "SELECT ts, tipo, valor FROM registros ORDER BY id DESC LIMIT ?",
            arrayOf(limite.toString())
        ).use { c ->
            while (c.moveToNext()) lista.add(Registro(c.getLong(0), c.getString(1), c.getString(2)))
        }
        return lista
    }

    fun borrarRegistros() {
        writableDatabase.execSQL("DELETE FROM registros")
    }
}
