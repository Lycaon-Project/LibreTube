package com.github.libretube.util

import android.os.Handler
import android.os.Looper
import java.util.Timer
import java.util.TimerTask

class PauseableTimer(
    private val onTick: () -> Unit,
    private val delayMillis: Long = 1000L
) {
    val handler: Handler = Handler(Looper.getMainLooper())

    private var timer: Timer? = null
    private var isRunning = false

    init {
        resume()
    }

    fun resume() {
        if (timer == null) {
            timer = Timer()
        }
        isRunning = true
        scheduleNext()
    }

    /**
     * Schedule the next tick with manual re-scheduling
     * This avoids the "catch-up" behavior of scheduleAtFixedRate when process is uncached
     */
    private fun scheduleNext() {
        if (!isRunning) return

        timer?.schedule(object : TimerTask() {
            override fun run() {
                handler.post {
                    onTick()
                    // Re-schedule for next execution only if still running
                    if (isRunning) {
                        scheduleNext()
                    }
                }
            }
        }, delayMillis)
    }

    fun pause() {
        isRunning = false
        timer?.cancel()
        timer = null
    }

    fun destroy() {
        pause()
    }
}