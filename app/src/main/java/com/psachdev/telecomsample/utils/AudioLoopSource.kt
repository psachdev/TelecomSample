/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.psachdev.telecomsample.utils

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

/**
 * Audio Looping, uses AudioRecord and Audio track to loop audio from microphone back to output device
 * Used for testing microphones and speakers
 */
class AudioLoopSource {

    //Scope used for getting buffer from Audio recorder to audio track
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    val isRecording = MutableStateFlow(false)

    companion object {
        private const val sampleRate = 48000

        private val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        private val audioTrackBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        var audioSampler: AudioRecord? = null

        //Audio track for audio playback
        private val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(audioTrackBufferSize)
                .build()
        } else {
            AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                audioTrackBufferSize,
                AudioTrack.MODE_STREAM,
            )
        }

        /**
         * Opens the active mic and loops it back to the selected active audio device.
         * When the scope is closed the mic and audio will be closed
         *
         * @throws IllegalStateException if AudioRecord couldn't be initialized
         */
        @RequiresPermission(Manifest.permission.RECORD_AUDIO)
        suspend fun openAudioLoop(preferredDevice: Flow<AudioDeviceInfo> = emptyFlow()) {
            // Init the recorder and audio
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val audioTrackBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(audioTrackBufferSize)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    audioTrackBufferSize,
                    AudioTrack.MODE_STREAM,
                )
            }
            val audioSampler = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            audioTrack.playbackRate = sampleRate

            try {
                // Launch in a new context the loop
                withContext(Dispatchers.IO) {
                    // Collect changes of preferred device to loop the audio
                    launch {
                        preferredDevice.collect { device ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                audioTrack.preferredDevice = device
                                audioSampler.preferredDevice = device
                            } else {
                                //Not required AudioManger will deal with routing in the PlatformAudioSource class
                            }
                        }
                    }

                    check(audioSampler.state == AudioRecord.STATE_INITIALIZED) {
                        "Audio recorder was not properly initialized"
                    }
                    audioSampler.startRecording()

                    val audioData = ByteArray(bufferSize)
                    audioTrack.play()

                    while (isActive) {
                        val bytesRead = audioSampler.read(audioData, 0, bufferSize)
                        if (bytesRead > 0) {
                            audioTrack.write(audioData, 0, bytesRead)
                        }
                    }
                }
            } finally {
                audioTrack.stop()
                audioSampler.stop()
            }
        }
    }

    /**
     * Gets buffer from Audio Recorder and loops back to the audio track
     */
    @SuppressLint("MissingPermission")
    fun startAudioLoop(): Boolean {
        Log.d("MPB", "startAudioLoop")

        if (audioSampler == null) {
            audioSampler = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }

        if (audioSampler?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            return false
        }

        audioTrack.playbackRate = sampleRate

        job = coroutineScope.launch {
            if (audioSampler?.state == AudioRecord.STATE_INITIALIZED) {
                audioSampler?.startRecording()
            }

            val data = ByteArray(bufferSize)
            audioTrack.play()

            isRecording.update { true }

            while (isActive) {

                val bytesRead = audioSampler!!.read(data, 0, bufferSize)

                if (bytesRead > 0) {
                    audioTrack.write(data, 0, bytesRead)
                }
            }
        }

        return true
    }

    /**
     * Stops current job and releases microphone and audio devices
     */
    fun stopAudioLoop() {
        Log.d("MPB", "stopAudioLoop")
        job?.cancel("Stop Recording", null)
        isRecording.update { false }
        if (audioSampler?.state == AudioRecord.STATE_INITIALIZED) {
            audioSampler?.stop()
        }
        audioTrack.stop()
    }

    /**
     * Set the audio device to record and playback with
     */
    fun setPreferredDevice(audioDeviceInfo: AudioDeviceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioTrack.preferredDevice = audioDeviceInfo
            audioSampler?.preferredDevice = audioDeviceInfo
        } else {
            //Not required AudioManger will deal with routing in the PlatformAudioSource class
        }
    }
}