package com.github.libretube.helpers

import android.content.Context
import android.media.AudioManager
import androidx.core.content.getSystemService
import com.github.libretube.extensions.normalize

class AudioHelper(context: Context) {

    private val audioManager = context.getSystemService<AudioManager>()!!

    private val minVolume = 0

    private val maxVolume =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    var volume: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        set(value) {
            val clamped = value.coerceIn(minVolume, maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        }

    fun setVolumeWithScale(value: Int, maxValue: Int, minValue: Int = 0) {
        volume = value.normalize(
            minValue,
            maxValue,
            minVolume,
            maxVolume
        )
    }

    fun getVolumeWithScale(maxValue: Int, minValue: Int = 0): Int {
        return volume.normalize(
            minVolume,
            maxVolume,
            minValue,
            maxValue
        )
    }
}