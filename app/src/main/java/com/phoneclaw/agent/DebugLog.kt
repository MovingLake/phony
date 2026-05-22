package com.phoneclaw.agent

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app debug log — captures the last N lines and exposes them as a Flow
 * so the floating debug panel can display them in real time.
 * Only active in debug builds.
 */
object DebugLog {
    private const val MAX_LINES = 30
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val lines = CopyOnWriteArrayList<String>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("D/$tag: $msg")
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        append("E/$tag: $msg${t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append("W/$tag: $msg")
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("I/$tag: $msg")
    }

    fun clear() {
        lines.clear()
        _flow.value = emptyList()
    }

    private fun append(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        lines.add(line)
        if (lines.size > MAX_LINES) lines.removeAt(0)
        _flow.value = lines.toList()
    }
}
