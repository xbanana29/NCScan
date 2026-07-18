package com.raj.ncscan

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// ponytail: Keystore AES/GCM tulis-tangan — androidx security-crypto sudah deprecated (Jul 2025),
// rekomendasi Google adalah pakai Android Keystore langsung.
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ncscan", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", "") ?: ""
        // normalkan typo umum "https:/domain" (satu slash) sebelum disimpan
        set(v) {
            val clean = v.trim().trimEnd('/').replace(Regex("^(?i)(https?):/+"), "$1://")
            sp.edit().putString("server", clean).apply()
        }

    var username: String
        get() = sp.getString("user", "") ?: ""
        set(v) { sp.edit().putString("user", v).apply() }

    var folder: String
        get() = sp.getString("folder", "Scans") ?: "Scans"
        set(v) { sp.edit().putString("folder", v).apply() }

    /** 0 = Rendah, 1 = Sedang, 2 = Tinggi */
    var quality: Int
        get() = sp.getInt("quality", 1)
        set(v) { sp.edit().putInt("quality", v).apply() }

    var grayscale: Boolean
        get() = sp.getBoolean("grayscale", false)
        set(v) { sp.edit().putBoolean("grayscale", v).apply() }

    var trustSelfSigned: Boolean
        get() = sp.getBoolean("self_signed", false)
        set(v) { sp.edit().putBoolean("self_signed", v).apply() }

    /** ON = upload ke Nextcloud; OFF = share sheet ke aplikasi lain. */
    var useNextcloud: Boolean
        get() = sp.getBoolean("use_nc", true)
        set(v) { sp.edit().putBoolean("use_nc", v).apply() }

    var appPassword: String
        get() = sp.getString("pw", null)?.let(::decrypt) ?: ""
        set(v) { sp.edit().putString("pw", encrypt(v)).apply() }

    val configured: Boolean
        get() = !useNextcloud ||
            (serverUrl.isNotBlank() && username.isNotBlank() && appPassword.isNotBlank())

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String = try {
        val parts = stored.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        "" // key ke-invalidate / data korup → dianggap belum dikonfigurasi, user isi ulang
    }

    private companion object { const val ALIAS = "ncscan_pw" }
}
