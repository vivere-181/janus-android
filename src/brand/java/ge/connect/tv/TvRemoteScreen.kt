/*
 * SPDX-FileCopyrightText: 2026 The Janus contributors
 * SPDX-License-Identifier: GPL-3.0-only
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package ge.connect.tv

import android.content.Context
import android.view.KeyEvent
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ge.connect.ui.theme.LocalAppSurfaces
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.backends.lan.LanLink
import org.kde.kdeconnect_tp.R
import kotlin.math.abs

/** The remote for one TV while it's on screen: pairing, the connection (reconnecting while needed), its state. */
class TvRemoteSession(context: Context, private val deviceId: String) {
    enum class Status { CONNECTING, CONNECTED, NOT_PAIRED, PAIRING, ENTER_CODE, OFFLINE }

    data class State(
        val status: Status = Status.CONNECTING,
        val volume: Int? = null,
        val volumeMax: Int? = null,
        val muted: Boolean = false,
        val error: String? = null,
        val listening: Boolean = false,
        /** What the phone heard for voice search, as it's said and for a moment after. */
        val heard: String? = null,
        /** The app in front on the TV (its package), once the TV said. */
        val app: String? = null,
        /** False while the TV is asleep (its screen off; the remote connection stays up), as the TV reports it. */
        val screenOn: Boolean? = null,
    )

    private val app = context.applicationContext
    private val notFound = app.getString(R.string.tv_not_found)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var remote: AtvRemote? = null
    @Volatile private var pairing: AtvPairing? = null
    @Volatile private var active = false
    private var retry: Job? = null

    /**
     * The TV's address: from its Janus connection, else found on the network (TVs announce their remote service, as
     * for the Google TV app; Janus on the TV may not be running), else where it was last seen.
     */
    private fun host(): String? {
        val device = KdeConnect.getInstance().getDevice(deviceId)
        val live = device?.activeLinks?.filterIsInstance<LanLink>()?.firstOrNull()?.remoteAddress?.hostAddress
            ?: AtvDiscovery.find(app, device?.name)
        if (live != null) AtvPairings.setHost(app, deviceId, live)
        return live ?: AtvPairings.host(app, deviceId)
    }

    fun start() {
        active = true
        scope.launch { connect() }
    }

    fun stop() {
        active = false
        main.post { recognizer?.destroy(); recognizer = null; listening = false }
        retry?.cancel()
        remote?.close(); remote = null
        pairing?.close(); pairing = null
    }

    private fun connect() {
        if (!active) return
        if (!AtvPairings.isPaired(app, deviceId)) { _state.update { it.copy(status = Status.NOT_PAIRED) }; return }
        val host = host() ?: run {
            _state.update { it.copy(status = Status.OFFLINE, app = null, screenOn = null) }
            retry = scope.launch { delay(5_000); connect() }
            return
        }
        _state.update { it.copy(status = Status.CONNECTING, error = null, app = null, screenOn = null) }
        remote?.close()
        remote = AtvRemote(app, host, object : AtvRemote.Listener {
            override fun onConnected() = _state.update { it.copy(status = Status.CONNECTED, error = null) }
            override fun onVolume(level: Int, max: Int, muted: Boolean) = _state.update { it.copy(volume = level, volumeMax = max, muted = muted) }
            // Asleep, the TV still remembers the app it had: forget it until the TV says again.
            override fun onPower(on: Boolean) = _state.update { it.copy(screenOn = on, app = if (on) it.app else null) }
            override fun onApp(packageName: String) = _state.update { it.copy(app = packageName) }
            override fun onClosed(error: Throwable?, needsPairing: Boolean) {
                if (!active) return
                if (needsPairing) {
                    AtvPairings.setPaired(app, deviceId, false)
                    _state.update { it.copy(status = Status.NOT_PAIRED) }
                    return
                }
                _state.update { it.copy(status = Status.OFFLINE, app = null, screenOn = null) }
                retry = scope.launch { delay(3_000); connect() }
            }
        }).also { it.start() }
    }

