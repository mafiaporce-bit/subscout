package com.jpsums.subscout

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The connected mailboxes. App passwords are encrypted with a key that never leaves this phone. */
object Accounts {
    data class Account(val id: String, val email: String, val host: String, val port: Int, val pass: String)

    private const val ALIAS = "subscout_mail_key"
    private fun prefs(c: Context) = c.getSharedPreferences("subscout", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val existing = ks.getKey(ALIAS, null)
        if (existing is SecretKey) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return kg.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    private fun open(sealed: String): String {
        val parts = sealed.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    private fun raw(c: Context): JSONArray =
        try { JSONArray(prefs(c).getString("accounts", "[]")) } catch (_: Exception) { JSONArray() }

    /** For the screen: never includes the password. */
    fun listPublic(c: Context): JSONArray {
        val out = JSONArray(); val arr = raw(c)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.put(JSONObject().put("id", o.getString("id")).put("email", o.getString("email")).put("host", o.getString("host")))
        }
        return out
    }

    fun get(c: Context, id: String): Account? {
        val arr = raw(c)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") == id) return Account(id, o.getString("email"), o.getString("host"), o.optInt("port", 993), open(o.getString("sealed")))
        }
        return null
    }

    fun add(c: Context, email: String, host: String, port: Int, pass: String): String {
        val arr = raw(c); val out = JSONArray()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (!o.getString("email").equals(email, true)) out.put(o) }
        val id = "a" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        out.put(JSONObject().put("id", id).put("email", email).put("host", host).put("port", port).put("sealed", seal(pass)))
        prefs(c).edit().putString("accounts", out.toString()).apply()
        return id
    }

    fun remove(c: Context, id: String) {
        val arr = raw(c); val out = JSONArray()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); if (o.getString("id") != id) out.put(o) }
        prefs(c).edit().putString("accounts", out.toString()).apply()
    }
}
