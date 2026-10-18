package com.hermesconsole.app

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

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

    /** Holders whose console is mid-start, and those of them already released by their owner. */
    private val building = mutableSetOf<Any>()
    private val releaseAfterBuild = mutableSetOf<Any>()

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
            // Mid-build there is no published console to close yet: the build releases instead,
            // after closing the console it was starting.
            if (holder in building) {
                releaseAfterBuild += holder
                // It is already going; asking it again would reach whatever now shares its identity
                // (a recreated activity keeps the old one's token, so finish() would end the new one).
                if (owner === holder) evictOwner = null
                return
            }
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

    /**
     * Run [start] for [holder]'s [console]. A build that fails or is cancelled closes the console
     * itself, and a [release] that arrived meanwhile takes effect only after that close, so a newer
     * owner never starts while this console still holds the mic.
     */
    suspend fun <C : AutoCloseable> startOwned(holder: Any, console: C, start: suspend () -> Unit): C {
        synchronized(this) { building += holder }
        try {
            start()
            coroutineContext.ensureActive()
            return console
        } catch (e: Throwable) {
            console.close()
            throw e
        } finally {
            val released = synchronized(this) {
                building -= holder
                releaseAfterBuild.remove(holder)
            }
            if (released) release(holder)
        }
    }
}