    /** Asks the TV for a code (it shows it on screen). */
    fun pair(name: String) {
        _state.update { it.copy(status = Status.PAIRING, error = null) }
        scope.launch {
            val host = host() ?: run { _state.update { it.copy(status = Status.NOT_PAIRED, error = notFound) }; return@launch }
            runCatching {
                pairing?.close()
                pairing = AtvPairing(app, host).also { it.start(name) }
            }.onSuccess {
                _state.update { it.copy(status = Status.ENTER_CODE) }
            }.onFailure { e ->
                _state.update { it.copy(status = Status.NOT_PAIRED, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun submitCode(code: String, wrong: String) {
        val session = pairing ?: return
        scope.launch {
            runCatching { session.finish(code) }.onSuccess { ok ->
                if (ok) {
                    session.close(); pairing = null
                    AtvPairings.setPaired(app, deviceId, true)
                    connect()
                } else {
                    _state.update { it.copy(error = wrong) }
                }
            }.onFailure { e ->
                session.close(); pairing = null
                _state.update { it.copy(status = Status.NOT_PAIRED, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun key(code: Int) = remote?.key(code)

    // Voice search: hold to talk. The phone recognises the speech and Janus on the TV gives the words to the TV's
    // assistant (TvActions → janus://search): TVs don't all take a phone's speech over the remote protocol (this TCL's
    // remote service refuses its assistant the audio).
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var recognizer: android.speech.SpeechRecognizer? = null
    private var listening = false
    private val noSpeech = app.getString(R.string.tv_voice_unavailable)

    private val recognition = object : android.speech.RecognitionListener {
        override fun onPartialResults(partialResults: android.os.Bundle?) {
            words(partialResults)?.let { heard -> _state.update { it.copy(heard = heard) } }
        }
        override fun onResults(results: android.os.Bundle?) {
            listening = false
            val said = words(results)
            _state.update { it.copy(listening = false, heard = said) }
            if (said != null) open(TvActions.searchLink(said))
            main.postDelayed({ _state.update { if (it.heard == said && !it.listening) it.copy(heard = null) else it } }, 4_000)
        }
        override fun onError(error: Int) {
            listening = false
            _state.update { it.copy(listening = false, heard = null) }
        }
        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
    }

    private fun words(bundle: android.os.Bundle?) = bundle?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    fun startVoice() = main.post {
        if (listening) return@post
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(app)) {
            _state.update { it.copy(error = noSpeech) }
            return@post
        }
        val r = recognizer ?: android.speech.SpeechRecognizer.createSpeechRecognizer(app).also { recognizer = it }
        r.setRecognitionListener(recognition)
        listening = true
        _state.update { it.copy(listening = true, heard = null, error = null) }
        r.startListening(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName))
    }

    /** Let go: what was said so far is recognised and sent (it also ends by itself after a pause). */
    fun stopVoice() = main.post { if (listening) recognizer?.stopListening() }

    /** Opens a link in the TV app that handles it (janus://… opens Janus on the TV). */
    fun open(link: String) = remote?.open(link)

    /** Volume by [steps] presses (TVs take no absolute level from a remote), spaced so the TV keeps up. */
    fun stepVolume(steps: Int) {
        if (steps == 0) return
        val code = if (steps > 0) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
        scope.launch { repeat(abs(steps).coerceAtMost(100)) { remote?.key(code); delay(35) } }
    }
    fun text(value: String) = remote?.text(value)
}

/**
 * A TV's Home on the phone, all on one screen like the Google TV app's remote: the TV's name and state with casting and
 * power at the top, the D-pad (or touchpad, or keyboard) in the middle, the keys, volume, and YouTube, Netflix and the
 * TV's other apps at the bottom. Pairing first, once.
 */
@Composable
fun TvRemote(deviceId: String, tvName: String, onDevices: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session = remember(deviceId) { TvRemoteSession(context, deviceId) }
    DisposableEffect(session) {
        session.start()
        onDispose { session.stop() }
    }
    val state by session.state.collectAsState()
    val live = state.status == TvRemoteSession.Status.CONNECTED
    // The saved app list at once, then a fresh one once the remote is connected (it can wake Janus on the TV).
    LaunchedEffect(deviceId) { TvApps.load(context, deviceId) }
    LaunchedEffect(live) { if (live) refreshApps(session, deviceId) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Header(session, state, deviceId, tvName, live, onDevices, onSettings)
        val remote = state.status in setOf(TvRemoteSession.Status.CONNECTED, TvRemoteSession.Status.CONNECTING, TvRemoteSession.Status.OFFLINE)
        if (!remote) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { PairingCard(session, state, tvName) }
            return@Column
        }
        RemoteControls(session, state, deviceId, live)
    }
}

private fun refreshApps(session: TvRemoteSession, deviceId: String) {
    if (!GeTvPlugin.request(deviceId)) session.open(TvActions.HELLO)
}

/** The TV's name (tap to switch devices) and what's going on, then casting, power and the TV's settings in Janus. */
@Composable
private fun Header(session: TvRemoteSession, state: TvRemoteSession.State, deviceId: String, tvName: String, live: Boolean, onDevices: () -> Unit, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val casting = castingTo()
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier.weight(1f).offset(x = (-8).dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onDevices)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tvName, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                    autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize),
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(painterResource(R.drawable.ms_keyboard_arrow_down), stringResource(R.string.home_switch_device), Modifier.padding(start = 2.dp).size(20.dp))
            }
            // While connected, the app in front on the TV (as the TV reports it), with its banner.
            val all by TvApps.apps.collectAsState()
            val running = state.app?.takeIf { live && state.screenOn != false }?.let { pkg -> all[deviceId]?.firstOrNull { it.packageName == pkg } }
            val onHome = live && running == null && state.app?.contains("launcher", ignoreCase = true) == true
            val (dot, status) = when {
                state.heard != null -> colors.tertiary to "“${state.heard}”"
                live && state.error != null -> colors.error to state.error
                state.listening -> colors.tertiary to stringResource(R.string.tv_listening)
                live && state.screenOn == false -> colors.outline to stringResource(R.string.tv_asleep)
                casting != null && live -> colors.tertiary to stringResource(R.string.tv_sharing_screen)
                running != null -> ONLINE to running.label
                onHome -> ONLINE to stringResource(R.string.tv_on_home)
                live && state.app == LocalContext.current.packageName -> ONLINE to stringResource(R.string.app_name) // Janus on the TV (a Mac's screen)
                else -> when (state.status) {
                    TvRemoteSession.Status.CONNECTED -> ONLINE to stringResource(R.string.tv_remote_ready)
                    TvRemoteSession.Status.CONNECTING -> colors.outline to stringResource(R.string.tv_remote_connecting)
                    TvRemoteSession.Status.OFFLINE -> colors.error to stringResource(R.string.tv_remote_offline)
                    else -> colors.outline to stringResource(R.string.tv_remote_not_paired)
                }
            }
            val banner = running?.banner.takeIf { !state.listening && state.heard == null && casting == null }
            AnimatedContent(status to banner, label = "tv status") { (text, picture) ->
                Row(Modifier.padding(top = 2.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(dot, CircleShape))
                    if (picture != null) androidx.compose.foundation.Image(
                        picture.asImageBitmap(), null,
                        Modifier.padding(start = 8.dp).size(32.dp, 18.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = if (picture != null) 8.dp else 6.dp))
                }
            }
        }
        // Mirrors this phone with its own casting (Samsung's Smart View, else Android's Cast screen): built into Android
        // TV, smooth and with sound. Lit while casting; a tap opens it again to stop.
        val context = LocalContext.current
        FilledTonalIconButton(
            onClick = {
                val smartView = android.content.Intent().setClassName("com.samsung.android.smartmirroring", "com.samsung.android.smartmirroring.CastingDialog")
                val cast = android.content.Intent(android.provider.Settings.ACTION_CAST_SETTINGS)
                val intent = listOf(smartView, cast).firstOrNull { it.resolveActivity(context.packageManager) != null } ?: return@FilledTonalIconButton
                runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
            colors = if (casting != null) IconButtonDefaults.filledTonalIconButtonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary)
            else IconButtonDefaults.filledTonalIconButtonColors(containerColor = LocalAppSurfaces.current.card, contentColor = colors.onSurface),
            modifier = Modifier.size(44.dp),
        ) { Icon(painterResource(R.drawable.ms_cast), stringResource(if (casting != null) R.string.tv_sharing_screen else R.string.tv_share_screen)) }
        Spacer(Modifier.width(6.dp))
        FilledTonalIconButton(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); session.key(KeyEvent.KEYCODE_POWER) },
            enabled = live,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = LocalAppSurfaces.current.card, contentColor = colors.error),
            modifier = Modifier.size(44.dp),
        ) { Icon(painterResource(R.drawable.ms_power_settings_new), stringResource(R.string.tv_power)) }
        IconButton(onClick = onSettings, modifier = Modifier.offset(x = 6.dp)) {
            Icon(painterResource(R.drawable.ms_settings), stringResource(R.string.device_settings_title))
        }
    }
}

