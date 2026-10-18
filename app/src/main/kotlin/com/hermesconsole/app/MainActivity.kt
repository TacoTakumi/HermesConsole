package com.hermesconsole.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.CompositionLocalProvider
import com.hermesconsole.app.artifacts.ArtifactRouter
import com.hermesconsole.app.devicetools.appTools
import com.hermesconsole.app.devicetools.launcherApps
import com.hermesconsole.app.tools.ToolActivityRouter
import com.hermesconsole.app.tools.ToolLine
import com.hermesconsole.app.tools.linesForSession
import com.hermesconsole.audio.AudioCapture
import com.hermesconsole.audio.AudioConfig
import com.hermesconsole.audio.AudioSinkFactory
import com.hermesconsole.audio.AudioTrackOneShotSink
import com.hermesconsole.audio.AudioTrackSinkFactory
import com.hermesconsole.audio.MicPipeline
import com.hermesconsole.audio.NativeSoftwareAec
import com.hermesconsole.audio.OneShotSink
import com.hermesconsole.audio.SoftwareAec
import com.hermesconsole.audio.TtsPlayer
import com.hermesconsole.hermesclient.DeviceTool
import com.hermesconsole.hermesclient.DeviceToolLink
import com.hermesconsole.hermesclient.HermesClient
import com.hermesconsole.hermesclient.HermesMessage
import com.hermesconsole.hermesclient.HermesSession
import com.hermesconsole.hermesclient.advertisedProfile
import com.hermesconsole.hermesclient.configuredModel
import com.hermesconsole.hermesclient.createSessionDisambiguated
import com.hermesconsole.hermesclient.listSessions
import com.hermesconsole.hermesclient.sessionMessages
import com.hermesconsole.lcars.LcarsCue
import com.hermesconsole.lcars.LcarsCuePlayer
import com.hermesconsole.lcars.LocalLcarsCues
import com.hermesconsole.voiceclient.VoiceManagerClient
import com.hermesconsole.voiceclient.fetchVoiceManagerInfo
import java.time.LocalDateTime
import com.hermesconsole.wakeword.WakeWordDetector
import com.hermesconsole.wakeword.WakeWordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The console shell. A wall-mounted appliance: fullscreen, portrait, no system bars.
 *
 * It composes the modules and owns no reusable logic of its own (REQ-31) - the capture path,
 * its device selection and its AEC verdict all live in :audio.
 */
