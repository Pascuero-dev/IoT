package com.tuproyecto.smartirrigation.security

// Autor: Benjamín Maldonado

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/*
 * Aquí está todo el cifrado de la app. Seguí las recomendaciones de la ISO/IEC 27400:
 * uso AES-256-GCM, que además de cifrar avisa si alguien modificó el mensaje, y le pongo
 * un IV distinto a cada mensaje. La clave no la dejé escrita en el código: se calcula a
 * partir de la clave que se escribe en los dos teléfonos y solo se guarda en memoria.
 */
object SecurityUtils {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 100_000
    private val SALT = "RiegoIoT-ISO27400-v1".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()
    @Volatile private var key: SecretKeySpec? = null

    fun setPassphrase(passphrase: CharArray) {
        val spec = PBEKeySpec(passphrase, SALT, PBKDF2_ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        key = SecretKeySpec(bytes, "AES")
    }

    fun isReady(): Boolean = key != null

    fun clear() { key = null }

    fun encrypt(plainText: String): String {
        val k = key ?: error("Clave no configurada")
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        // Uso NO_WRAP para que el Base64 no meta saltos de línea, ya que el "\n" separa los mensajes
        return Base64.encodeToString(iv + cipherText, Base64.NO_WRAP)
    }

    // Si la clave no coincide o el mensaje fue alterado, devuelve null
    fun decrypt(encoded: String): String? {
        val k = key ?: return null
        return try {
            val data = Base64.decode(encoded, Base64.NO_WRAP)
            if (data.size <= IV_LENGTH) return null
            val iv = data.copyOfRange(0, IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(data, IV_LENGTH, data.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

data class SecureMessage(val type: String, val value: String)

/*
 * Formato de los mensajes antes de cifrarlos:  TIPO|VALOR|TIMESTAMP|NONCE
 * Los tipos son HUM (humedad), PUMP (ON/OFF) y CMD (REGAR).
 * Para que nadie pueda grabar un mensaje y reenviarlo después, descarto los que tienen
 * más de 60 segundos o cuyo nonce ya vi antes.
 */
object SecureProtocol {
    private const val MAX_SKEW_MS = 60_000L
    private const val MAX_NONCES = 500
    private val seenNonces = LinkedHashSet<String>()

    fun build(type: String, value: String): String {
        val payload = listOf(type, value, System.currentTimeMillis(), UUID.randomUUID()).joinToString("|")
        return SecurityUtils.encrypt(payload)
    }

    @Synchronized
    fun parse(line: String): SecureMessage? {
        val plain = SecurityUtils.decrypt(line.trim()) ?: return null
        val parts = plain.split("|")
        if (parts.size != 4) return null
        val timestamp = parts[2].toLongOrNull() ?: return null
        if (abs(System.currentTimeMillis() - timestamp) > MAX_SKEW_MS) return null
        if (!seenNonces.add(parts[3])) return null
        if (seenNonces.size > MAX_NONCES) {
            val it = seenNonces.iterator(); it.next(); it.remove()
        }
        return SecureMessage(parts[0], parts[1])
    }
}

/*
 * Las contraseñas de los usuarios nunca se guardan tal cual. Guardo solo un hash PBKDF2
 * con una sal aleatoria distinta para cada usuario, así dos personas con la misma
 * contraseña no terminan con el mismo hash en la base de datos.
 */
object PasswordHasher {
    private const val ITERATIONS = 120_000
    private val random = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(16).also { random.nextBytes(it) }

    fun hash(password: CharArray, salt: ByteArray): String {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // MessageDigest.isEqual compara en tiempo constante
    fun matches(password: CharArray, salt: ByteArray, expected: String): Boolean =
        MessageDigest.isEqual(
            hash(password, salt).toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
}
