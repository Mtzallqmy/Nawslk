package com.nawslk.app.calls

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.os.Build

class CallAudioController(context: Context, private val onInterruption: () -> Unit = {}) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var request: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    fun activate(): Boolean {
        previousMode = manager.mode
        previousSpeaker = manager.isSpeakerphoneOn
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { if (it < 0) onInterruption() }.build()
        request = focus
        if (manager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { release(); return false }
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        return true
    }
    fun speaker(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= 31) {
            if (enabled) manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }?.let { manager.setCommunicationDevice(it) }
            else manager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = enabled
        }
    }
    fun release() {
        request?.let { manager.abandonAudioFocusRequest(it) }
        request = null
        if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
        else { @Suppress("DEPRECATION") manager.isSpeakerphoneOn = previousSpeaker }
        manager.mode = previousMode
    }
}
