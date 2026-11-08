package com.milk.audiobook

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import java.io.File
import kotlin.random.Random

private val SUB_EXT = setOf("srt", "vtt", "ass", "ssa", "ttml")

private fun subMime(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

@OptIn(UnstableApi::class)
class VideoActivity : ComponentActivity() {
    lateinit var player: ExoPlayer
    var path = ""
    var vTitle by mutableStateOf("")
    var pip by mutableStateOf(false)
    var loop by mutableIntStateOf(0)
    var shuffle by mutableStateOf(false)
    private var handoff = false
    private val prefs by lazy { getSharedPreferences("milk", Context.MODE_PRIVATE) }

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
        player = buildPlayer()
        shuffle = prefs.getBoolean("vshuffle", false)
        applyLoop(prefs.getInt("vloop", 0))
        load(p)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VideoScreen(this@VideoActivity)
            }
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val attrs = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()
        return ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(attrs, true)
            .build()
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    fun applyLoop(v: Int) {
        loop = v
        prefs.edit().putInt("vloop", v).apply()
        player.repeatMode =
            if (v == 1) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun applyShuffle(v: Boolean) {
        shuffle = v
        prefs.edit().putBoolean("vshuffle", v).apply()
    }

    fun rotate() {
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        requestedOrientation = if (land) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun sidecars(f: File): List<File> {
        val dir = f.parentFile ?: return emptyList()
        val base = f.nameWithoutExtension
        val all = dir.listFiles() ?: return emptyList()
        return all
            .filter { it.isFile && it.extension.lowercase() in SUB_EXT }
            .filter { it.name.startsWith(base) }
            .sortedBy { it.name }
    }

    private fun load(p: String) {
        saveNow()
        path = p
        val f = File(p)
        vTitle = f.nameWithoutExtension
        val subs = sidecars(f).mapIndexed { i, s ->
            val flag = if (i == 0) C.SELECTION_FLAG_DEFAULT else 0
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(s))
                .setMimeType(subMime(s.name))
                .setLanguage("und")
                .setSelectionFlags(flag)
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

    private fun siblings(): List<File> {
        val dir = File(path).parentFile ?: return emptyList()
        val all = dir.listFiles() ?: return emptyList()
        return all
            .filter { it.isFile && it.extension.lowercase() in MediaExt.VIDEO }
            .sortedBy { it.name.lowercase() }
    }

    fun goTo(d: Int, wrap: Boolean = false) {
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

    fun saveNow() {
        if (!::player.isInitialized) return
        val item = player.currentMediaItem ?: return
        Store.save(this, item.mediaId, player.currentPosition, player.duration)
    }

    fun seekBy(d: Long) {
        var t = player.currentPosition + d
        val dur = player.duration
        if (t < 0) t = 0
        if (dur != C.TIME_UNSET && t > dur) t = dur
        player.seekTo(t)
    }

    fun curBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    fun applyBrightness(v: Float) {
        val a = window.attributes
        a.screenBrightness = v
        window.attributes = a
    }

    fun pickTrack(g: Tracks.Group, i: Int) {
        val ov = TrackSelectionOverride(g.mediaTrackGroup, i)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(g.type, false)
            .setOverrideForType(ov)
            .build()
    }

    fun disableText() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun addSubtitle(uri: Uri) {
        val cur = player.currentMediaItem ?: return
        var name = "legenda.srt"
        val q = contentResolver.query(uri, null, null, null, null)
        if (q != null) {
            q.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
        }
        val old = cur.localConfiguration?.subtitleConfigurations.orEmpty()
            .map { it.buildUpon().setSelectionFlags(0).build() }
        val cfg = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(subMime(name))
            .setLanguage("und")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val pos = player.currentPosition
        val was = player.playWhenReady
        val item = cur.buildUpon().setSubtitleConfigurations(old + cfg).build()
        player.setMediaItem(item, pos)
        player.prepare()
        player.playWhenReady = was
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    private fun audioItem(f: File): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(f.nameWithoutExtension)
            .setArtist(f.parentFile?.name ?: "")
            .build()
        return MediaItem.Builder()
            .setMediaId(f.absolutePath)
            .setUri(Uri.fromFile(f))
            .setMediaMetadata(meta)
            .build()
    }

    fun playAsAudio() {
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

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
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
                            this@VideoActivity,
                            "Falha ao tocar como áudio",
                            Toast.LENGTH_SHORT
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

    fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        val vs = player.videoSize
        val ok = vs.width > 0 && vs.height > 0
        val ratio = if (ok) vs.width.toFloat() / vs.height else 16f / 9f
        val r = Rational((ratio.coerceIn(0.45f, 2.3f) * 1000).toInt(), 1000)
        try {
            val params = PictureInPictureParams.Builder().setAspectRatio(r).build()
            enterPictureInPictureMode(params)
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
}
