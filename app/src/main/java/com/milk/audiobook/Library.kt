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
    val playlists = mutableStateListOf<Playlist>()
    var scanning by mutableStateOf(false)
        private set
    var onlyLong by mutableStateOf(true)
    var tick by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var gen = 0

    init {
        playlists.addAll(Store.playlists(app))
    }

    fun refresh() { tick++ }

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
        val p
