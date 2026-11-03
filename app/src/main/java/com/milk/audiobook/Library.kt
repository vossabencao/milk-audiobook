package com.milk.audiobook

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("positions", Context.MODE_PRIVATE)
    fun get(c: Context, key: String): Long = sp(c).getLong(key, 0L)
    fun put(c: Context, key: String, value: Long) = sp(c).edit().putLong(key, value).apply()
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    val books = mutableStateListOf<Book>()
    var scanning by mutableStateOf(false)
        private set
    var onlyLong by mutableStateOf(true)

    private var job: Job? = null
    private var gen = 0

    fun scan() {
        job?.cancel()
        val id = ++gen
        books.clear()
        scanning = true
        job = viewModelScope.launch {
            try {
                val min = if (onlyLong) 5_000_000L else 0L
                AudioScanner.scan(getApplication(), min).collect { books.add(it) }
                books.sortBy { it.title.lowercase() }
            } finally {
                if (id == gen) scanning = false
            }
        }
    }
}

class PlayerHolder(private val ctx: Context) {
    var controller by mutableStateOf<MediaController?>(null)
        private set
    var active by mutableStateOf(false)
        private set

    private val future: ListenableFuture<MediaController> =
        MediaController.Builder(
            ctx, SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        ).buildAsync()

    init {
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(ctx))
    }

    fun play(book: Book) {
        val c = controller ?: return
        val item = MediaItem.Builder()
            .setMediaId(book.path)
            .setUri(Uri.fromFile(File(book.path)))
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(book.title).setArtist(book.folder).build()
            )
            .build()
        c.setMediaItem(item, Prefs.get(ctx, book.path))
        c.prepare()
        c.play()
        active = true
    }

    fun release() = MediaController.releaseFuture(future)
}
