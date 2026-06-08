package com.glasscast.sender

import android.util.Log

object SafeLog {
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable == null) Log.d(tag, message) else Log.d(tag, message, throwable)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
        }
    }
}
