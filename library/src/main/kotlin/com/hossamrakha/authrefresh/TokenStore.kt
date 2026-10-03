package com.hossamrakha.authrefresh

/**
 * Token persistence behind a 4-method interface — implement with whatever
 * your app already uses (EncryptedSharedPreferences, DataStore, a DB…).
 */
interface TokenStore {
    fun accessToken(): String?
    fun refreshToken(): String?
    fun save(access: String, refresh: String?)
    fun clear()
}
