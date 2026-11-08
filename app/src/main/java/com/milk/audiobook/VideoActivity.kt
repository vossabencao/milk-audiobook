package com.milk.audiobook

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

private val SUB_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml")

private fun tfmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0L)
    val h = s / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, (s % 3600) / 60, s % 60)
    else "%02d:%02d".format((s % 3600) / 60, s % 60)
}

private fun subMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "vtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.APPLICATION_SUBRIP
}

private fun trackLabel(f: Format, n: Int): String {
    val name = f.label ?: f.language?.takeIf { it != "und" } ?: "Faixa $n"
    val ch = if (f.channelCount > 0) " • ${f.channelCount}ch" else ""
    return name + ch
}

@Composable
private fun CtlIcon(
    icon: ImageVector,
    desc: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (active) Color(0xFF4FC3F7) else Color.White
        )
    }
}

@OptIn(UnstableApi::class)
class VideoActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var path = ""
    private var title by mutableStateOf("")
    private var pip by mutableStateOf(false)
    private var loop by mutableIntStateOf(0)
    private var shuffle by mutableStateOf(false)
    private var handoff = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = intent.getStringExtra("path")
        if (p == null || !File(p).isFile) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideBars()

        player = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .build()
        val prefs = getSharedPreferences("milk", Context.MODE_PRIVATE)
        shuffle = prefs.getBoolean("vshuffle", false)
        setLoop(prefs.getInt("vloop", 0))
        load(p)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { VideoScreen() }
        }
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun setLoop(v: Int) {
        loop = v
        getSharedPreferences("milk", Context.MODE_PRIVATE).edit().putInt("vloop", v).apply()
        player.repeatMode = if (v == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    private fun setShuffle(v: Boolean) {
        shuffle = v
        getSharedPreferences("milk", Context.MODE_PRIVATE).edit().putBoolean("vshuffle", v).apply()
    }

    private fun rotate() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    private fun sidecars(f: File): List<File> {
        val dir = f.parentFile ?: return emptyList()
        val base = f.nameWithoutExtension
        return dir.listFiles { x ->
            x.isFile && x.extension.lowercase() in SUB_EXT && x.name.startsWith(base)
        }?.sortedBy { it.name } ?: emptyList()
    }

    private fun load(p: String) {
        saveNow()
        path = p
        val f = File(p)
        title = f.nameWithoutExtension
        val subs = sidecars(f).mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s))
                .setMimeType(subMime(s.name))
                .setLanguage("und")
                .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
        val item = MediaItem.Builder()
            .setMediaId(p)
            .setUri(Uri.fromFile(f))
            .setSubtitleConfigurations(subs)
            .build()
        player.setMediaItem(item, Store.resumePos(this, p))
        player.prepare()
        player.playWhenReady = true
    }

    private fun siblings(): List<File> =
        File(path).parentFile?.listFiles { f ->
            f.isFile && f.extension.lowercase() in MediaExt.VIDEO
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun goTo(d: Int, wrap: Boolean = false) {
        val l = siblings()
        if (l.isEmpty()) return
        val i = l.indexOfFirst { it.absolutePath == path }
        if (i < 0) return
        if (d > 0 && shuffle && l.size > 1) {
            var n = i
            while (n == i) n = Random.nextInt(l.size)
            load(l[n].absolutePath)
            return
        }
        var n = i + d
        if (wrap) n = (n + l.size) % l.size
        if (n in l.indices) load(l[n].absolutePath)
    }

    private fun saveNow() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        Store.save(this, item.mediaId, player.currentPosition, player.duration)
    }

    private fun seekBy(d: Long) {
        var t = player.currentPosition + d
        val dur = player.duration
        if (t < 0) t = 0
        if (dur != C.TIME_UNSET && t > dur) t = dur
        player.seekTo(t)
    }

    private fun curBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun setBrightness(v: Float) {
        val a = window.attributes
        a.screenBrightness = v
        window.attributes = a
    }

    private fun pickTrack(g: Tracks.Group, i: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(g.type, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            .build()
    }

    private fun disableText() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun addSubtitle(uri: Uri) {
        val cur = player.currentMediaItem ?: return
        val name = contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: "legenda.srt"
        val old = cur.localConfiguration?.subtitleConfigurations.orEmpty()
            .map { it.buildUpon().setSelectionFlags(0).build() }
        val cfg = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(subMime(name))
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val pos = player.currentPosition
        val was = player.playWhenReady
        player.setMediaItem(cur.buildUpon().setSubtitleConfigurations(old + cfg).build(), pos)
        player.prepare()
        player.playWhenReady = was
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    private fun audioItem(f: File): MediaItem =
        MediaItem.Builder()
            .setMediaId(f.absolutePath)
            .setUri(Uri.fromFile(f))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(f.nameWithoutExtension)
                    .setArtist(f.parentFile?.name ?: "")
                    .build()
            )
            .build()

    private fun playAsAudio() {
        if (handoff) return
        handoff = true
        saveNow()
        val files = siblings()
        val list = if (files.isEmpty()) listOf(File(path)) else files
        val idx = list.indexOfFirst { it.absolutePath == path }.coerceAtLeast(0)
        val startPos = player.currentPosition
        val mode = loop
        val shuf = shuffle
        player.pause()

        val future = MediaController.Builder(
            this, SessionToken(this, ComponentName(this, PlaybackService::class.java))
        ).buildAsync()
        future.addListener({
            val c = try {
                future.get()
            } catch (e: Exception) {
                null
            }
            if (c == null) {
                handoff = false
                Toast.makeText(this, "Falha ao iniciar o áudio", Toast.LENGTH_SHORT).show()
            } else {
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            MediaController.releaseFuture(future)
                            finish()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        handoff = false
                        MediaController.releaseFuture(future)
                        Toast.makeText(
                            this@VideoActivity, "Falha ao tocar como áudio", Toast.LENGTH_SHORT
                        ).show()
                    }
                })
                c.trackSelectionParameters = c.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
                c.repeatMode = when (mode) {
                    1 -> Player.REPEAT_MODE_ONE
                    2 -> Player.REPEAT_MODE_ALL
                    else -> Player.REPEAT_MODE_OFF
                }
                c.shuffleModeEnabled = shuf
                c.setMediaItems(list.map { audioItem(it) }, idx, startPos)
                c.prepare()
                c.play()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val vs = player.videoSize
        val ratio = if (vs.width > 0 && vs.height > 0) vs.width.toFloat() / vs.height else 16f / 9f
        val r = Rational((ratio.coerceIn(0.45f, 2.3f) * 1000).toInt(), 1000)
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(r).build())
        } catch (e: Exception) {
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (::player.isInitialized && player.isPlaying) enterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }

    override fun onStop() {
        saveNow()
        if (::player.isInitialized) player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        saveNow()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    @Composable
    private fun VideoScreen() {
        val am = remember { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
        var visible by remember { mutableStateOf(true) }
        var locked by remember { mutableStateOf(false) }
        var bump by remember { mutableIntStateOf(0) }
        var aspect by remember { mutableIntStateOf(0) }
        var playing by remember { mutableStateOf(player.isPlaying) }
        var pos by remember { mutableLongStateOf(0L) }
        var dur by remember { mutableLongStateOf(0L) }
        var seeking by remember { mutableStateOf(false) }
        var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
        var overlay by remember { mutableStateOf<String?>(null) }
        var tracks by remember { mutableStateOf(player.currentTracks) }
        var dialog by remember { mutableIntStateOf(0) }

        val subPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                addSubtitle(uri)
                dialog = 0
            }
        }

        DisposableEffect(Unit) {
            val l = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onTracksChanged(t: Tracks) { tracks = t }
                override fun onPlaybackParametersChanged(p: PlaybackParameters) { speed = p.speed }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && (loop == 2 || shuffle)) goTo(1, true)
                }
            }
            player.addListener(l)
            onDispose { player.removeListener(l) }
        }

        LaunchedEffect(Unit) {
            var n = 0
            while (true) {
                if (!seeking) pos = player.currentPosition
                dur = player.duration.coerceAtLeast(0L)
                n++
                if (n % 6 == 0) saveNow()
                delay(500)
            }
        }
        LaunchedEffect(visible, playing, bump) {
            if (visible && playing) {
                delay(4000)
                visible = false
            }
        }
        LaunchedEffect(overlay) {
            if (overlay != null) {
                delay(900)
                overlay = null
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        this.player = this@VideoActivity.player
                    }
                },
                update = { v ->
                    v.resizeMode = when (aspect) {
                        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                }
            )

            if (!pip) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(locked) {
                            detectTapGestures(
                                onTap = {
                                    visible = !visible
                                    bump++
                                },
                                onDoubleTap = { o ->
                                    if (!locked) {
                                        if (o.x < size.width / 2f) {
                                            seekBy(-10_000L)
                                            overlay = "-10s"
                                        } else {
                                            seekBy(10_000L)
                                            overlay = "+10s"
                                        }
                                    }
                                }
                            )
                        }
                        .pointerInput(locked) {
                            if (locked) return@pointerInput
                            var mode = 0
                            var total = Offset.Zero
                            var startX = 0f
                            var startPos = 0L
                            var startVol = 0f
                            var startBri = 0f
                            var target = 0L
                            detectDragGestures(
                                onDragStart = { o ->
                                    mode = 0
                                    total = Offset.Zero
                                    startX = o.x
                                },
                                onDragEnd = {
                                    if (mode == 1) player.seekTo(target)
                                    mode = 0
                                },
                                onDragCancel = { mode = 0 },
                                onDrag = { change, amt ->
                                    change.consume()
                                    total += amt
                                    if (mode == 0 && (abs(total.x) > 30f || abs(total.y) > 30f)) {
                                        mode = if (abs(total.x) > abs(total.y)) 1
                                        else if (startX < size.width / 2f) 2 else 3
                                        total = Offset.Zero
                                        startPos = player.currentPosition
                                        startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                        startBri = curBrightness()
                                        target = startPos
                                    }
                                    when (mode) {
                                        1 -> {
                                            val delta = (total.x * 100f).toLong()
                                            val d = player.duration
                                            val max = if (d != C.TIME_UNSET && d > 0) d else Long.MAX_VALUE
                                            target = (startPos + delta).coerceIn(0L, max)
                                            val sign = if (target - startPos >= 0) "+" else "-"
                                            overlay = tfmt(target) + "  (" + sign +
                                                (abs(target - startPos) / 1000) + "s)"
                                        }
                                        2 -> {
                                            val b = (startBri - total.y / size.height * 1.2f)
                                                .coerceIn(0.02f, 1f)
                                            setBrightness(b)
                                            overlay = "Brilho " + (b * 100).toInt() + "%"
                                        }
                                        3 -> {
                                            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            val v = (startVol - total.y / size.height * max * 1.2f)
                                                .coerceIn(0f, max.toFloat())
                                            am.setStreamVolume(
                                                AudioManager.STREAM_MUSIC, v.roundToInt(), 0
                                            )
                                            overlay = "Volume " + (v / max * 100).toInt() + "%"
                                        }
                                    }
                                }
                            )
                        }
                )

                overlay?.let { t ->
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .background(Color(0xAA000000))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) { Text(t, color = Color.White) }
                }

                if (visible && !locked) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                        Row(
                            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                                .background(Color(0x99000000)).padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { finish() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Voltar",
                                    tint = Color.White
                                )
                            }
                            Text(
                                title, Modifier.weight(1f).padding(vertical = 6.dp),
                                color = Color.White
                            )
                            CtlIcon(Icons.Filled.ClosedCaption, "Legendas") { dialog = 2; bump++ }
                            CtlIcon(Icons.Filled.Audiotrack, "Faixa de áudio") { dialog = 1; bump++ }
                        }

                        Row(
                            Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CtlIcon(Icons.Filled.SkipPrevious, "Anterior") {
                                goTo(-1, loop == 2)
                                bump++
                            }
                            CtlIcon(Icons.Filled.Replay10, "Voltar 10 segundos") {
                                seekBy(-10_000L)
                                bump++
                            }
                            FilledIconButton(onClick = {
                                if (playing) player.pause() else player.play()
                                bump++
                            }) {
                                Icon(
                                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = "Play/Pause"
                                )
                            }
                            CtlIcon(Icons.Filled.Forward10, "Avançar 10 segundos") {
                                seekBy(10_000L)
                                bump++
                            }
                            CtlIcon(Icons.Filled.SkipNext, "Próximo") {
                                goTo(1, loop == 2)
                                bump++
                            }
                        }

                        Column(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                                .background(Color(0x99000000))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    tfmt(pos) + " / " + tfmt(dur),
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = {
                                        val steps = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
                                        val next = steps.firstOrNull { it > speed + 0.01f } ?: steps.first()
                                        player.setPlaybackSpeed(next)
                                        overlay = "Velocidade ${next}x"
                                        bump++
                                    }) {
                                        Text("$speed", color = Color.White,
                                            style = MaterialTheme.typography.titleMedium)
                                    }
                                    CtlIcon(Icons.Filled.ScreenRotation, "Girar a tela") {
                                        rotate()
                                        bump++
                                    }
                                }
                            }
                            Slider(
                                value = pos.toFloat().coerceIn(0f, maxOf(dur, 1L).toFloat()),
                                onValueChange = { seeking = true; pos = it.toLong(); bump++ },
                                onValueChangeFinished = { player.seekTo(pos); seeking = false },
                                valueRange = 0f..maxOf(dur, 1L).toFloat(),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CtlIcon(Icons.Filled.LockOpen, "Travar controles") {
                                    locked = true
                                    visible = false
                                }
                                CtlIcon(Icons.Filled.AspectRatio, "Proporção da tela") {
                                    aspect = (aspect + 1) % 3
                                    overlay = when (aspect) {
                                        1 -> "Tela: esticar"
                                        2 -> "Tela: cortar"
                                        else -> "Tela: ajustar"
                                    }
                                    bump++
                                }
                                CtlIcon(Icons.Filled.PictureInPictureAlt, "Picture-in-picture") {
                                    enterPip()
                                }
                                CtlIcon(Icons.Filled.Headphones, "Tocar como áudio") {
                                    playAsAudio()
                                }
                                CtlIcon(
                                    if (loop == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                    "Repetir",
                                    active = loop != 0
                                ) {
                                    setLoop((loop + 1) % 3)
                                    overlay = when (loop) {
                                        1 -> "Loop: 1 vídeo"
                                        2 -> "Loop: lista"
                                        else -> "Loop: desligado"
                                    }
                                    bump++
                                }
                                CtlIcon(Icons.Filled.Shuffle, "Aleatório", active = shuffle) {
                                    setShuffle(!shuffle)
                                    overlay = if (shuffle) "Aleatório: ligado" else "Aleatório: desligado"
                                    bump++
                                }
                            }
                        }
                    }
                }

                if (locked && visible) {
                    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.CenterEnd) {
                        FilledIconButton(onClick = { locked = false; bump++ }) {
                            Icon(Icons.Filled.Lock, contentDescription = "Destravar")
                        }
                    }
                }
            }

            if (dialog != 0) {
                val type = if (dialog == 1) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
                val groups = tracks.groups.filter { it.type == type }
                AlertDialog(
                    onDismissRequest = { dialog = 0 },
                    title = { Text(if (dialog == 1) "Faixa de áudio" else "Legendas") },
                    text = {
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            if (type == C.TRACK_TYPE_TEXT) {
                                item {
                                    Text(
                                        "Desativar",
                                        Modifier.fillMaxWidth()
                                            .clickable { disableText(); dialog = 0 }
                                            .padding(12.dp)
                                    )
                                }
                                item {
                                    Text(
                                        "Carregar arquivo...",
                                        Modifier.fillMaxWidth()
                                            .clickable { subPicker.launch(arrayOf("*/*")) }
                                            .padding(12.dp)
                                    )
                                }
                            }
                            if (groups.isEmpty()) {
                                item { Text("Nenhuma faixa encontrada", Modifier.padding(12.dp)) }
                            }
                            groups.forEach { g ->
                                for (i in 0 until g.length) {
                                    item {
                                        val mark = if (g.isTrackSelected(i)) "✓ " else ""
                                        Text(
                                            mark + trackLabel(g.getTrackFormat(i), i + 1),
                                            Modifier.fillMaxWidth()
                                                .clickable { pickTrack(g, i); dialog = 0 }
                                                .padding(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { dialog = 0 }) { Text("Fechar") } }
                )
            }
        }
    }
}
