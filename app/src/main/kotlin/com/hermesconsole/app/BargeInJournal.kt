package com.hermesconsole.app

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * One barge-in as it survives a reboot (T-59).
 *
 * [atMillis] is wall clock, and it is the field the others hang off: the console's record is only
 * useful next to VoiceManager's, and [BargeInLog]'s own stamps come from the loop's injected clock,
 * which means nothing to a person reading a server log. Sighting three was lost to exactly that
 * join being unavailable.
 */
data class PersistedBargeIn(
    val atMillis: Long,
    val levelDbfs: Double,
    val converged: Boolean,
    val exposureMillis: Long,
    /** Null when there was no track left to ask - never flattened to 0, which is a different claim. */
    val playbackUnderruns: Int?,
    val runId: String?,
)

/** The loop's in-memory record, stamped with the wall clock, ready to outlive the process. */
fun BargeInLog.toEntry(atMillis: Long): PersistedBargeIn = PersistedBargeIn(
    atMillis = atMillis,
    levelDbfs = levelDbfs,
    converged = converged,
    exposureMillis = exposureMillis,
    playbackUnderruns = playbackUnderruns,
    runId = runId,
)

/**
 * Subscribe the journal to the loop's barge-ins (T-59).
 *
 * The seam is deliberately the flow and not the console: [VoiceLoop] has no business holding a
 * DataStore, and it already publishes everything the record needs. So the composition root watches
 * and writes, the loop stays ignorant, and the wiring is provable without standing up a console.
 *
 * A failed write is swallowed. This is the diagnostic, not the interruption - a journal that could
 * take the voice loop down with it would be a worse bug than the one it exists to catch.
 *
 * [source] carries one barge-in as several values, not one: [VoiceLoop.onBargeIn] publishes the
 * decision and then updates it in place as `tts_cancel` and Hermes' stop come back, and every one of
 * those is a distinct value this collector cannot tell from a fresh interruption. Measured on the
 * wall on 2026-08-05, a single barge-in took two of the five slots. That is the [BargeInJournal]
 * ring being spent two or three times over per event, which reduces it to two barge-ins - and so
 * lets the 09:00 person evict the 03:00 phantom, the exact eviction [BargeInJournal.CAPACITY] was
 * sized to prevent.
 *
 * So the journal keys on [BargeInLog.speechAtMillis], the frame the interruption was confirmed on
 * and the one field those updates cannot change. Recording the first value of the three loses
 * nothing: [PersistedBargeIn] keeps no field that the later two add.
 *
 * Being a StateFlow, [source] is also conflated, so two barge-ins arriving closer together than this
 * collector drains would still be journalled as one. Left as is, because a barge-in ends a turn and
 * opens another - a second cannot follow inside a scheduling slice - and the alternative is a replay
 * buffer in [VoiceLoop] serving a diagnostic that no longer needs it.
 */
fun CoroutineScope.journalBargeIns(
    source: StateFlow<BargeInLog?>,
    now: () -> Long = System::currentTimeMillis,
    record: suspend (PersistedBargeIn) -> Unit,
): Job = launch {
    // filterNotNull, because a StateFlow opens on null and nobody barged in to produce it.
    source.filterNotNull()
        .distinctUntilChangedBy { it.speechAtMillis }
        .collect { log ->
            runCatching { record(log.toEntry(atMillis = now())) }
                .onFailure { Log.w("BargeInJournal", "barge-in not journalled: ${it.message}") }
        }
}

/**
 * The last few barge-ins, on disk (T-59).
 *
 * T-58 put the trigger facts in logcat, which was the right place right up until the failure
 * happened overnight. Measured on the SM-X210, 2026-08-05: `main` is a 5 MiB ring holding about 90
 * minutes, `logcat -G 16M` clamps back to 5 MiB, and `setprop persist.logd.size` is denied. So the
 * console's own record of why it interrupted itself expired before anybody could read it, and the
 * one sighting we could have classified was gone by morning.
 *
 * A ring of [CAPACITY] rather than a single slot, because one slot loses the interesting one: a
 * phantom at 03:00 would be overwritten by a person barging in perfectly legitimately at 09:00,
 * and it is the 03:00 entry that is the whole investigation.
 *
 * Stored as flat, indexed, typed keys - no serialisation. Partly because `app` is forbidden from
 * parsing wire formats (T-31 enforces it by script) and a hand-rolled encoder here would be the
 * same smell in a different coat; mostly because there is no encoding to get wrong.
 */
class BargeInJournal(private val store: DataStore<Preferences>) {

    /** The recorded barge-ins, newest first. Empty on a console that has never interrupted itself. */
    suspend fun recent(): List<PersistedBargeIn> {
        val prefs = store.data.first()
        val written = prefs[COUNT] ?: 0
        if (written == 0) return emptyList()

        // Walk back from the most recently written slot, at most CAPACITY of them.
        return (1..minOf(written, CAPACITY)).mapNotNull { back ->
            val slot = Math.floorMod(written - back, CAPACITY)
            val at = prefs[atKey(slot)] ?: return@mapNotNull null
            PersistedBargeIn(
                atMillis = at,
                levelDbfs = prefs[levelKey(slot)] ?: 0.0,
                converged = prefs[convergedKey(slot)] ?: false,
                exposureMillis = prefs[exposureKey(slot)] ?: 0,
                playbackUnderruns = prefs[underrunsKey(slot)],
                runId = prefs[runIdKey(slot)],
            )
        }
    }

    /** Record one, into the next slot round the ring. */
    suspend fun record(entry: PersistedBargeIn) {
        store.edit { prefs ->
            val written = prefs[COUNT] ?: 0
            val slot = Math.floorMod(written, CAPACITY)
            prefs[atKey(slot)] = entry.atMillis
            prefs[levelKey(slot)] = entry.levelDbfs
            prefs[convergedKey(slot)] = entry.converged
            prefs[exposureKey(slot)] = entry.exposureMillis
            // Removed rather than zeroed: absent is how null comes back, and the difference between
            // "the mixer never ran dry" and "there was no track to ask" is load-bearing.
            entry.playbackUnderruns
                ?.let { prefs[underrunsKey(slot)] = it }
                ?: prefs.remove(underrunsKey(slot))
            entry.runId
                ?.let { prefs[runIdKey(slot)] = it }
                ?: prefs.remove(runIdKey(slot))
            prefs[COUNT] = written + 1
        }
    }

    companion object {
        /**
         * Five. Enough that an unattended night's worth of self-interruptions all survive to be
         * read in the morning, few enough that this stays a diagnostic and not a database.
         */
        const val CAPACITY = 5

        // Nothing here is a credential (REQ-28): a level, a flag, two durations, a run id.
        private val COUNT = intPreferencesKey("barge_in_count")
        private fun atKey(slot: Int) = longPreferencesKey("barge_in_${slot}_at")
        private fun levelKey(slot: Int) = doublePreferencesKey("barge_in_${slot}_level_dbfs")
        private fun convergedKey(slot: Int) = booleanPreferencesKey("barge_in_${slot}_converged")
        private fun exposureKey(slot: Int) = longPreferencesKey("barge_in_${slot}_exposure_ms")
        private fun underrunsKey(slot: Int) = intPreferencesKey("barge_in_${slot}_underruns")
        private fun runIdKey(slot: Int) = stringPreferencesKey("barge_in_${slot}_run_id")
    }
}
