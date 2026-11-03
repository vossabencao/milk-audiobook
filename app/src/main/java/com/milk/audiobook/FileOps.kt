package com.milk.audiobook

import java.io.File

fun File.toBook() = Book(absolutePath, nameWithoutExtension, parentFile?.name ?: "", length() / 1_048_576f)

object FileOps {
    private val BAD = Regex("[/\\\\:*?\"<>|]")

    fun cleanName(s: String) = s.trim().replace(BAD, "_")

    fun rename(src: File, newName: String): Result<File> = runCatching {
        var base = cleanName(newName)
        val ext = src.extension
        if (ext.isNotEmpty() && base.endsWith(".$ext", ignoreCase = true)) {
            base = base.dropLast(ext.length + 1)
        }
        require(base.isNotBlank()) { "Nome vazio" }
        val dest = File(src.parentFile, if (ext.isEmpty()) base else "$base.$ext")
        if (dest == src) return@runCatching src
        if (dest.exists()) error("Já existe um arquivo com esse nome")
        if (!src.renameTo(dest)) error("Falha ao renomear")
        dest
    }

    fun move(src: File, dir: File): Result<File> = runCatching {
        require(dir.isDirectory) { "Pasta inválida" }
        if (src.parentFile?.canonicalPath == dir.canonicalPath) error("O áudio já está nessa pasta")
        val dest = File(dir, src.name)
        if (dest.exists()) error("Já existe um arquivo com esse nome na pasta")
        if (!src.renameTo(dest)) {
            try {
                src.copyTo(dest)
            } catch (e: Exception) {
                dest.delete()
                throw e
            }
            if (!src.delete()) {
                dest.delete()
                error("Falha ao mover")
            }
        }
        dest
    }

    fun copy(src: File, dir: File): Result<File> = runCatching {
        require(dir.isDirectory) { "Pasta inválida" }
        var dest = File(dir, src.name)
        var n = 1
        while (dest.exists()) {
            val suffix = if (n == 1) " (cópia)" else " (cópia $n)"
            val ext = if (src.extension.isEmpty()) "" else "." + src.extension
            dest = File(dir, src.nameWithoutExtension + suffix + ext)
            n++
        }
        try {
            src.copyTo(dest)
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        dest
    }

    fun delete(f: File): Boolean = f.delete() || !f.exists()

    fun subdirs(dir: File): List<File> =
        dir.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun mkdir(parent: File, name: String): File? {
        val n = cleanName(name)
        if (n.isBlank()) return null
        val f = File(parent, n)
        return if (f.isDirectory || f.mkdirs()) f else null
    }
}
