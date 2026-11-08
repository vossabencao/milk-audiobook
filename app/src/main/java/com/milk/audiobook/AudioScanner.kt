package com.milk.audiobook

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File

data class Book(val path: String, val title: String, val folder: String, val sizeMb: Float)

object MediaExt {
    val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "wma")
    val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "flv", "wmv", "m4v", "mpg", "mpeg")
}

fun isVideo(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in MediaExt.VIDEO

object AudioScanner {
    private val SKIP_DIRS = setOf("ringtones", "notifications", "alarms", "cache", ".thumbnails", ".trash")

    private fun roots(ctx: Context): List<File> {
        val dirs = if (Build.VERSION.SDK_INT >= 30) {
            ctx.getSystemService(StorageManager::class.java)
                .storageVolumes.mapNotNull { it.directory }
        } else {
            listOf(Environment.getExternalStorageDirectory())
        }
        return dirs.filter { it.exists() && it.canRead() }
    }

    fun scan(ctx: Context, minBytes: Long, video: Boolean = false): Flow<Book> = channelFlow {
        val ext = if (video) MediaExt.VIDEO else MediaExt.AUDIO
        for (root in roots(ctx)) {
            root.walkTopDown()
                .onEnter { dir -> dir.name.lowercase() !in SKIP_DIRS }
                .forEach { f ->
                    ensureActive()
                    if (f.isFile && f.extension.lowercase() in ext && f.length() >= minBytes) {
                        send(
                            Book(
                                path = f.absolutePath,
                                title = f.nameWithoutExtension,
                                folder = f.parentFile?.name ?: "",
                                sizeMb = f.length() / 1_048_576f
                            )
                        )
                    }
                }
        }
    }.flowOn(Dispatchers.IO)
}
