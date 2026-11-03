package com.milk.audiobook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Store {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("milk", Context.MODE_PRIVATE)

    fun save(c: Context, path: String, pos: Long, dur: Long) {
        val e = sp(c).edit().putLong("pos:$path", pos.coerceAtLeast(0L))
        if (dur > 0) e.putLong("dur:$path", dur)
        e.apply()
    }

    private fun dur(c: Context, path: String): Long = sp(c).getLong("dur:$path", 0L)

    fun resumePos(c: Context, path: String): Long {
        val pos = sp(c).getLong("pos:$path", 0L)
        val d = dur(c, path)
        return if (d > 0 && pos >= d - 3000) 0L else pos
    }

    fun progress(c: Context, path: String): Float {
        val d = dur(c, path)
        if (d <= 0) return 0f
        return (sp(c).getLong("pos:$path", 0L) / d.toFloat()).coerceIn(0f, 1f)
    }

    fun forget(c: Context, path: String) {
        sp(c).edit().remove("pos:$path").remove("dur:$path").apply()
    }

    fun move(c: Context, old: String, new: String) {
        val s = sp(c)
        val p = s.getLong("pos:$old", 0L)
        val d = s.getLong("dur:$old", 0L)
        val e = s.edit().remove("pos:$old").remove("dur:$old")
        if (p > 0) e.putLong("pos:$new", p)
        if (d > 0) e.putLong("dur:$new", d)
        if (s.getString("last", null) == old) e.putString("last", new)
        e.apply()
    }

    fun setLast(c: Context, path: String) {
        sp(c).edit().putString("last", path).apply()
    }

    fun last(c: Context): String? =
        sp(c).getString("last", null)?.takeIf { File(it).isFile }

    fun getRepeat(c: Context): Int = sp(c).getInt("repeat", 0)
    fun setRepeat(c: Context, mode: Int) = sp(c).edit().putInt("repeat", mode).apply()

    fun playlists(c: Context): List<Playlist> {
        val raw = sp(c).getString("playlists", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val items = o.getJSONArray("items")
                Playlist(o.getString("name"), (0 until items.length()).map { items.getString(it) })
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun savePlaylists(c: Context, list: List<Playlist>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().put("name", p.name).put("items", JSONArray(p.items)))
        }
        sp(c).edit().putString("playlists", arr.toString()).apply()
    }
}
