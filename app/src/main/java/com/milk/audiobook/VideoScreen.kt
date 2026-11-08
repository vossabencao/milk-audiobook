package com.milk.audiobook

import android.content.Context
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private fun tfmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

private fun trackLabel(f: Format, n: Int): String {
    val lang = f.language
    val name = f.label ?: (if (lang != null && lang != "und") lang else "Faixa $n")
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
    val tint = if (active) Color(0xFF4FC3F7) else Color.White
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = desc, tint = tint)
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(a: VideoActivity) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val exo = a.player

    var visible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var bump by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(exo.isPlaying) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(exo.playbackParameters.speed) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(exo.currentTracks) }
    var dialog by remember { mutableIntStateOf(0) }

    val subPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            a.addSubtitle(uri)
            dialog = 0
        }
    }

    DisposableEffect(Unit) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onTracksChanged(t: Tracks) {
                tracks = t
            }

            override fun onPlaybackParametersChanged(p: PlaybackParameters) {
                speed = p.speed
            }

            override fun onPlaybackStateChanged(state: Int) {
                val auto = a.loop == 2 || a.shuffle
                if (state == Player.STATE_ENDED && auto) a.goTo(1, true)
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l) }
    }

    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            if (!seeking) pos = exo.currentPosition
            dur = exo.duration.coerceAtLeast(0L)
            n++
            if (n % 6 == 0) a.saveNow()
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
                    this.player = exo
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

        if (!a.pip) {
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
                                        a.seekBy(-10_000L)
                                        overlay = "-10s"
                                    } else {
                                        a.seekBy(10_000L)
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
                                if (mode == 1) exo.seekTo(target)
                                mode = 0
                            },
                            onDragCancel = { mode = 0 },
                            onDrag = { change, amt ->
                                change.consume()
                                total += amt
                                val moved = abs(total.x) > 30f || abs(total.y) > 30f
                                if (mode == 0 && moved) {
                                    mode = if (abs(total.x) > abs(total.y)) {
                                        1
                                    } else if (startX < size.width / 2f) {
                                        2
                                    } else {
                                        3
                                    }
                                    total = Offset.Zero
                                    startPos = exo.currentPosition
                                    startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                                    startBri = a.curBrightness()
                                    target = startPos
                                }
                                if (mode == 1) {
                                    val delta = (total.x * 100f).toLong()
                                    val d = exo.duration
                                    val max = if (d != C.TIME_UNSET && d > 0) d else Long.MAX_VALUE
                                    target = (startPos + delta).coerceIn(0L, max)
                                    val diff = target - startPos
                                    val sign = if (diff >= 0) "+" else "-"
                                    overlay = tfmt(target) + "  (" + sign + (abs(diff) / 1000) + "s)"
                                } else if (mode == 2) {
                                    val b = (startBri - total.y / size.height * 1.2f)
                                        .coerceIn(0.02f, 1f)
                                    a.applyBrightness(b)
                                    overlay = "Brilho " + (b * 100).toInt() + "%"
                                } else if (mode == 3) {
                                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val v = (startVol - total.y / size.height * max * 1.2f)
                                        .coerceIn(0f, max.toFloat())
                                    am.setStreamVolume(AudioManager.STREAM_MUSIC, v.roundToInt(), 0)
                                    overlay = "Volume " + (v / max * 100).toInt() + "%"
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
                ) {
                    Text(t, color = Color.White)
                }
            }

            if (visible && !locked) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                    Row(
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .background(Color(0x99000000))
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { a.finish() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Voltar",
                                tint = Color.White
                            )
                        }
                        Text(
                            a.vTitle,
                            Modifier.weight(1f).padding(vertical = 6.dp),
                            color = Color.White
                        )
                        CtlIcon(Icons.Filled.ClosedCaption, "Legendas") {
                            dialog = 2
                            bump++
                        }
                        CtlIcon(Icons.Filled.Audiotrack, "Faixa de áudio") {
                            dialog = 1
                            bump++
                        }
                    }

                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CtlIcon(Icons.Filled.SkipPrevious, "Anterior") {
                            a.goTo(-1, a.loop == 2)
                            bump++
                        }
                        CtlIcon(Icons.Filled.Replay10, "Voltar 10 segundos") {
                            a.seekBy(-10_000L)
                            bump++
                        }
                        FilledIconButton(onClick = {
                            if (playing) exo.pause() else exo.play()
                            bump++
                        }) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Play/Pause"
                            )
                        }
                        CtlIcon(Icons.Filled.Forward10, "Avançar 10 segundos") {
                            a.seekBy(10_000L)
                            bump++
                        }
                        CtlIcon(Icons.Filled.SkipNext, "Próximo") {
                            a.goTo(1, a.loop == 2)
                            bump++
                        }
                    }

                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
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
                                    exo.setPlaybackSpeed(next)
                                    overlay = "Velocidade " + next + "x"
                                    bump++
                                }) {
                                    Text(
                                        speed.toString(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                                CtlIcon(Icons.Filled.ScreenRotation, "Girar a tela") {
                                    a.rotate()
                                    bump++
                                }
                            }
                        }
                        Slider(
                            value = pos.toFloat().coerceIn(0f, maxOf(dur, 1L).toFloat()),
                            onValueChange = {
                                seeking = true
                                pos = it.toLong()
                                bump++
                            },
                            onValueChangeFinished = {
                                exo.seekTo(pos)
                                seeking = false
                            },
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
                                a.enterPip()
                            }
                            CtlIcon(Icons.Filled.Headphones, "Tocar como áudio") {
                                a.playAsAudio()
                            }
                            CtlIcon(
                                if (a.loop == 1) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                                "Repetir",
                                active = a.loop != 0
                            ) {
                                a.applyLoop((a.loop + 1) % 3)
                                overlay = when (a.loop) {
                                    1 -> "Loop: 1 vídeo"
                                    2 -> "Loop: lista"
                                    else -> "Loop: desligado"
                                }
                                bump++
                            }
                            CtlIcon(Icons.Filled.Shuffle, "Aleatório", active = a.shuffle) {
                                a.applyShuffle(!a.shuffle)
                                overlay = if (a.shuffle) "Aleatório: ligado" else "Aleatório: desligado"
                                bump++
                            }
                        }
                    }
                }
            }

            if (locked && visible) {
                Box(
                    Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    FilledIconButton(onClick = {
                        locked = false
                        bump++
                    }) {
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
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            a.disableText()
                                            dialog = 0
                                        }
                                        .padding(12.dp)
                                )
                            }
                            item {
                                Text(
                                    "Carregar arquivo...",
                                    Modifier
                                        .fillMaxWidth()
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
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                a.pickTrack(g, i)
                                                dialog = 0
                                            }
                                            .padding(12.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { dialog = 0 }) { Text("Fechar") }
                }
            )
        }
    }
}
