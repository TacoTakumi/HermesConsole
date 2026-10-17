package com.hermesconsole.app

import com.hermesconsole.hermesclient.HermesEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The MODEL block's source (T-07, REQ-08): the last effective model a completed run reported.
 *
 * Rides the console's event tap beside the artifact router and the approval desk - one more
 * desk on the tap the assembly test proves sees every run event. The rule is retention: a
 * completed run carrying runtime metadata updates the readout, anything else leaves it standing,
 * and nothing before the first report invents one. Unlike the polled readouts this is not a
 * liveness claim - "the model in use" means the one most recently *seen* in use - so a run that
 * reports nothing keeps the last report rather than blanking it.
 */
class ModelReadout {

    private val _model = MutableStateFlow<String?>(null)

    /** The most recent completed run's reported model. Null until any run has reported one. */
    val model: StateFlow<String?> = _model.asStateFlow()

    /**
     * Every event on the tap passes through; only a completed run's report moves the state. The
     * top-level runtime wins, the patched gateway's usage.runtime is the fallback, and a blank
     * model is no report - upstream can send an empty string.
     */
    fun onEvent(event: HermesEvent) {
        val done = event as? HermesEvent.RunCompleted ?: return
        val reported = done.runtime?.model?.takeIf { it.isNotBlank() }
            ?: done.usage?.runtime?.model?.takeIf { it.isNotBlank() }
            ?: return
        _model.value = reported
    }
}
