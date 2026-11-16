package com.tuproyecto.smartirrigation.security

// Autor: Benjamín Maldonado

// Guarda quién inició sesión. Solo vive en memoria: si se cierra la app, hay que volver a entrar.
object Session {
    @Volatile var usuario: String? = null

    fun logout() {
        usuario = null
        SecurityUtils.clear()
    }
}
