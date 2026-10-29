package com.myiptv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A channel exactly as it appears in the provider's playlist (or added by hand). */
data class SourceChannel(
    val key: String,
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val userAgent: String
)

/** The user's changes to one channel. Stored separately so playlist refreshes keep them. */
class ChannelEdit {
    var name: String? = null
    var group: String? = null
    var logo: String? = null
    var hidden = false
    var fav = false
    fun isEmpty() = name == null && group == null && logo == null && !hidden && !fav
}

/** A channel after the user's changes are applied. */
data class Channel(
    val key: String,
    val name: String,
    val url: String,
    val logo: String,
    val group: String,
    val userAgent: String,
    val hidden: Boolean,
    val fav: Boolean,
    val custom: Boolean
)

object M3u {
    private fun attr(line: String, name: String): String {
        val m = Regex("$name=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(line)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }

    fun parse(text: String): List<SourceChannel> {
        val out = ArrayList<SourceChannel>()
        val seen = HashMap<String, Int>()
        var name = ""
        var logo = ""
        var group = ""
        var ua = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    // Commas can appear inside attributes, so read the name after the last quote
                    val afterAttrs = line.substring(line.lastIndexOf('"') + 1)
                    val i = afterAttrs.indexOf(',')
                    name = if (i >= 0) afterAttrs.substring(i + 1).trim() else line.substringAfterLast(',', "").trim()
                    if (name.isEmpty()) name = attr(line, "tvg-name")
                    logo = attr(line, "tvg-logo")
                    group = attr(line, "group-title")
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substring(8).trim()
                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> ua = line.substringAfter('=').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val n = seen[line] ?: 0
                    seen[line] = n + 1
                    val key = if (n == 0) line else "$line#$n"
                    out.add(
                        SourceChannel(
                            key,
                            name.ifEmpty { "Channel ${out.size + 1}" },
                            line,
                            logo,
                            group.ifEmpty { "Other" },
                            ua
                        )
                    )
                    name = ""; logo = ""; group = ""
                }
            }
        }
        return out
    }
}

