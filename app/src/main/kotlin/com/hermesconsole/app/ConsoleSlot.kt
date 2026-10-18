package com.hermesconsole.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * One wall console per process. MainActivity is singleTask, but Android still makes a second
 * instance when a HOME launch follows a plain one (home tasks live apart from standard ones), and
 * two consoles in one process hear every wake word twice and speak both replies at once.
 *
 * The newest owner wins: a claim evicts the current owner, and the new owner waits in
 * [awaitRetired] until every evicted owner has [release]d, so two loops never share the mic.
 */
class ConsoleSlot {
    private var owner: Any? = null
    private var evictOwner: (() -> Unit)? = null
    private val retiring = MutableStateFlow<Set<Any>>(emptySet())

    /** Take the slot for [claimant]; [evict] is how the slot asks it to go when a newer one claims. */
    fun claim(claimant: Any, evict: () -> Unit) {
        val evictPrevious = synchronized(this) {
            val previous = owner
            if (previous === claimant) return
            val evictPrevious = evictOwner
            owner = claimant
            evictOwner = evict
            if (previous != null) retiring.value = retiring.value + previous
            evictPrevious
        }
        // Outside the lock: the eviction is the old owner's code, and it may call back in.
        evictPrevious?.invoke()
    }

    /** [holder] has closed its console. An evicted holder's release leaves the newer owner in place. */
    fun release(holder: Any) {
        synchronized(this) {
            if (owner === holder) {
                owner = null
                evictOwner = null
            }
            retiring.value = retiring.value - holder
        }
    }

    /** Suspend until every owner this slot evicted has released its console. */
    suspend fun awaitRetired() {
        retiring.first { it.isEmpty() }
    }
}
