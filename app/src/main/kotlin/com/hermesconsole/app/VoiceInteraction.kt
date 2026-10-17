package com.hermesconsole.app

import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The assistant-role trio (REQ-25). Holding the device's digital-assistant role is what stops the
 * OS from treating the console's open microphone as a suspicious app behavior; the role itself is
 * granted in system settings, and these three services are the minimum the settings picker will
 * accept as a candidate.
 *
 * They are deliberately inert. The console's real voice path is the VoiceLoop over VoiceManager -
 * it does not route through the Android voice-interaction stack at all - so each class here is
 * the smallest valid implementation of its contract and nothing more. The system requires all
 * three to exist (a VoiceInteractionService whose meta-data names both a session service and a
 * recognition service) before it will list the app as an assistant; see
 * res/xml/voice_interaction_service.xml for the meta-data that ties them together.
 */
class ConsoleVoiceInteractionService : VoiceInteractionService()

/** Required by the role contract; a session that shows nothing and does nothing is still valid. */
class ConsoleVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = VoiceInteractionSession(this)
}

/**
 * Required by the role contract, never used: speech recognition happens on the VoiceManager
 * backend (REQ-04), not on the tablet. Any caller that reaches this anyway is told "no" rather
 * than left hanging.
 */
class ConsoleRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        try {
            listener.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: RemoteException) {
            // The caller died mid-refusal; there is nobody left to tell.
        }
    }

    override fun onCancel(listener: Callback) = Unit

    override fun onStopListening(listener: Callback) = Unit
}