class Library(ctx: Context) {
    companion object {
        const val ALL = "All channels"
        const val FAV = "★ Favourites"
        const val OTHER = "Other"
        const val DEFAULT_UA = "Mozilla/5.0 (Linux; Android 13) MyIPTV/1.0"

        /** Downloads a playlist, following redirects (also http <-> https). Call off the main thread. */
        fun fetch(url: String, ua: String): String {
            var current = url
            repeat(6) {
                val c = URL(current).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 20000
                c.readTimeout = 60000
                c.setRequestProperty("User-Agent", ua.ifEmpty { DEFAULT_UA })
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw Exception("redirect without address")
                    current = URL(URL(current), loc).toString()
                    c.disconnect()
                    return@repeat
                }
                if (code !in 200..299) throw Exception("server answered $code")
                return c.inputStream.bufferedReader().use { it.readText() }
            }
            throw Exception("too many redirects")
        }
    }

    private val prefs = ctx.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val cacheFile = File(ctx.filesDir, "playlist_cache.m3u")

    var playlistUrl = ""
    var userAgent = ""

    private var source: List<SourceChannel> = emptyList()
    private val edits = HashMap<String, ChannelEdit>()
    private val custom = ArrayList<SourceChannel>()
    private val channelOrder = ArrayList<String>()
    private val categoryOrder = ArrayList<String>()
    private val hiddenCats = HashSet<String>()
    private val catRename = HashMap<String, String>()
    private val customCats = LinkedHashSet<String>()

    init {
        load()
        try {
            if (cacheFile.exists()) source = M3u.parse(cacheFile.readText())
        } catch (_: Exception) {
        }
    }

    fun hasPlaylist() = source.isNotEmpty() || custom.isNotEmpty()

    /** Replaces the provider playlist. The user's edits stay because they are keyed by stream link. */
    fun setSource(text: String) {
        val parsed = M3u.parse(text)
        if (parsed.isEmpty()) throw Exception("no channels found – is this an M3U playlist?")
        source = parsed
        cacheFile.writeText(text)
    }

    private fun resolve(g: String): String {
        var r = g
        var n = 0
        while (n < 20) {
            val next = catRename[r] ?: break
            r = next
            n++
        }
        return r
    }

    private fun edit(key: String) = edits.getOrPut(key) { ChannelEdit() }

    fun channels(): List<Channel> {
        val customKeys = custom.map { it.key }.toHashSet()
        val pos = HashMap<String, Int>()
        channelOrder.forEachIndexed { i, k -> pos[k] = i }
        val built = (source + custom).mapIndexed { i, s ->
            val e = edits[s.key]
            Pair(
                i,
                Channel(
                    key = s.key,
                    name = e?.name ?: s.name,
                    url = s.url,
                    logo = e?.logo ?: s.logo,
                    group = resolve(e?.group ?: s.group),
                    userAgent = s.userAgent.ifEmpty { userAgent },
                    hidden = e?.hidden ?: false,
                    fav = e?.fav ?: false,
                    custom = s.key in customKeys
                )
            )
        }
        return built
            .sortedWith(compareBy<Pair<Int, Channel>>({ pos[it.second.key] ?: Int.MAX_VALUE }, { it.first }))
            .map { it.second }
    }

    fun categories(includeHidden: Boolean): List<String> {
        val seen = LinkedHashSet<String>()
        for (c in channels()) seen.add(c.group)
        seen.addAll(customCats)
        val pos = HashMap<String, Int>()
        categoryOrder.forEachIndexed { i, k -> pos[k] = i }
        val ordered = seen.withIndex()
            .sortedWith(compareBy<IndexedValue<String>>({ pos[it.value] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
        return if (includeHidden) ordered else ordered.filter { it !in hiddenCats }
    }

    fun isCategoryHidden(name: String) = name in hiddenCats
    fun countIn(name: String) = channels().count { it.group == name }

    // ---------- channel edits ----------
    fun rename(key: String, name: String) { edit(key).name = name.trim().ifEmpty { null }; save() }
    fun move(key: String, group: String) { edit(key).group = group; save() }
    fun setLogo(key: String, logo: String) { edit(key).logo = logo.trim().ifEmpty { null }; save() }
    fun toggleFav(key: String) { val e = edit(key); e.fav = !e.fav; save() }
    fun toggleHidden(key: String) { val e = edit(key); e.hidden = !e.hidden; save() }
    fun reset(key: String) { edits.remove(key); save() }

    fun addChannel(name: String, url: String, logo: String, group: String) {
        custom.add(SourceChannel("custom:" + UUID.randomUUID(), name, url, logo, group, ""))
        save()
    }

    fun deleteCustom(key: String) {
        custom.removeAll { it.key == key }
        edits.remove(key)
        channelOrder.remove(key)
        save()
    }

    /** Applies a new order for a subset of channels (the ones visible in the current view). */
    fun reorder(subset: List<String>) {
        val full = channels().map { it.key }.toMutableList()
        val set = subset.toHashSet()
        var j = 0
        for (i in full.indices) {
            if (full[i] in set && j < subset.size) {
                full[i] = subset[j]
                j++
            }
        }
        channelOrder.clear()
        channelOrder.addAll(full)
        save()
    }

    // ---------- category edits ----------
    fun addCategory(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        customCats.add(n)
        save()
    }

    fun renameCategory(old: String, new: String) {
        val n = new.trim()
        if (n.isEmpty() || n == old) return
        for (e in edits.values) {
            val g = e.group
            if (g != null && resolve(g) == old) e.group = n
        }
        for (k in catRename.keys.toList()) if (catRename[k] == old) catRename[k] = n
        catRename.remove(n)
        catRename[old] = n
        for (k in catRename.keys.toList()) if (catRename[k] == k) catRename.remove(k)
        if (customCats.remove(old)) customCats.add(n)
        val i = categoryOrder.indexOf(old)
        if (i >= 0) categoryOrder[i] = n
        if (hiddenCats.remove(old)) hiddenCats.add(n)
        save()
    }

    fun deleteCategory(name: String) {
        if (name != OTHER) for (c in channels()) if (c.group == name) edit(c.key).group = OTHER
        customCats.remove(name)
        categoryOrder.remove(name)
        hiddenCats.remove(name)
        save()
    }

    fun toggleCategoryHidden(name: String) {
        if (!hiddenCats.remove(name)) hiddenCats.add(name)
        save()
    }

    fun moveCategory(name: String, dir: Int) {
        val cur = categories(true).toMutableList()
        val i = cur.indexOf(name)
        val j = i + dir
        if (i < 0 || j < 0 || j >= cur.size) return
        java.util.Collections.swap(cur, i, j)
        categoryOrder.clear()
        categoryOrder.addAll(cur)
        save()
    }

    /** The playlist with all edits applied, for use in other apps. */
    fun exportM3u(): String {
        val order = categories(true).withIndex().associate { it.value to it.index }
        val sb = StringBuilder("#EXTM3U\n")
        channels()
            .filter { !it.hidden && it.group !in hiddenCats }
            .sortedBy { order[it.group] ?: Int.MAX_VALUE }
            .forEach { c ->
                sb.append("#EXTINF:-1")
                if (c.logo.isNotEmpty()) sb.append(" tvg-logo=\"").append(c.logo).append('"')
                sb.append(" group-title=\"").append(c.group.replace("\"", "'")).append("\",").append(c.name).append('\n')
                if (c.userAgent.isNotEmpty()) sb.append("#EXTVLCOPT:http-user-agent=").append(c.userAgent).append('\n')
                sb.append(c.url).append('\n')
            }
        return sb.toString()
    }

    // ---------- storage ----------
    fun save() {
        val o = JSONObject()
        o.put("url", playlistUrl)
        o.put("ua", userAgent)
        val e = JSONObject()
        for ((k, v) in edits) {
            if (v.isEmpty()) continue
            val j = JSONObject()
            v.name?.let { j.put("name", it) }
            v.group?.let { j.put("group", it) }
            v.logo?.let { j.put("logo", it) }
            if (v.hidden) j.put("hidden", true)
            if (v.fav) j.put("fav", true)
            e.put(k, j)
        }
        o.put("edits", e)
        val cu = JSONArray()
        for (c in custom) {
            cu.put(
                JSONObject().put("key", c.key).put("name", c.name).put("url", c.url)
                    .put("logo", c.logo).put("group", c.group)
            )
        }
        o.put("custom", cu)
        o.put("order", JSONArray(channelOrder))
        o.put("catOrder", JSONArray(categoryOrder))
        o.put("hiddenCats", JSONArray(hiddenCats.toList()))
        o.put("customCats", JSONArray(customCats.toList()))
        val r = JSONObject()
        for ((k, v) in catRename) r.put(k, v)
        o.put("catRename", r)
        prefs.edit().putString("state", o.toString()).apply()
    }

    private fun load() {
        val s = prefs.getString("state", null) ?: return
        try {
            val o = JSONObject(s)
            playlistUrl = o.optString("url")
            userAgent = o.optString("ua")
            o.optJSONObject("edits")?.let { e ->
                for (k in e.keys()) {
                    val j = e.getJSONObject(k)
                    val ce = ChannelEdit()
                    if (j.has("name")) ce.name = j.getString("name")
                    if (j.has("group")) ce.group = j.getString("group")
                    if (j.has("logo")) ce.logo = j.getString("logo")
                    ce.hidden = j.optBoolean("hidden")
                    ce.fav = j.optBoolean("fav")
                    edits[k] = ce
                }
            }
            o.optJSONArray("custom")?.let { a ->
                for (i in 0 until a.length()) {
                    val j = a.getJSONObject(i)
                    custom.add(
                        SourceChannel(
                            j.getString("key"), j.optString("name"), j.optString("url"),
                            j.optString("logo"), j.optString("group", "My channels"), ""
                        )
                    )
                }
            }
            o.optJSONArray("order")?.let { a -> for (i in 0 until a.length()) channelOrder.add(a.getString(i)) }
            o.optJSONArray("catOrder")?.let { a -> for (i in 0 until a.length()) categoryOrder.add(a.getString(i)) }
            o.optJSONArray("hiddenCats")?.let { a -> for (i in 0 until a.length()) hiddenCats.add(a.getString(i)) }
            o.optJSONArray("customCats")?.let { a -> for (i in 0 until a.length()) customCats.add(a.getString(i)) }
            o.optJSONObject("catRename")?.let { r -> for (k in r.keys()) catRename[k] = r.getString(k) }
        } catch (_: Exception) {
        }
    }
}

/** Hands the current channel list to the player without hitting Android's size limit for extras. */
object PlayerQueue {
    var items: List<Channel> = emptyList()
    var index = 0
}