private val ONLINE = androidx.compose.ui.graphics.Color(0xFF3DBE6A)

private enum class Mode(val label: Int) { BUTTONS(R.string.tv_mode_buttons), TOUCHPAD(R.string.tv_mode_touchpad), KEYBOARD(R.string.tv_mode_keyboard) }

@Composable
private fun ColumnScope.RemoteControls(session: TvRemoteSession, state: TvRemoteSession.State, deviceId: String, live: Boolean) {
    val haptics = LocalHapticFeedback.current
    var mode by rememberSaveable { mutableStateOf(Mode.BUTTONS) }
    var apps by remember { mutableStateOf(false) }
    fun press(code: Int) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        session.key(code)
    }
    // The phone's volume keys work the TV's volume while its remote is on screen.
    DisposableEffect(session, live) {
        TvVolumeKeys.handler = if (live) { code -> session.key(code) } else null
        onDispose { TvVolumeKeys.handler = null }
    }

    ModeSwitch(mode) { mode = it }

    // The pad takes the room that's left: as big as fits, smaller while typing.
    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (mode == Mode.KEYBOARD) TypeBox(session)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (mode == Mode.TOUCHPAD) SwipePad(live, ::press, Modifier.fillMaxSize())
            else DPad(live, ::press, minOf(maxWidth, maxHeight, 296.dp))
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        RoundKey(R.drawable.ms_arrow_back, R.string.tv_back, live) { press(KeyEvent.KEYCODE_BACK) }
        RoundKey(R.drawable.ms_home, R.string.tv_home, live) { press(KeyEvent.KEYCODE_HOME) }
        VoiceKey(session, state, live)
        RoundKey(R.drawable.ms_play_pause, R.string.tv_play_pause, live) { press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
        // TVs ignore the input key from remotes like this one: Janus on the TV opens its input list instead.
        RoundKey(R.drawable.ms_input, R.string.tv_input, live) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            session.open(TvActions.INPUTS)
        }
    }

    VolumeBar(session, state, live, ::press)

    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val all by TvApps.apps.collectAsState()
        val list = all[deviceId].orEmpty()
        QUICK_APPS.forEach { (packageName, label, color) ->
            AppChip(list.firstOrNull { it.packageName == packageName }?.banner, label, androidx.compose.ui.graphics.Color(color), live) {
                session.open(TvActions.launchLink(packageName))
            }
        }
        Surface(
            onClick = { apps = true },
            shape = RoundedCornerShape(16.dp),
            color = LocalAppSurfaces.current.card,
            modifier = Modifier.weight(1f).height(56.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ms_apps), null, Modifier.size(20.dp))
                Text(stringResource(R.string.tv_apps), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }

    if (apps) AppsSheet(session, deviceId, live, state.app) { apps = false }
}

