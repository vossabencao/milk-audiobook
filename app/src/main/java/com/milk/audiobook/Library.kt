package com.milk.audiobook

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Playlist(val name: String, val items: List<String>)

class LibraryViewModel(private val app: Application) : AndroidViewModel(app) {
    val books = mutableStateListOf<Book>()
    val videos = mutableStateListOf<Book>()
    val playlists = mutableStateListOf<Playlist>()
    var scanning by mutableStateOf(false)
        private set
    var scanningV by mutableStateOf(false)
        private set
    var onlyLong by mutableStateOf(true)
    var tick by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var gen = 0
    private var jobV: Job? = null
    private var genV = 0

    init {
        playlists.addAll(Store.playlists(app))
    }

    fun refresh() { tick++ }

    fun scanAll() {
        scan()
        scanVideos()
    }

    fun scan() {
        job?.cancel()
        val id = ++gen
        books.clear()
        scanning = true
        job = viewModelScope.launch {
            try {
                val min = if (onlyLong) 5_000_000L else 0L
                AudioScanner.scan(app, min).collect { books.add(it) }
                books.sortBy { it.title.lowercase() }
            } finally {
                if (id == gen) scanning = false
            }
        }
    }

    fun scanVideos() {
        jobV?.cancel()
        val id = ++genV
        videos.clear()
        scanningV = true
        jobV = viewModelScope.launch {
            try {
                AudioScanner.scan(app, 0L, video = true).collect { videos.add(it) }
                videos.sortBy { it.title.lowercase() }
            } finally {
                if (id == genV) scanningV = false
            }
        }
    }

    fun bookOf(path: String): Book? =
        books.firstOrNull { it.path == path } ?: File(path).takeIf { it.isFile }?.toBook()

    // ---------- playlists ----------

    private fun persist() = Store.savePlaylists(app, playlists.toList())

    private fun mapPlaylists(f: (List<String>) -> List<String>) {
        for (i in playlists.indices) {
            val p = playlists[i]
            val n = f(p.items)
            if (n != p.items) playlists[i] = p.copy(items = n)
        }
        persist()
    }

    fun createPlaylist(name: String, first: String? = null) {
        val n = name.trim()
        if (n.isEmpty() || playlists.any { it.name.equals(n, true) }) {
            message = "Nome inválido ou já existe"
            return
        }
        playlists.add(Playlist(n, listOfNotNull(first)))
        persist()
        message = "Playlist criada"
    }

    fun deletePlaylist(name: String) {
        playlists.removeAll { it.name == name }
        persist()
        message = "Playlist excluída"
    }

    fun addToPlaylist(name: String, path: String) {
        val i = playlists.indexOfFirst { it.name == name }
        if (i < 0) return
        val p = playlists[i]
        if (path in p.items) {
            message = "Já está na playlist"
            return
        }
        playlists[i] = p.copy(items = p.items + path)
        persist()
        message = "Adicionado a $name"
    }

    fun removeFromPlaylist(name: String, path: String) {
        val i = playlists.indexOfFirst { it.name == name }
        if (i < 0) return
        playlists[i] = playlists[i].copy(items = playlists[i].items - path)
        persist()
    }

    // ---------- editar arquivos ----------

    private fun scanFiles(paths: List<String>) {
        try {
            MediaScannerConnection.scanFile(app, paths.toTypedArray(), null, null)
        } catch (e: Exception) {
        }
    }

    private fun afterMove(old: Book, f: File) {
        val nb = f.toBook()
        val target = if (isVideo(nb.path)) videos else books
        val i = target.indexOfFirst { it.path == old.path }
        if (i >= 0) target[i] = nb else target.add(nb)
        target.sortBy { it.title.lowercase() }
        Store.move(app, old.path, nb.path)
        mapPlaylists { l -> l.map { if (it == old.path) nb.path else it } }
        scanFiles(listOf(old.path, nb.path))
        tick++
    }

    fun rename(b: Book, name: String) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { FileOps.rename(File(b.path), name) }
            r.onSuccess { f ->
                afterMove(b, f)
                message = "Renomeado"
            }.onFailure { message = it.message ?: "Falha ao renomear" }
        }
    }

    fun move(b: Book, dir: File) {
        message = "Movendo..."
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { FileOps.move(File(b.path), dir) }
            r.onSuccess { f ->
                afterMove(b, f)
                message = "Movido para ${dir.name}"
            }.onFailure { message = it.message ?: "Falha ao mover" }
        }
    }

    fun copy(b: Book, dir: File) {
        message = "Copiando..."
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { FileOps.copy(File(b.path), dir) }
            r.onSuccess { f ->
                val nb = f.toBook()
                val target = if (isVideo(nb.path)) videos else books
                target.add(nb)
                target.sortBy { it.title.lowercase() }
                scanFiles(listOf(nb.path))
                tick++
                message = "Copiado para ${dir.name}"
            }.onFailure { message = it.message ?: "Falha ao copiar" }
        }
    }

    fun delete(b: Book) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { FileOps.delete(File(b.path)) }
            if (ok) {
                books.removeAll { it.path == b.path }
                videos.removeAll { it.path == b.path }
                Store.forget(app, b.path)
                mapPlaylists { l -> l - b.path }
                scanFiles(listOf(b.path))
                tick++
                message = "Excluído"
            } else {
                message = "Falha ao excluir"
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
        future.addListener({
            val c = future.get()
            controller = c
            active = c.mediaItemCount > 0
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun item(book: Book) = MediaItem.Builder()
        .setMediaId(book.path)
        .setUri(Uri.fromFile(File(book.path)))
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(book.title).setArtist(book.folder).build()
        )
        .build()

    fun play(book: Book) = playQueue(listOf(book), 0)

    fun playQueue(list: List<Book>, start: Int) {
        val c = controller ?: return
        if (list.isEmpty()) return
        val i = start.coerceIn(0, list.size - 1)
        c.repeatMode = Store.getRepeat(ctx)
        c.setMediaItems(list.map { item(it) }, i, Store.resumePos(ctx, list[i].path))
        c.prepare()
        c.play()
        active = true
    }

    fun stopIfUsing(path: String) {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(i).mediaId == path) {
                c.stop()
                c.clearMediaItems()
                active = false
                return
            }
        }
    }

    fun release() = MediaController.releaseFuture(future)
}
