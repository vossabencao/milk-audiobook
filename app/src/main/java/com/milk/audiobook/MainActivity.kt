package com.milk.audiobook

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { App() } }
    }
}

private fun hasAccess(ctx: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat.checkSelfPermission(
        ctx, Manifest.permission.READ_EXTERNAL_STORAGE
    ) == PackageManager.PERMISSION_GRANTED

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: LibraryViewModel = viewModel()) {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(hasAccess(ctx)) }
    var query by remember { mutableStateOf("") }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = hasAccess(ctx) }

    val legacyLauncher = rememberLauncherForActivityResult(RequestPermission()) { access = it }
    val notifLauncher = rememberLauncherForActivityResult(RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(access) { if (access) vm.scan() }

    val holder = remember { PlayerHolder(ctx) }
    DisposableEffect(Unit) { onDispose { holder.release() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Milk Audiobooks") },
                actions = {
                    IconButton(onClick = { if (access) vm.scan() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reescanear")
                    }
                }
            )
        },
        bottomBar = {
            val c = holder.controller
            if (holder.active && c != null) PlayerBar(c)
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!access) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Precisa de acesso a todos os arquivos para varrer o armazenamento.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 30) {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                            )
                        } else legacyLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }) { Text("Conceder acesso") }
                }
                return@Column
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Buscar") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(checked = vm.onlyLong, onCheckedChange = { vm.onlyLong = it; vm.scan() })
                Spacer(Modifier.width(8.dp))
                Text("Ocultar áudios curtos (< 5 MB)", Modifier.weight(1f))
            }
            if (vm.scanning) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${vm.books.size} encontrados...", Modifier.padding(12.dp))
            } else {
                Text("${vm.books.size} áudios", Modifier.padding(12.dp))
            }

            val shown = vm.books.filter {
                query.isBlank() || it.title.contains(query, true) || it.folder.contains(query, true)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.path }) { b ->
                    Column(
                        Modifier.fillMaxWidth().clickable { holder.play(b) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyLarge)
                        Text("${b.folder} • %.1f MB".format(b.sizeMb),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun PlayerBar(c: MediaController) {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf(c.isPlaying) }
    var pos by remember { mutableLongStateOf(c.currentPosition) }
    var dur by remember { mutableLongStateOf(c.duration.coerceAtLeast(0L)) }
    var title by remember { mutableStateOf(c.mediaMetadata.title?.toString() ?: "") }
    var speed by remember { mutableFloatStateOf(c.playbackParameters.speed) }

    DisposableEffect(c) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onEvents(p: Player, e: Player.Events) {
                dur = p.duration.coerceAtLeast(0L)
                title = p.mediaMetadata.title?.toString() ?: ""
                speed = p.playbackParameters.speed
            }
        }
        c.addListener(l)
        onDispose { c.removeListener(l) }
    }

    LaunchedEffect(c, playing) {
        do {
            pos = c.currentPosition
            c.currentMediaItem?.mediaId?.let { Prefs.put(ctx, it, pos) }
            if (playing) delay(1000)
        } while (playing)
    }

    Surface(tonalElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Slider(
                value = pos.toFloat().coerceIn(0f, maxOf(dur, 1L).toFloat()),
                onValueChange = { pos = it.toLong() },
                onValueChangeFinished = { c.seekTo(pos) },
                valueRange = 0f..maxOf(dur, 1L).toFloat()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(pos), style = MaterialTheme.typography.bodySmall)
                Text(fmt(dur), style = MaterialTheme.typography.bodySmall)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { c.seekBack() }) { Text("-15s") }
                FilledIconButton(onClick = { if (playing) c.pause() else c.play() }) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/Pause"
                    )
                }
                TextButton(onClick = { c.seekForward() }) { Text("+30s") }
                TextButton(onClick = {
                    val steps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                    val next = steps.firstOrNull { it > speed + 0.01f } ?: steps.first()
                    c.setPlaybackSpeed(next)
                }) { Text("${speed}x") }
            }
        }
    }
}
