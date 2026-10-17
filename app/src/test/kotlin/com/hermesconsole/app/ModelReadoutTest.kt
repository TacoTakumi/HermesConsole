package com.hermesconsole.app

import com.hermesconsole.hermesclient.HermesEvent
import com.hermesconsole.hermesclient.HermesRuntime
import com.hermesconsole.hermesclient.HermesUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * T-07 / REQ-08: the MODEL block's source - the last effective model a completed run reported.
 *
 * The readout rides the same event tap the router and the approval desk ride (the assembly test
 * proves that tap sees every run event), so what these tests hold is the retention rule itself:
 * a report updates it, silence keeps it, and nothing before the first report invents one.
 */
class ModelReadoutTest {

    private fun completed(model: String?, provider: String? = "anthropic") = HermesEvent.RunCompleted(
        runId = "run_1",
        output = "done",
        usage = HermesUsage(
            totalTokens = 12,
            runtime = model?.let { HermesRuntime(provider = provider, model = it) },
        ),
    )

    @Test
    fun `fresh state has no model - the screen's placeholder, never an invention`() {
        assertNull(ModelReadout().model.value)
    }

    @Test
    fun `a completed run reporting a model is the readout`() {
        val readout = ModelReadout()

        readout.onEvent(completed(model = "claude-sonnet-5"))

        assertEquals("claude-sonnet-5", readout.model.value)
    }

    @Test
    fun `a run that reports nothing keeps the last model seen - retained, not blanked`() {
        val readout = ModelReadout()
        readout.onEvent(completed(model = "claude-sonnet-5"))

        readout.onEvent(completed(model = null))
        readout.onEvent(HermesEvent.RunCompleted(runId = "run_2", output = "done", usage = null))
        readout.onEvent(HermesEvent.RunFailed(runId = "run_3", error = "boom"))

        assertEquals("claude-sonnet-5", readout.model.value, "the most recent *report* stands")
    }

    @Test
    fun `the next report supersedes the last - the readout is the most recent completed run's`() {
        val readout = ModelReadout()
        readout.onEvent(completed(model = "claude-sonnet-5"))

        readout.onEvent(completed(model = "claude-fable-5"))

        assertEquals("claude-fable-5", readout.model.value)
    }

    @Test
    fun `the top-level runtime wins over usage runtime`() {
        val readout = ModelReadout()

        readout.onEvent(
            completed(model = "patched-model").copy(runtime = HermesRuntime(provider = "anthropic", model = "upstream-model")),
        )

        assertEquals("upstream-model", readout.model.value)
    }

    @Test
    fun `a blank top-level model falls back to usage runtime`() {
        val readout = ModelReadout()

        readout.onEvent(completed(model = "patched-model").copy(runtime = HermesRuntime(provider = "anthropic", model = "")))

        assertEquals("patched-model", readout.model.value)
    }

    @Test
    fun `a blank model from both sources keeps the last good readout`() {
        val readout = ModelReadout()
        readout.onEvent(completed(model = "claude-sonnet-5"))

        readout.onEvent(completed(model = " ").copy(runtime = HermesRuntime(provider = "anthropic", model = "")))

        assertEquals("claude-sonnet-5", readout.model.value)
    }
}
