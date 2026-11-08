package app.partitura

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Cofre do app: a chave do servidor é guardada CRIPTOGRAFADA (AES-256-GCM) com uma chave que fica no Keystore do Android
 * e nunca sai do aparelho. Quem copiar os arquivos do app (ou um backup) só vê texto cifrado inútil.
 * Também guarda o PIN do app (só o hash PBKDF2 com sal), usado quando o tablet não tem bloqueio de tela.
 */
object Vault {
    private const val ALIAS = "compasso_vault_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    /** Texto → "iv:cifrado" em base64. Devolve null se o Keystore não estiver disponível. */
    fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    } catch (e: Exception) { null }

    fun decrypt(blob: String): String? = try {
        val (iv, ct) = blob.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct))
    } catch (e: Exception) { null }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)).encoded

    /** "sal:hash" para guardar. */
    fun hashPin(pin: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(salt, Base64.NO_WRAP) + ":" + Base64.encodeToString(pbkdf2(pin, salt), Base64.NO_WRAP)
    }

    fun checkPin(pin: String, stored: String): Boolean = try {
        val (s, h) = stored.split(":")
        java.security.MessageDigest.isEqual(pbkdf2(pin, Base64.decode(s, Base64.NO_WRAP)), Base64.decode(h, Base64.NO_WRAP))
    } catch (e: Exception) { false }
}