/** Buttons, touchpad or keyboard, as a pill with the chosen one lit. */
@Composable
private fun ModeSwitch(mode: Mode, onChange: (Mode) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(LocalAppSurfaces.current.card).padding(4.dp)) {
        Mode.entries.forEach { m ->
            val selected = m == mode
            val background by animateColorAsState(if (selected) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent, label = "mode")
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(background).clickable { onChange(m) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(m.label), style = MaterialTheme.typography.labelLarge,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
            }
        }
    }
}

/** YouTube or Netflix: its banner from the TV, else its colour and name. */
@Composable
private fun RowScope.AppChip(banner: android.graphics.Bitmap?, label: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = color,
        modifier = Modifier.weight(1f).height(56.dp).alpha(if (enabled) 1f else 0.5f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (banner != null) androidx.compose.foundation.Image(
                banner.asImageBitmap(), label, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            ) else Text(label, style = MaterialTheme.typography.titleMedium, color = androidx.compose.ui.graphics.Color.White)
        }
    }
}

/** YouTube and Netflix (packages on Android TV), with their colours for when there's no banner. */
private val QUICK_APPS = listOf(
    Triple("com.google.android.youtube.tv", "YouTube", 0xFFFF0033),
    Triple("com.netflix.ninja", "Netflix", 0xFF141414),
)

