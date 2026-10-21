package com.ifanotif.ifanotif.ifa

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Phase(val label: String) {
    STOPPED("Off"),
    SEARCHING("Looking for your PC…"),
    CONNECTING("Connecting…"),
    IDLE("Ready"),
    LISTENING("Listening…"),
    THINKING("Thinking…"),
    SPEAKING("Speaking…")
}

data class ChatLine(val fromUser: Boolean, val text: String)

/** Teaching Ifa your voice: which phrase is next and how it went. */
data class Enrollment(
    val phrases: List<String> = emptyList(),
    val index: Int = 0,
    val status: String = "Getting ready…",
    val finished: Boolean = false,
    val ok: Boolean = false,
)

/** What the service is doing, observed by the UI. */
object IfaState {
    private val _phase = MutableStateFlow(Phase.STOPPED)
    val phase = _phase.asStateFlow()

    private val _pcName = MutableStateFlow<String?>(null)
    val pcName = _pcName.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)
    val problem = _problem.asStateFlow()

    private val _chat = MutableStateFlow<List<ChatLine>>(emptyList())
    val chat = _chat.asStateFlow()

    private val _wakeScore = MutableStateFlow(0f)
    val wakeScore = _wakeScore.asStateFlow()

    private val _wakePeak = MutableStateFlow(0f)
    val wakePeak = _wakePeak.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel = _micLevel.asStateFlow()

    private val _micGain = MutableStateFlow(1f)
    val micGain = _micGain.asStateFlow()

    fun setPhase(phase: Phase) {
        _phase.value = phase
    }

    fun setPcName(name: String?) {
        _pcName.value = name
    }

    fun setProblem(problem: String?) {
        _problem.value = problem
    }

    fun addChat(fromUser: Boolean, text: String) {
        _chat.value = (_chat.value + ChatLine(fromUser, text)).takeLast(MAX_CHAT)
    }

    fun setWakeScore(score: Float) {
        _wakeScore.value = score
        if (score > _wakePeak.value) _wakePeak.value = score
    }

    fun setMicLevel(level: Float) {
        _micLevel.value = level
    }

    fun setMicGain(gain: Float) {
        _micGain.value = gain
    }

    fun resetWakePeak() {
        _wakePeak.value = 0f
    }

    private val _enrollment = MutableStateFlow<Enrollment?>(null)
    val enrollment = _enrollment.asStateFlow()

    /** Which mics Ifa has learned your voice from ("pc", "phone"). Empty: Ifa listens to everyone. */
    private val _voiceSources = MutableStateFlow<List<String>>(emptyList())
    val voiceSources = _voiceSources.asStateFlow()

    fun setEnrollment(enrollment: Enrollment?) {
        _enrollment.value = enrollment
    }

    fun setVoiceSources(sources: List<String>) {
        _voiceSources.value = sources
    }

    private const val MAX_CHAT = 100
}
