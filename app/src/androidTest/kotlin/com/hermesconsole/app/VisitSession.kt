package com.hermesconsole.app

import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.createSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The visit's session for a live-turn rig (REQ-01, REQ-06): made by the real gateway on the
 * rig's first dispatch, then reused by every later turn - the same once-only shape the wall's
 * composition root wires through SessionBacklog.currentOrCreate, without standing up a backlog
 * the rig has no pane for. There is no unscoped dispatch left to fall back to; a rig whose
 * gateway cannot make a session fails its turn, exactly as the wall would.
 */
fun HermesClient.visitSession(): suspend () -> String {
    val mutex = Mutex()
    var made: String? = null
    return { mutex.withLock { made ?: createSession().id.also { made = it } } }
}