class MainActivity : ComponentActivity() {

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // The always-on anchor (REQ-25) starts here and not in onCreate: on Android 14+ a
                // microphone-typed startForeground without RECORD_AUDIO is a SecurityException, so
                // the grant callback is the earliest safe moment. First boot waits for the tap;
                // every boot after, the grant is persisted and this fires with zero interaction -
                // from a visible Activity, which is the compliant way to start a mic-typed FGS.
                startForegroundService(Intent(this, MicForegroundService::class.java))
                startWallConsole()
            } else {
                Log.w(TAG, "RECORD_AUDIO denied; no capture path")
            }
        }

    /** The live console (T-22): built once the mic grant lands and the backends are configured. */
    private val wallConsole = MutableStateFlow<WallConsole?>(null)

    /** Whether this instance took the wall console slot; the debug path never does. */
    private var claimedSlot = false

    /** The mic-less debug console (T-17), alive only for a debug launch. */
    private var debugConsole: DebugConsole? = null

    /** The one output path (REQ-33): cues, TTS and the wall console all play through it. */
    private var sinkFactory: AudioTrackSinkFactory? = null

    /**
     * The shell's earcons (REQ-39): the kit's cue set, played through the audio module's output
     * path so a chirp is echo-cancelled exactly as TTS is (REQ-33). Built only for the console
     * path - the debug console carries its own - and closed with the activity.
     */
    private var cuePlayer: LcarsCuePlayer? = null

    /**
     * The persisted runtime config (T-23, REQ-28): every launch reads its backends from here,
     * and the settings screen is the only writer.
     */
    private val settingsStore by lazy { SettingsStore(consoleSettings) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // The debug launch (T-17, REQ-24): typed commands become full Hermes turns while the
        // capture path is never constructed - no RECORD_AUDIO request, no AEC self-check, no
        // AudioRecord read loop anywhere in the process. Backend config rides the intent (REQ-28).
        if (intent.getBooleanExtra("debug", false)) {
            startDebugConsole()
            return
        }

        // One wall console per process: a HOME launch after a plain one makes a second instance
        // despite singleTask. The newer instance wins, and the older one finishes and closes.
        consoleSlot.claim(this) { finish() }
        claimedSlot = true

        // The software AEC's native half, proven at boot (REQ-09): libwebrtc_apm must load and a
        // 16 kHz AEC3 APM must initialize on this device, and the verdict belongs on the record
        // every boot. The library needs no permission, so the probe runs before the mic grant;
        // the probe session closes immediately and the per-capture-session AEC is created at
        // capture start (T-08).
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { NativeSoftwareAec.startupProbe() }
        }

        // A wake word must be able to light the wall even after One UI's own display timeout has
        // slept the panel (T-57, REQ-26). setShowWhenLocked keeps the console visible over any
        // keyguard - the wall runs with none (docs/device-setup.md), but this is the correct
        // pairing - and setTurnScreenOn turns the panel on as the console is shown. Neither wakes
        // a stopped, dozing console on its own; the state watcher below brings the console back to
        // the foreground on a wake, which is what makes setTurnScreenOn fire.
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        // One output route for everything the console plays - earcons and TTS - so every sound is
        // echo-cancelled the same way (REQ-33/REQ-39), here exactly as in the debug assembly. TTS
        // streams; a cue is a one-shot clip (a MODE_STATIC track per earcon), because streaming a
        // 45 ms chirp into a long-lived track starved it between taps and the restart ate the chirp
        // (measured on-device). Both resolve the same route, so the AEC coverage is identical.
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sinks = AudioTrackSinkFactory(audioManager, AudioConfig()).also { sinkFactory = it }
        val cues = LcarsCuePlayer(AudioTrackOneShotSink(audioManager, AudioConfig())).also { cuePlayer = it }

        // The console's state: the live loop's the moment it exists (T-22), a READY constant
        // until then - which is exactly what an active but unengaged console shows (REQ-35).
        // Following the console flow is what puts the wake-word restore path live: a wake word
        // becomes a state transition, the transition wakes the screen (T-21's other half).
        @OptIn(ExperimentalCoroutinesApi::class)
        val consoleState: StateFlow<ConsoleState> = wallConsole
            .flatMapLatest { it?.loop?.state ?: MutableStateFlow(ConsoleState.READY).asStateFlow() }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, ConsoleState.READY)

        // The stored intervals, live for the life of the activity (T-18, REQ-17). Its initial value
        // is the compiled defaults rather than a read of the store, because this is onCreate and
        // nothing here may block; the first emission lands a moment later and the clocks pick it up
        // on their next restart, which is exactly how a save applies without a reinstall.
        val idleSettings = settingsStore.settings
            .stateIn(lifecycleScope, SharingStarted.Eagerly, BackendSettings())

        // The console's idle clocks (T-21/T-16, REQ-22/REQ-16), owned here because the dim is the
        // window's. Both read their interval through a supplier, so what a person just typed on the
        // config screen governs the very next stretch of silence (REQ-17).
        val idleController = IdleController(
            state = consoleState,
            scope = lifecycleScope,
            sessionTimeoutMillis = { idleSettings.value.sessionTimeoutSeconds * 1_000L },
            screensaverTimeoutMillis = { idleSettings.value.screensaverTimeoutSeconds * 1_000L },
        ).also { it.start() }

        // The visit ends in the same silence, two minutes ahead of the screen (T-08/T-16, REQ-07):
        // the conversation closes while the wall is still lit, so the next person to walk up starts
        // their own and anyone standing there sees it happen. The console may not exist yet - and
        // before it does there is no visit to end.
        lifecycleScope.endVisitWhenIdle(idleController.visitOver) { wallConsole.value?.endVisit() }

        // The OS display wake (T-57, REQ-26). The screensaver dim above is a window-brightness
        // change and only reaches a panel that is still lit; once One UI's own display timeout
        // sleeps it (docs/device-setup.md: stock 30 min) that change lands on nothing. A wake word
        // still runs a full turn in the dark - the mic rides the anchor's wake lock (REQ-26) - but
        // this makes the reply land on a lit wall instead of a dark one. The loop leaving READY is
        // that wake word by the time anything can see it (the same edge the screensaver restores
        // on), and it powers the OS panel back on - but only while the panel is actually asleep, so
        // a wake mid-turn on a lit console is a no-op. This rides lifecycleScope, not the
        // composition: Compose stops recomposing while the activity is stopped (which is exactly
        // what a slept display leaves it), so the idle LaunchedEffect below cannot be the hook.
        val powerManager = getSystemService(PowerManager::class.java)
        lifecycleScope.launch {
            var wasReady = true
            consoleState.collect { state ->
                val leftReady = wasReady && state != ConsoleState.READY
                wasReady = state == ConsoleState.READY
                if (leftReady && !powerManager.isInteractive) {
                    // Wake the panel out of doze by bringing the console to the foreground. On this
                    // device (One UI 8 / Android 16) an ACQUIRE_CAUSES_WAKEUP screen wake lock does
                    // NOT bring the display out of doze (measured on the wall, T-57); the
                    // foregrounded activity's setTurnScreenOn does. singleTask + HOME means this
                    // resumes THIS instance, not a second one, and screen-on resets One UI's own
                    // display timeout so the panel stays lit for the turn. The background-activity-
                    // launch restriction is lifted because the console is the default home app,
                    // holds the assistant role, and is battery-exempt (docs/device-setup.md).
                    startActivity(
                        Intent(this@MainActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
        }

        setContent {
            // Every kit component under here confirms a touch out loud (REQ-39).
            CompositionLocalProvider(LocalLcarsCues provides cues::play) {
                var configuring by remember { mutableStateOf(false) }
                // The app drawer (T-53, REQ-42), a peer of the settings screen: a full-screen
                // pane outside the shell, dismissed back to the console.
                var showingApps by remember { mutableStateOf(false) }
                // The sessions screen (T-09, REQ-02), the third full-screen pane on the same
                // pattern: the stored list lives here now, opened off the rail from home.
                var showingSessions by remember { mutableStateOf(false) }
                val idle by idleController.idle.collectAsState()

                // The wall's own charge (T-03, REQ-06/07): the sticky battery broadcast, registered
                // for the life of the composition and published as state the top-bar segment reads.
                // Null until the first reading lands - the honest placeholder - and following the
                // battery after. Hoisted here, outside every screen branch, so the frame chrome
                // carries it whatever is on the wall.
                val batterySource = remember { BatterySource(this@MainActivity) }
                DisposableEffect(batterySource) {
                    batterySource.start()
                    onDispose { batterySource.stop() }
                }
                val battery by batterySource.state.collectAsState()

                // A full-screen pane over the shell - settings, the drawer, the sessions list -
                // sits outside its press observer, so the idle countdown is held off while one is
                // up (as below it is fed on dismiss); any would otherwise expire under the
                // integrator's fingers.
                val overlay = configuring || showingApps || showingSessions

                // The blank pixels are the screensaver; the backlight going with them is what
                // makes a wall console dark rather than a black rectangle glowing at the room
                // (REQ-22). Never while an overlay pane is up, though - those screens sit outside
                // the shell's press observer, so the countdown can expire under their fingers.
                // The latency is logged from here so the stamp covers the frame the person sees.
                LaunchedEffect(idle, overlay) {
                    window.attributes = window.attributes.also {
                        it.screenBrightness =
                            if (idle && !overlay) IDLE_BRIGHTNESS
                            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                    if (!idle) idleController.restored()
                }

                if (configuring) {
                    // Seeded from disk, not from a default: the screen must open showing what the
                    // console would actually dial. Null only for the beat the first read takes.
                    val settings by settingsStore.settings.collectAsState(initial = null)
                    settings?.let {
                        SettingsScreen(
                            initial = it,
                            onSave = { edited -> lifecycleScope.launch { settingsStore.save(edited) } },
                            // The countdown ran through the whole settings visit unfed - the shell's
                            // press observer does not cover this screen - so the dismissal counts as
                            // the touch it is, and nobody returns from settings straight into the dark.
                            onDone = {
                                configuring = false
                                idleController.touched()
                            },
                            // T-52, REQ-41: land in the tablet's system Settings. The console is
                            // HOME (singleTask), so HOME or back out of Settings returns to this
                            // very instance, and the voice loop rides MicForegroundService the
                            // whole time - undisturbed by the trip.
                            onOpenSystemSettings = {
                                startActivity(Intent(SYSTEM_SETTINGS_ACTION))
                            },
                        )
                    }
                } else if (showingSessions) {
                    // The sessions screen (T-09, REQ-02): the stored list on its own pane, live
                    // off the console's one poll - never staler than the lamps beside it. Entering
                    // a session, stored or fresh, is leaving this screen: the tap moves the store,
                    // and the shell underneath already follows the store. Unreachable without a
                    // console - the rail's segment is inert chrome until one is assembled.
                    val console by wallConsole.collectAsState()
                    console?.let { c ->
                        val stored by c.health.sessions.collectAsState()
                        val current by c.currentSession.collectAsState()
                        SessionsScreen(
                            sessions = stored,
                            currentSessionId = current,
                            onSelect = { id ->
                                c.select(id)
                                showingSessions = false
                                idleController.touched()
                            },
                            onCreate = {
                                lifecycleScope.launch { c.create() }
                                showingSessions = false
                                idleController.touched()
                            },
                            // Back/close: the countdown ran the whole visit unfed, so the
                            // dismissal is the touch it is - straight back to System Access.
                            onClose = {
                                showingSessions = false
                                idleController.touched()
                            },
                        )
                    }
                } else if (showingApps) {
                    // The console's own launcher (T-53, REQ-42). Queried right here as the pane
                    // composes - which is once per open, so a since-installed or removed app shows
                    // without a console restart - and self excluded by this activity's own package.
                    // remember, not a State: the wall is a fixed appliance, so one read per open is
                    // the live picture; reopening re-queries.
                    val entries = remember { packageManager.launcherEntries(packageName) }
                    AppDrawerScreen(
                        entries = entries,
                        onLaunch = { app ->
                            // The console is HOME (singleTask): launching an app backgrounds this
                            // very instance, and HOME returns to it - so close the drawer now, and
                            // the return lands on the console proper, not back on the drawer.
                            runCatching { startActivity(app.launchIntent()) }
                                .onFailure { Log.w(TAG, "app launch failed: ${app.packageName}: ${it.message}") }
                            showingApps = false
                            idleController.touched()
                        },
                        // Back/close: the countdown ran the whole visit unfed, so the dismissal is
                        // the touch it is - nobody returns from the drawer straight into the dark.
                        onClose = {
                            showingApps = false
                            idleController.touched()
                        },
                    )
                } else {
                    val console by wallConsole.collectAsState()
                    val state by consoleState.collectAsState()
                    ConsoleShell(
                        state = state,
                        turn = console?.let { it.loop.turn.collectAsState().value } ?: TurnLog(),
                        // The run's artifacts (T-49, REQ-15/16): routed panes, stacked in the
                        // content column below the conversation - on the wall, not just the
                        // debug screen.
                        panes = console?.let { it.panes.collectAsState().value }.orEmpty(),
                        backlog = console?.let { it.backlog.collectAsState().value },
                        spoken = console?.let { it.loop.spoken.collectAsState().value }.orEmpty(),
                        // Each run's tool lines, woven into its reply at the point they fired (T-10).
                        toolLinesByRun = console?.let { it.toolLinesByRun.collectAsState().value }.orEmpty(),
                        onConfigure = { configuring = true },
                        onApps = { showingApps = true },
                        // The way home (T-02, REQ-04): the system access segment ends the visit
                        // exactly as the idle return does - the loop aborted, the store emptied,
                        // one full stop - and the System Access screen shows because the attract
                        // state then holds. The shell itself renders it inert while home holds.
                        onSystemAccess = console?.let { c -> { c.endVisit() } },
                        // The rail's departure (T-21, REQ-18), then the list (T-10, REQ-03): the
                        // same endVisit the idle return fires - the loop aborted, the store
                        // emptied, one full stop - and the sessions screen presents over the
                        // attract state that then holds, never over a live conversation.
                        onSessions = console?.let { c ->
                            sessionsDeparture(endVisit = { c.endVisit() }, present = { showingSessions = true })
                        },
                        // From home the same segment opens the list instead (T-09, REQ-02):
                        // there is no visit to end, and the list lives on its own pane now.
                        onOpenSessions = console?.let { { showingSessions = true } },
                        // The attract state's content (T-01, REQ-01): the System Access home
                        // screen - identity and com-links live off the console's health, no
                        // session rows. An unconfigured shell passes nothing and stands by.
                        // Until M-02 lands the sessions screen, voice is the only session
                        // entry point - the accepted interim state.
                        ready = console?.let { c ->
                            {
                                val hermes by c.health.hermes.collectAsState()
                                val voice by c.health.voice.collectAsState()
                                val vmInfo by c.health.vmInfo.collectAsState()
                                val profile by c.health.profile.collectAsState()
                                val model by c.effectiveModel.collectAsState()
                                SystemAccessScreen(
                                    hermes = hermes,
                                    voice = voice,
                                    vmInfo = vmInfo,
                                    profile = profile,
                                    model = model,
                                )
                            }
                        },
                        // The stalled run's question, pinned outside the scroll (T-27, REQ-14).
                        approval = console?.let { c -> { ApprovalHost(c.approvals) } },
                        // The tablet's charge in the top-bar segment (T-03, REQ-06): the shell
                        // renders the value, it holds no battery source of its own.
                        battery = battery,
                        idle = idle,
                        onInteraction = idleController::touched,
                    )
                }
            }
        }
        requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    /**
     * Assemble the live console (T-22): the full voice loop - microphone, wake word, both
     * backends, the speaker - plus the ready state's session switcher and health monitor.
     * Runs only once the mic grant has landed; without configured backends the shell stays a
     * standing-by frame and the boot still logs its AEC verdict (REQ-02).
     */
    private fun startWallConsole() = lifecycleScope.launch {
        // An older instance's console must close first, or two loops share the mic.
        consoleSlot.awaitRetired()
        // The settings, live for the life of the console: the backends are fixed at assembly
        // (reconfiguring them is a relaunch), but the follow-up window is read through this at
        // every reply's end - the knob applies to the next exchange, no reinstall (REQ-40).
        val settings = settingsStore.settings
            .stateIn(lifecycleScope, SharingStarted.Eagerly, settingsStore.read())
        val backends = settings.value.toBackends()
        if (backends == null) {
            Log.w(TAG, "console unconfigured; set both keys on the settings screen (REQ-28)")
            runAecSelfCheck()
            return@launch
        }
        val mic = MicPipeline(getSystemService(Context.AUDIO_SERVICE) as AudioManager, AudioConfig())
        val console = WallConsole(
            backends = backends,
            cues = checkNotNull(cuePlayer)::play,
            sinks = checkNotNull(sinkFactory),
            // The announcement's own one-shot on the cue route (REQ-11): the cue player owns its
            // sink, so the spoken line gets a sibling rather than a shared one.
            announcementSink = AudioTrackOneShotSink(
                getSystemService(Context.AUDIO_SERVICE) as AudioManager,
                AudioConfig(),
            ),
            capture = mic,
            wake = WakeWordDetector(applicationContext),
            followUpWindowMillis = { settings.value.followUpWindowSeconds * 1_000L },
            // The software echo canceller (T-08, REQ-01/REQ-08): one native AEC3 session per
            // capture session, created at capture start. Whether the loop is given this factory at
            // all is WallConsole's SOFTWARE_AEC_ENABLED, beside the barge-in switch; handing it
            // over here costs nothing, because the lambda only loads the library when invoked. A
            // device that cannot load or initialize it runs raw capture, and the boot probe's
            // failure record is what says so.
            aecFactory = { runCatching { NativeSoftwareAec.create() }.getOrNull() },
            journal = BargeInJournal(bargeInRecords),
            // The console is HOME, so it may start activities even with another app in front.
            deviceTools = appTools(
                resolved = { packageManager.launcherApps() },
                selfPackage = packageName,
                launch = { app -> withContext(Dispatchers.Main) { startActivity(app.launchIntent()) } },
            ),
            scope = lifecycleScope,
        )
        // Through the slot: if a newer instance evicts this one mid-start, the half-started console
        // is closed here before the newer one may build its own.
        consoleSlot.startOwned(this@MainActivity, console) {
            withContext(Dispatchers.IO) {
                // The AEC verdict, on the record every boot (REQ-02) - logged where the pipeline is
                // built, from the very capture session the loop will run on.
                Log.i(TAG, "input devices: ${mic.availableDevices()}")
                Log.i(TAG, mic.start().describe())
                console.start()
            }
        }
        wallConsole.value = console
    }

    private fun startDebugConsole() {
        // The intent extras are an explicit override (T-17's verify launches this way); a debug
        // launch without them runs on the persisted settings, the same config a normal launch
        // dials (T-23, REQ-28). Reading the store is a suspend, hence the launch.
        lifecycleScope.launch {
            val backends = debugBackends(intent) ?: settingsStore.read().toBackends()
            if (backends == null) {
                Log.w(
                    TAG,
                    "debug launch unconfigured; set keys on the settings screen " +
                        "or pass --es hermesKey / --es vmKey (REQ-28)",
                )
                setContent { DebugScreenUnconfigured() }
                return@launch
            }
            val console = DebugConsole(backends, getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            debugConsole = console
            console.start()
            // The debug screen is its own composition root, so it provides the cue path here as the
            // console root does above (REQ-39): its dispatch and approval pills are kit components
            // that confirm through LocalLcarsCues, and without a provider every touch on this screen
            // would fall to the local's silent default. The chirp rides the debug console's own
            // player, on the same echo-cancelled output path as its earcons.
            setContent {
                CompositionLocalProvider(LocalLcarsCues provides console.touchCues) {
                    DebugScreen(console)
                }
            }
        }
    }

    override fun onDestroy() {
        debugConsole?.close()
        debugConsole = null
        wallConsole.value?.let {
            it.close()
            Log.i(TAG, "wall console closed")
        }
        wallConsole.value = null
        cuePlayer?.close()
        cuePlayer = null
        if (claimedSlot) consoleSlot.release(this)
        super.onDestroy()
    }

    /**
     * Startup self-check (REQ-02): open the capture session on the configured device and report
     * whether hardware echo cancellation is actually available and enabled on it. Barge-in is a
     * gate, not an assumption - so the verdict is logged every boot.
     */
    private fun runAecSelfCheck() = lifecycleScope.launch {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        withContext(Dispatchers.IO) {
            MicPipeline(audioManager, AudioConfig()).use { mic ->
                Log.i(TAG, "input devices: ${mic.availableDevices()}")
                Log.i(TAG, mic.start().describe())
            }
        }
    }

    private companion object {
        const val TAG = "HermesConsole"

        /** Process-wide: every MainActivity instance in the process shares it. */
        val consoleSlot = ConsoleSlot()

        /**
         * The idle backlight (REQ-22): low enough to read as dark on the wall, high enough that
         * the panel visibly comes back the frame the restore lands rather than a beat later.
         */
        const val IDLE_BRIGHTNESS = 0.05f
    }
}

/**
 * The wall console assembled live (T-22): the same [VoiceLoop] as the debug assembly with the
 * microphone half present - [MicPipeline] capture, on-device wake word, both handed in by the
 * activity - plus what the ready state stands on: the session the next turn lands in, and both
 * backends' health.
 *
 * The session is the console's one piece of switchable state, and the loop reads it *per turn*
 * through its provider: tapping a session between turns changes where the next wake word's
 * question goes, with nothing rebuilt (REQ-23).
 *
 * The constructor takes the audio-hardware seams as their interfaces rather than building them
 * from a [Context] so that this assembly - the one the wall actually ships - is constructible on
 * a plain JVM (T-49): the review found the artifact router live only in the debug screen and the
 * test rigs because nothing ever proved THIS class routed a pane, and a copy-of-the-wiring rig
 * cannot prove it either.
 */
internal class WallConsole(
    backends: DebugBackends,
    /** The console's earcons (REQ-39): the shell's cue player, on the echo-cancelled output path. */
    private val cues: (LcarsCue) -> Unit,
    /** The one output path (REQ-33): the same factory the cue player plays through. */
    sinks: AudioSinkFactory,
    /**
     * The approval announcement's one-shot output (REQ-11), on the same echo-cancelled route as
     * the cues. Null - the assembly tests, the debug shell - keeps the rise cue-only.
     */
    private val announcementSink: OneShotSink? = null,
    /** The capture path: [MicPipeline] on the wall; the loop owns and closes it. */
    capture: AudioCapture,
    wake: WakeWordSource,
    /** The follow-up window (REQ-40), read through to the loop at every reply's end. */
    followUpWindowMillis: () -> Long,
    /**
     * The software echo canceller's factory (T-08, REQ-01/REQ-08): one AEC3 session per capture
     * session, created by the loop at capture start and seeded with the measured output latency.
     * Null - the assembly tests - runs raw capture. The shipping wall wires the native
     * implementation with graceful degradation: a device that cannot load or initialize the
     * library captures raw, which is the half-duplex fallback (REQ-13).
     */
    private val aecFactory: (() -> SoftwareAec?)? = null,
    /**
     * Where a barge-in goes to outlive the log buffer (T-59), or null to keep no durable record.
     *
     * Null in the assembly tests and on any console that has no storage to hand: the journal is a
     * diagnostic, and the voice loop must run identically without one.
     */
    private val journal: BargeInJournal? = null,
    /**
     * The tools the model may run on the tablet itself, over the device socket to the gateway.
     * Empty - the assembly tests that do not exercise them - opens no socket.
     */
    private val deviceTools: List<DeviceTool> = emptyList(),
    private val scope: CoroutineScope,
) : AutoCloseable {

    private val voice = VoiceManagerClient(backends.voice)
    private val hermes = HermesClient(backends.hermes)

    /**
     * The approval desk (T-27, REQ-14): raised off the loop's event tap, answered by touch on the
     * shell's pane or by the transcript watcher below, and never left pending past its deadline.
     * A raise is never silent (REQ-10): the question's arrival sounds its own cue.
     */
    val approvals = ApprovalController(
        gateway = hermes.asApprovalGateway(),
        scope = scope,
        announce = {
            cues(LcarsCue.APPROVAL_REQUIRED)
            announcer?.speak()
        },
    )

    /**
     * The artifact seam (T-49, REQ-15): panes routed off the same event stream the reply is
     * spoken from - the wall's own router, in the tap beside the approval desk, exactly as the
     * debug assembly carries it.
     */
    private val router = ArtifactRouter()

    /**
     * The tool-activity desk (tool-output-pane T-06/T-09, REQ-09): a distinct desk beside the
     * artifact one, reading `tool.completed` off the same tap the reply is spoken from. It tags each
     * line with the live session (T-07), read in a deferred lambda as the loop/sessions pair is - so
     * the wall can later show a session only its own tools.
     */
    private val toolRouter = ToolActivityRouter(
        currentSession = { sessions.current.value },
        // Where in the live reply each tool fired (T-10), read as the deltas have already landed.
        replyLengthOf = { loop.turn.value.reply.length },
    )

    /** The model readout's desk on the same tap (T-07, REQ-08): retains the last reported model. */
    private val modelReadout = ModelReadout()

    /**
     * Which session the console is in and what its pane opens showing (T-43): entered sessions
     * restore their stored history through the display read, and entry clears the stale turn
     * off the wall before the restored lines land.
     */
    // Typed explicitly, as is the loop: each one's initializer names the other in a deferred
    // lambda, and inference alone sees only the cycle.
    private val sessions: SessionBacklog = SessionBacklog(
        fetch = { hermes.sessionMessages(it) },
        // The panes go with the turn log. Unconditional, unlike clearTurn: a session change never
        // lands mid-run with this run's artifacts already up (the first dispatch opens its
        // session before the run starts), so what is cleared is always another session's.
        clearWall = {
            loop.clearTurn()
            router.clear()
        },
        restoreWall = router::restore,
        scope = scope,
    )

    /**
     * The device tool socket. It claims the session the wall shows, so the gateway sends this
     * console the calls of its own conversation when other consoles share the profile.
     */
    private val deviceLink = DeviceToolLink(backends.hermes, deviceTools, currentSession = sessions.current)

    /** Where the next turn lands (REQ-23). Null until a session is entered - the first dispatch then makes one (REQ-06). */
    val currentSession: StateFlow<String?> get() = sessions.current

    /** The entered session's restored spoken history, for the shell. Null before any entry. */
    val backlog: StateFlow<List<HermesMessage>?> get() = sessions.backlog

    /**
     * The routed artifact panes for the shell (T-49, REQ-15/16). Tool lines no longer ride here: as
     * of T-10 they weave inline into the conversation at the point each fired (see [toolLinesByRun]),
     * not into a trailing pane.
     */
    val panes: StateFlow<List<ConsolePane>> =
        router.panes
            .map { routed -> routed.map { pane -> ConsolePane(pane.title) { pane.Content() } } }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * The current session's tool lines, grouped by the run that produced them (T-10): the shell
     * weaves each run's lines back into that run's reply at the offset each fired. Session-scoped
     * (T-07) so an older session shows its own tools, never a newer run's; declared after [sessions]
     * so its flow is live.
     */
    val toolLinesByRun: StateFlow<Map<String, List<ToolLine>>> =
        combine(toolRouter.lines, sessions.current) { lines, session ->
            linesForSession(lines, session).groupBy { it.runId }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val loop: VoiceLoop = VoiceLoop(
        capture = capture,
        wake = wake,
        voice = voice.asVoiceLink(),
        agent = hermes.asAgent(),
        player = TtsPlayer(sinks),
        // The visit's scope (REQ-01/REQ-06): the entered session, or one made by the gateway on
        // the visit's first dispatch - and never no session at all (REQ-03). Made titled with
        // the moment of creation (T-12, REQ-14), collisions disambiguated client-side.
        sessionId = { sessions.currentOrCreate { createTitled().id } },
        followUpWindowMillis = followUpWindowMillis,
        // All the desks on the one tap (T-49, T-07, tool-output-pane T-06): the artifact router
        // first, as the debug assembly has it, the tool-activity desk beside it - none may throw,
        // and the loop guards them as one anyway.
        eventTap = { event ->
            router.onEvent(event)
            toolRouter.onEvent(event)
            approvals.onEvent(event)
            modelReadout.onEvent(event)
        },
        // A MEDIA: line the model wrote instead of calling render_artifact: shown, never spoken.
        mediaTap = router::onMedia,
        cues = cues,
        // The barge-in kill switch - one knob, flip-and-rebuild (see BARGE_IN_ENABLED). Off today:
        // the half-duplex stopgap, while the hardware canceller's intermittent dropout is diagnosed.
        // Trips are still detected and logged, they are just not acted on.
        bargeInEnabled = BARGE_IN_ENABLED,
        // The software AEC kill switch, beside the barge-in one (see SOFTWARE_AEC_ENABLED). Off
        // today: no AEC3 in the capture path, so the detector reads the hardware canceller's own
        // output and a dropout measurement is taken through one canceller, not two.
        aecFactory = if (SOFTWARE_AEC_ENABLED) aecFactory else null,
    )

    /**
     * The approval's voice (REQ-11): the fixed line, synthesized once through its own link and
     * played on the cue route - outside the loop, so no console state moves and a barge-in has
     * nothing to cancel while the question waits. Without a sink, the rise stays cue-only.
     */
    private val announcer: ApprovalAnnouncer? = announcementSink?.let { sink ->
        ApprovalAnnouncer(
            synths = { VoiceManagerClient(backends.voice).asAnnouncementSynth() },
            sink = sink,
            scope = scope,
            speaking = { loop.state.value == ConsoleState.SPEAKING },
        )
    }

    val health = HealthMonitor(
        probe = { hermes.listSessions() },
        link = voice.state,
        // The engines readout's source (T-05, REQ-09): /info off the same config the socket
        // dials, polled on the monitor's one clock.
        vmInfoProbe = { fetchVoiceManagerInfo(backends.voice) },
        // The profile readout's source (T-06, REQ-07): the gateway's advertised identity.
        profileProbe = { hermes.advertisedProfile() },
        // The MODEL block's seed (2026-08-14): the configured selection, on the link's arrival.
        modelProbe = { hermes.configuredModel() },
        scope = scope,
    )

    /**
     * The MODEL block's reading (T-07, REQ-08; seeded 2026-08-14): the last effective model a
     * completed run reported, or the gateway's configured selection until any run has - so a
     * reachable gateway names its model from the first poll, not from the first conversation.
     * A run's report outranks the seed the moment one lands, and nothing ever blanks either.
     */
    val effectiveModel: StateFlow<String?> =
        combine(modelReadout.model, health.configuredModel) { reported, configured -> reported ?: configured }
            .stateIn(scope, SharingStarted.Eagerly, null)

    fun start() {
        loop.start()
        voice.connect()
        if (deviceTools.isNotEmpty()) deviceLink.connect()
        health.start()
        // The pane opens on the wake itself (T-06, REQ-04): leaving READY with nothing entered
        // is a visit beginning, and the wall shows its empty pane before the utterance is done -
        // ahead of any dispatch, which is the earliest the session can exist (REQ-05). The store
        // guards the other departures: mid-session, leaving READY is just a turn.
        scope.launch {
            loop.state.collect { if (it != ConsoleState.READY) sessions.woken() }
        }
        // Barge-ins onto disk (T-59). logcat holds about 90 minutes on this device and cannot be
        // enlarged, so without this the console's account of why it interrupted itself is gone by
        // morning - which is exactly how the third phantom sighting was lost.
        journal?.let { j -> scope.journalBargeIns(loop.lastBargeIn) { j.record(it) } }
        // The approval pane's voice half (REQ-14): the same transcript stream the loop dispatches
        // from, watched for the two words the pane advertises while a request is pending.
        scope.launch { voice.transcript.collect(approvals::onTranscript) }
    }

    /** The ready screen's tap: the next turn goes here, and the pane opens on its stored past. */
    fun select(sessionId: String) {
        Log.i(TAG, "session -> $sessionId")
        sessions.select(sessionId)
        health.poke()
    }

    /**
     * The visit ends (T-08/T-21, REQ-07/REQ-18): leave the conversation, hand the wall back, and
     * sound the full stop (T-12, REQ-09) - which the room hears whether or not anyone is in it.
     * Two triggers, one decision: the idle countdown elapsing, and the rail's sessions segment -
     * a person leaving for the main screen on purpose. The loop is aborted first (T-20, REQ-19):
     * a departure mid-listening closes the window and cancels whatever was in flight, so nothing
     * further dispatches into the session being left.
     */
    fun endVisit() {
        sessions.current.value?.let { Log.i(TAG, "leaving session $it") }
        sessions.endVisit(abort = loop::abort, cue = cues)
    }

    /** The ready screen's other tap: a fresh conversation, entered - empty - the moment it exists. */
    suspend fun create() {
        val session = runCatching { createTitled() }
            .onFailure { Log.w(TAG, "session create failed: ${it.message}") }
            .getOrNull() ?: return
        Log.i(TAG, "session created: ${session.id} \"${session.title}\"")
        sessions.opened(session.id)
        health.poke()
    }

    /**
     * Every session the console makes is born titled with its creation moment (T-12, REQ-14) -
     * the list's create row and the voice loop's first-dispatch provider both come through here,
     * so no path mints the untitled sessions the naming rule exists to label. The gateway's
     * duplicate-title rejection is survived by the client's numbered disambiguation.
     */
    private suspend fun createTitled(): HermesSession =
        hermes.createSessionDisambiguated(sessionCreationTitle(LocalDateTime.now()))

    override fun close() {
        // The loop owns capture and wake and closes them; the clients are ours to shut down.
        loop.close()
        voice.shutdown()
        deviceLink.close()
        announcementSink?.close()
    }

    private companion object {
        const val TAG = "HermesConsole"

        /**
         * The barge-in kill switch (2026-08-18). false = the half-duplex stopgap: the console cannot
         * be interrupted mid-reply, and it never cuts itself off.
         *
         * False while the echo dropout is being diagnosed, and false *because* of the diagnosis, not
         * only to protect the user. The detector runs either way - the gate measures every frame and
         * a trip is logged as `barge-in SUPPRESSED` (VoiceLoop.logSuppressedBargeIn) with the same
         * fields the live path logs. What the stopgap removes is the action, and the action is what
         * destroys the evidence: a real barge-in flushes the track and ends the reply, so the leak
         * that fired it is the last thing measurable about that turn. Suppressed, the reply plays on
         * and the whole of it can be read.
         *
         * true = barge-in acts on what the detector sees. Flip it back once a reply can be spoken
         * end to end with no suppressed trips in the log. One place to change, rebuild to take it.
         */
        const val BARGE_IN_ENABLED = false

        /**
         * The software AEC kill switch (2026-08-18). false = no AEC3 in the capture path: the
         * barge-in detector reads the stream exactly as the platform hands it over, which is the
         * hardware canceller's own output.
         *
         * False today because the premise the software AEC was built on did not hold. Re-measured
         * on the post-OTA build (X210XXSCEZGC) the hardware canceller passes its own gate - 3 x 60 s,
         * mean -119 dBFS, zero detections, 66 dB of suppression (docs/aec-spike.md, "The eleventh").
         * It is not dead; it drops out intermittently under the live playback pattern. With AEC3 in
         * the chain every measurement of that dropout is taken through a second canceller, and the
         * two cannot be told apart. Off, the path under investigation is the only one in it.
         *
         * true = the AEC3 session is created per capture session and the detector reads its output
         * (docs/projects/software-aec/, shelved). The code stays wired and tested behind this switch:
         * if the hardware canceller turns out to be unfixable, this is how it comes back.
         */
        const val SOFTWARE_AEC_ENABLED = false
    }
}
