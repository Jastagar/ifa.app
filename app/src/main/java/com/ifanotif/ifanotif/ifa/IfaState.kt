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

    fun resetWakePeak() {
        _wakePeak.value = 0f
    }

    private const val MAX_CHAT = 100
}
