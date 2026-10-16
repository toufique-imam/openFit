package com.openfit.ui

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One-off messages for a toast, from any screen's model; [com.openfit.MainActivity] shows them. */
object Notices {
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    private var last: Pair<Int, Long>? = null

    fun post(@StringRes message: Int) {
        _messages.tryEmit(message)
    }

    /** Posts [message] unless it went out within [QUIET_MS]: a screen loading a dozen series should toast once. */
    fun postOnce(@StringRes message: Int) = synchronized(this) {
        val now = System.currentTimeMillis()
        if (last?.let { it.first == message && now - it.second < QUIET_MS } == true) return
        last = message to now
        post(message)
    }

    private const val QUIET_MS = 30_000L
}