/** All of the TV's apps as banners, like its own app row; a tap opens one on the TV. */
@Composable
private fun AppsSheet(session: TvRemoteSession, deviceId: String, live: Boolean, open: String?, onDismiss: () -> Unit) {
    val all by TvApps.apps.collectAsState()
    val apps = all[deviceId].orEmpty()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LocalAppSurfaces.current.background,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.tv_apps_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { refreshApps(session, deviceId) }, enabled = live) {
                    Icon(painterResource(R.drawable.ms_refresh), stringResource(R.string.tv_apps_refresh))
                }
            }
            if (apps.isEmpty()) {
                Text(
                    stringResource(if (live) R.string.tv_apps_loading else R.string.tv_remote_offline),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                )
                return@Column
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                items(apps.size) { i ->
                    val app = apps[i]
                    val current = live && app.packageName == open
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = live) {
                            session.open(TvActions.launchLink(app.packageName))
                            onDismiss()
                        },
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(LocalAppSurfaces.current.card)
                                .then(if (current) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            val banner = app.banner
                            if (banner != null) androidx.compose.foundation.Image(
                                banner.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            ) else Text(app.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(6.dp))
                        }
                        Row(Modifier.padding(top = 6.dp, start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (current) Box(Modifier.padding(end = 6.dp).size(6.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                            Text(app.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The TV's volume as a slider at the level the TV reports (it moves when the TV changes it too); dragging steps the
 * volume there, as the TV only takes up/down presses. Mute beside it.
 */
@Composable
private fun VolumeBar(session: TvRemoteSession, state: TvRemoteSession.State, live: Boolean, press: (Int) -> Unit) {
    val max = (state.volumeMax ?: 100).coerceAtLeast(1)
    val level = state.volume
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(shape = CircleShape, color = LocalAppSurfaces.current.card, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { press(KeyEvent.KEYCODE_VOLUME_MUTE) }, enabled = live) {
                Icon(painterResource(if (state.muted) R.drawable.ms_volume_off else R.drawable.ms_volume_up), stringResource(R.string.tv_mute))
            }
            androidx.compose.material3.Slider(
                value = dragging ?: (level ?: 0).toFloat(),
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    val target = dragging?.toInt()
                    dragging = null
                    if (target != null && level != null) session.stepVolume(target - level)
                },
                valueRange = 0f..max.toFloat(),
                enabled = live && level != null,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    level == null -> "–"
                    state.muted -> stringResource(R.string.tv_muted)
                    else -> "${dragging?.toInt() ?: level}"
                },
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.width(56.dp),
            )
        }
    }
}

/** Hold to talk to the TV (its assistant listens, as with the Google TV app): the phone's microphone while held. */
@Composable
private fun VoiceKey(session: TvRemoteSession, state: TvRemoteSession.State, enabled: Boolean) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    val listening = state.listening
    val size by animateDpAsState(if (listening) 80.dp else 68.dp, label = "voice")
    val background by animateColorAsState(if (listening) colors.tertiary else colors.primary, label = "voice colour")
    Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size).clip(CircleShape).background(background.copy(alpha = if (enabled) 1f else 0.38f))
                .pointerInput(enabled) {
                    detectTapGestures(onPress = {
                        if (!enabled) return@detectTapGestures
                        val allowed = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (!allowed) { permission.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        session.startVoice()
                        tryAwaitRelease()
                        session.stopVoice()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ms_mic), stringResource(R.string.tv_voice), Modifier.size(30.dp),
                tint = if (listening) colors.onTertiary else colors.onPrimary)
        }
    }
}

