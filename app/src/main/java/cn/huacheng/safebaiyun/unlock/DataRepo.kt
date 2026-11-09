package cn.huacheng.safebaiyun.unlock

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import cn.huacheng.safebaiyun.util.ContextHolder

/**
 * 本地配置存储：门禁 MAC / Key，以及最近一次开门结果。
 */
object DataRepo {

    private const val KEY_MAC = "mac"
    private const val KEY_PRODUCT_KEY = "key"
    private const val KEY_LAST_SUCCESS = "last_success"
    private const val KEY_LAST_TIME = "last_time"
    private const val KEY_LAST_REASON = "last_reason"
    private const val KEY_LAST_DETAIL = "last_detail"

    private val preferences: SharedPreferences by lazy {
        ContextHolder.get().getSharedPreferences("data", Context.MODE_PRIVATE)
    }

    fun readData(): Pair<String, String> {
        val mac = preferences.getString(KEY_MAC, "") ?: ""
        val key = preferences.getString(KEY_PRODUCT_KEY, "") ?: ""
        return mac to key
    }

    fun save(mac: String, key: String) {
        preferences.edit {
            putString(KEY_MAC, mac)
            putString(KEY_PRODUCT_KEY, key)
        }
    }

    fun isConfigured(): Boolean {
        val (mac, key) = readData()
        return mac.isNotBlank() && key.isNotBlank()
    }

    fun saveLastResult(last: LastUnlock) {
        preferences.edit {
            putBoolean(KEY_LAST_SUCCESS, last.success)
            putLong(KEY_LAST_TIME, last.at)
            if (last.reason != null) {
                putString(KEY_LAST_REASON, last.reason.name)
            } else {
                remove(KEY_LAST_REASON)
            }
            if (last.detail != null) {
                putString(KEY_LAST_DETAIL, last.detail)
            } else {
                remove(KEY_LAST_DETAIL)
            }
        }
    }

    fun lastResult(): LastUnlock? {
        val time = preferences.getLong(KEY_LAST_TIME, 0L)
        if (time <= 0L) return null
        val reason = preferences.getString(KEY_LAST_REASON, null)?.let {
            runCatching { UnlockFailure.valueOf(it) }.getOrNull()
        }
        return LastUnlock(
            success = preferences.getBoolean(KEY_LAST_SUCCESS, false),
            at = time,
            reason = reason,
            detail = preferences.getString(KEY_LAST_DETAIL, null)
        )
    }
}
