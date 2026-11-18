/*
 * Yowza! — big alerts for rare messages.
 * Copyright (C) 2026 Rob Nugen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.robnugen.yowza

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the JMAP API token encrypted with an AES key that lives in the Android Keystore
 * (the key never leaves secure hardware where available). The token is never logged.
 */
object TokenStore {
    const val DEFAULT_SESSION_URL = "https://api.fastmail.com/jmap/session"

    private const val PREFS = "connection"
    private const val KEY_ALIAS = "yowza_token_key"
    private const val PREF_TOKEN = "token_ciphertext"
    private const val PREF_IV = "token_iv"
    private const val PREF_SESSION_URL = "session_url"

    fun sessionUrl(context: Context): String =
        prefs(context).getString(PREF_SESSION_URL, null) ?: DEFAULT_SESSION_URL

    fun hasToken(context: Context): Boolean = prefs(context).contains(PREF_TOKEN)

    fun save(context: Context, token: String, sessionUrl: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(PREF_TOKEN, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(PREF_SESSION_URL, sessionUrl)
            .apply()
    }

    /** The decrypted token, or null if none is saved or it can't be decrypted. */
    fun load(context: Context): String? {
        val p = prefs(context)
        val ct = p.getString(PREF_TOKEN, null) ?: return null
        val iv = p.getString(PREF_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key was wiped (e.g. lock-screen reset). Treat as no token.
            null
        }
    }

    fun forget(context: Context) {
        prefs(context).edit().remove(PREF_TOKEN).remove(PREF_IV).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }
}