/** Arrows around OK, as on the TV's own remote. */
@Composable
private fun DPad(enabled: Boolean, press: (Int) -> Unit, size: androidx.compose.ui.unit.Dp) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(size).clip(CircleShape).background(LocalAppSurfaces.current.card), contentAlignment = Alignment.Center) {
        val tint = colors.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
        @Composable
        fun Arrow(@DrawableRes icon: Int, label: Int, alignment: Alignment, code: Int) {
            Box(Modifier.align(alignment).size(size * 0.32f).clip(CircleShape).pointerInput(enabled) {
                detectTapGestures { if (enabled) press(code) }
            }, contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), stringResource(label), Modifier.size(36.dp), tint = tint)
            }
        }
        Arrow(R.drawable.ms_keyboard_arrow_up, R.string.tv_up, Alignment.TopCenter, KeyEvent.KEYCODE_DPAD_UP)
        Arrow(R.drawable.ms_keyboard_arrow_down, R.string.tv_down, Alignment.BottomCenter, KeyEvent.KEYCODE_DPAD_DOWN)
        Arrow(R.drawable.ms_keyboard_arrow_left, R.string.tv_left, Alignment.CenterStart, KeyEvent.KEYCODE_DPAD_LEFT)
        Arrow(R.drawable.ms_keyboard_arrow_right, R.string.tv_right, Alignment.CenterEnd, KeyEvent.KEYCODE_DPAD_RIGHT)
        Box(
            Modifier.size(size * 0.4f).clip(CircleShape).background(colors.primary.copy(alpha = if (enabled) 1f else 0.38f))
                .pointerInput(enabled) { detectTapGestures { if (enabled) press(KeyEvent.KEYCODE_DPAD_CENTER) } },
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.tv_ok), style = MaterialTheme.typography.titleLarge, color = colors.onPrimary) }
    }
}

@Composable
private fun RoundKey(@DrawableRes icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick, enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = LocalAppSurfaces.current.card, contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = Modifier.size(56.dp),
    ) { Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp)) }
}

/**
 * Whether the phone is casting its screen (Smart View, Cast…): Android shows it as an extra display or as the selected
 * video route. The name of where to, or null. Follows changes while on screen.
 */
