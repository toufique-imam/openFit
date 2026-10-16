package com.openfit.ui

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One-off messages for a toast, from any screen's model; [com.openfit.MainActivity] shows them. */
object Notices {
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun post(@StringRes message: Int) {
        _messages.tryEmit(message)
    }
}