@Composable
private fun castingTo(): String? {
    val context = LocalContext.current
    val displays = context.getSystemService(android.hardware.display.DisplayManager::class.java)
    @Suppress("DEPRECATION") val router = context.getSystemService(android.media.MediaRouter::class.java)
    fun current(): String? {
        displays?.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull()?.let { return it.name ?: "" }
        @Suppress("DEPRECATION") val route = router?.getSelectedRoute(android.media.MediaRouter.ROUTE_TYPE_LIVE_VIDEO)
        @Suppress("DEPRECATION") val default = router?.defaultRoute
        return if (route != null && route != default && route.presentationDisplay != null) route.name?.toString() ?: "" else null
    }
    var target by remember { mutableStateOf(current()) }
    DisposableEffect(displays, router) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { target = current() }
            override fun onDisplayRemoved(id: Int) { target = current() }
            override fun onDisplayChanged(id: Int) { target = current() }
        }
        displays?.registerDisplayListener(displayListener, main)
        @Suppress("DEPRECATION") val routeListener = object : android.media.MediaRouter.SimpleCallback() {
            override fun onRouteSelected(r: android.media.MediaRouter, type: Int, info: android.media.MediaRouter.RouteInfo) { target = current() }
            override fun onRouteUnselected(r: android.media.MediaRouter, type: Int, info: android.media.MediaRouter.RouteInfo) { target = current() }
            override fun onRoutePresentationDisplayChanged(r: android.media.MediaRouter, info: android.media.MediaRouter.RouteInfo) { target = current() }
        }
        @Suppress("DEPRECATION") router?.addCallback(android.media.MediaRouter.ROUTE_TYPE_LIVE_VIDEO, routeListener)
        onDispose {
            displays?.unregisterDisplayListener(displayListener)
            @Suppress("DEPRECATION") router?.removeCallback(routeListener)
        }
    }
    return target
}

@Composable
private fun PairingCard(session: TvRemoteSession, state: TvRemoteSession.State, tvName: String) {
    val phone = org.kde.kdeconnect.helpers.DeviceHelper.getDeviceName(LocalContext.current)
    var code by remember { mutableStateOf("") }
    val wrong = stringResource(R.string.tv_code_wrong)
    Surface(shape = RoundedCornerShape(28.dp), color = LocalAppSurfaces.current.card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ms_tv), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (state.status == TvRemoteSession.Status.ENTER_CODE) {
                Text(stringResource(R.string.tv_enter_code), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(stringResource(R.string.tv_enter_code_text, tvName), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center, letterSpacing = MaterialTheme.typography.headlineSmall.fontSize * 0.3f),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { session.submitCode(code, wrong) }),
                    modifier = Modifier.width(220.dp),
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = { session.submitCode(code, wrong) }, enabled = code.length == 6) { Text(stringResource(R.string.tv_pair)) }
            } else {
                Text(stringResource(R.string.tv_connect_remote), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(stringResource(R.string.tv_connect_remote_text, tvName), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
                Button(onClick = { session.pair("Janus on $phone") }, enabled = state.status != TvRemoteSession.Status.PAIRING) {
                    Text(stringResource(if (state.status == TvRemoteSession.Status.PAIRING) R.string.tv_asking else R.string.tv_connect))
                }
            }
        }
    }
}

/** Swipe to move, tap for OK (like the Google TV app's touchpad). */
@Composable
private fun SwipePad(enabled: Boolean, press: (Int) -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.clip(RoundedCornerShape(32.dp)).background(LocalAppSurfaces.current.card)
            .pointerInput(enabled) {
                detectTapGestures { if (enabled) press(KeyEvent.KEYCODE_DPAD_CENTER) }
            }
            .pointerInput(enabled) {
                val step = 48.dp.toPx()
                detectDragGestures(onDragStart = { drag = Offset.Zero }) { change, amount ->
                    change.consume()
                    drag += amount
                    // One step per stretch of the finger, in the main direction.
                    if (enabled && (abs(drag.x) > step || abs(drag.y) > step)) {
                        press(if (abs(drag.x) > abs(drag.y)) {
                            if (drag.x > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                        } else {
                            if (drag.y > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                        })
                        drag = Offset.Zero
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ms_swipe), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.tv_swipe_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

/** Types into the TV's focused text field as you type here. */
@Composable
private fun TypeBox(session: TvRemoteSession) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; session.text(it) },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.tv_type_hint)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { session.key(KeyEvent.KEYCODE_ENTER) }),
        trailingIcon = {
            TextButton(onClick = { text = ""; session.text("") }) { Text(stringResource(R.string.tv_clear)) }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
