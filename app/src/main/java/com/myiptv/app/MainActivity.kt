package com.myiptv.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var lib: Library
    private lateinit var toolbar: MaterialToolbar
    private lateinit var chips: ChipGroup
    private lateinit var search: EditText
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: ChannelAdapter

    private var currentCategory = Library.ALL
    private var editMode = false
    private var query = ""
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val importDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }
    private val backInEdit = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = toggleEdit()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        toolbar = findViewById(R.id.toolbar)
        chips = findViewById(R.id.chips)
        search = findViewById(R.id.search)
        status = findViewById(R.id.status)
        list = findViewById(R.id.list)
        setSupportActionBar(toolbar)
        onBackPressedDispatcher.addCallback(this, backInEdit)

        lib = Library(this)
        adapter = ChannelAdapter(
            onClick = { c -> if (editMode) channelMenu(c) else play(c) },
            onLongClick = { c -> channelMenu(c) }
        )
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                if (editMode) {
                    lib.reorder(adapter.items.map { it.key })
                    list.post { adapter.notifyDataSetChanged() }
                }
            }
        })
        touchHelper.attachToRecyclerView(list)
        adapter.onStartDrag = { vh -> touchHelper.startDrag(vh) }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString() ?: ""
                refreshList()
            }
        })

        refreshAll()
        if (lib.hasPlaylist()) {
            if (lib.playlistUrl.isNotEmpty()) reload(quiet = true)
        } else {
            playlistDialog()
        }
    }

    // ---------- menu ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Search").setIcon(android.R.drawable.ic_menu_search)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 2, 1, if (editMode) "Done" else "Edit").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        menu.add(0, 3, 2, "Manage categories")
        menu.add(0, 4, 3, "Add channel")
        menu.add(0, 5, 4, "Playlist link")
        menu.add(0, 6, 5, "Refresh playlist")
        menu.add(0, 7, 6, "Export edited playlist")
        menu.add(0, 8, 7, "Show on monitor (mirror)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> toggleSearch()
            2 -> toggleEdit()
            3 -> manageCategories()
            4 -> addChannelDialog()
            5 -> playlistDialog()
            6 -> reload(quiet = false)
            7 -> exportDoc.launch("MyIPTV.m3u")
            8 -> Util.openMirror(this)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggleSearch() {
        if (search.visibility == View.VISIBLE) {
            search.setText("")
            search.visibility = View.GONE
        } else {
            search.visibility = View.VISIBLE
            search.requestFocus()
        }
    }

    private fun toggleEdit() {
        editMode = !editMode
        toolbar.subtitle = if (editMode) "Editing" else null
        backInEdit.isEnabled = editMode
        invalidateOptionsMenu()
        refreshAll()
    }

    // ---------- lists ----------
    private fun refreshAll() {
        buildChips()
        refreshList()
    }

    private fun buildChips() {
        val names = mutableListOf(Library.ALL)
        if (lib.channels().any { it.fav }) names.add(Library.FAV)
        names.addAll(lib.categories(editMode))
        if (currentCategory !in names) currentCategory = Library.ALL
        chips.removeAllViews()
        for (n in names) {
            val chip = Chip(this)
            chip.text = if (lib.isCategoryHidden(n)) "$n (hidden)" else n
            chip.isCheckable = true
            chip.isCheckedIconVisible = false
            chip.isChecked = n == currentCategory
            chip.setOnClickListener {
                currentCategory = n
                buildChips()
                refreshList()
            }
            if (n != Library.ALL && n != Library.FAV) {
                chip.setOnLongClickListener {
                    categoryMenu(n)
                    true
                }
            }
            chips.addView(chip)
        }
    }

    private fun refreshList() {
        val visibleCats = lib.categories(false).toHashSet()
        var items = lib.channels()
        if (!editMode) items = items.filter { !it.hidden && it.group in visibleCats }
        items = when (currentCategory) {
            Library.ALL -> items
            Library.FAV -> items.filter { it.fav }
            else -> items.filter { it.group == currentCategory }
        }
        val q = query.trim()
        if (q.isNotEmpty()) items = items.filter { it.name.contains(q, ignoreCase = true) }
        adapter.submit(items, editMode)
        status.visibility = if (items.isEmpty() || editMode) View.VISIBLE else View.GONE
        status.text = when {
            editMode -> "Edit mode: drag ≡ to reorder · tap a channel to edit it · faded = hidden"
            !lib.hasPlaylist() -> "No playlist yet. Tap ⋮ → Playlist link to add yours."
            else -> "No channels here."
        }
    }

    private fun play(c: Channel) {
        val queue = adapter.items.filter { !it.hidden || it.key == c.key }
        if (queue.isEmpty()) return
        PlayerQueue.items = queue
        PlayerQueue.index = queue.indexOfFirst { it.key == c.key }.coerceAtLeast(0)
        startActivity(Intent(this, PlayerActivity::class.java))
    }

    // ---------- channel editing ----------
    private fun channelMenu(c: Channel) {
        val opts = mutableListOf<Pair<String, () -> Unit>>()
        opts.add("▶  Play" to { play(c) })
        opts.add("Rename" to { input("Rename channel", c.name) { lib.rename(c.key, it); refreshAll() } })
        opts.add("Move to category" to {
            pickCategory("Move \"${c.name}\" to") { g -> lib.move(c.key, g); refreshAll() }
        })
        opts.add("Change logo" to {
            input("Logo image link", c.logo, "https://…/logo.png") { lib.setLogo(c.key, it); refreshAll() }
        })
        opts.add((if (c.fav) "Remove from favourites" else "Add to favourites") to {
            lib.toggleFav(c.key); refreshAll()
        })
        opts.add((if (c.hidden) "Show channel" else "Hide channel") to {
            lib.toggleHidden(c.key); refreshAll()
        })
        if (c.custom) {
            opts.add("Delete channel" to { lib.deleteCustom(c.key); refreshAll() })
        } else {
            opts.add("Undo my changes to this channel" to { lib.reset(c.key); refreshAll() })
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(c.name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .show()
    }

    private fun pickCategory(title: String, onPick: (String) -> Unit) {
        val cats = lib.categories(true)
        val labels = (cats + "➕  New category…").toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setItems(labels) { _, i ->
                if (i == cats.size) {
                    input("New category name", "") { n ->
                        if (n.isNotBlank()) {
                            lib.addCategory(n)
                            onPick(n.trim())
                        }
                    }
                } else {
                    onPick(cats[i])
                }
            }
            .show()
    }

    private fun addChannelDialog() {
        val name = EditText(this).apply { hint = "Channel name"; setSingleLine() }
        val url = EditText(this).apply { hint = "Stream link (http://…)"; setSingleLine() }
        val logo = EditText(this).apply { hint = "Logo link (optional)"; setSingleLine() }
        val group = if (currentCategory == Library.ALL || currentCategory == Library.FAV) "My channels" else currentCategory
        MaterialAlertDialogBuilder(this)
            .setTitle("Add channel to \"$group\"")
            .setView(pad(name, url, logo))
            .setPositiveButton("Add") { _, _ ->
                val u = url.text.toString().trim()
                if (u.isEmpty()) {
                    toast("A stream link is needed")
                } else {
                    lib.addChannel(name.text.toString().trim().ifEmpty { "New channel" }, u, logo.text.toString().trim(), group)
                    refreshAll()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- category editing ----------
    private fun manageCategories() {
        val cats = lib.categories(true)
        val labels = cats.map { c ->
            "$c  (${lib.countIn(c)})" + if (lib.isCategoryHidden(c)) "  · hidden" else ""
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Categories – tap one to edit")
            .setItems(labels) { _, i -> categoryMenu(cats[i]) }
            .setPositiveButton("Add category") { _, _ ->
                input("New category name", "") {
                    lib.addCategory(it)
                    refreshAll()
                    manageCategories()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun categoryMenu(name: String) {
        val opts = listOf<Pair<String, () -> Unit>>(
            "Rename" to {
                input("Rename category", name) {
                    lib.renameCategory(name, it)
                    if (currentCategory == name) currentCategory = it.trim()
                    refreshAll()
                    manageCategories()
                }
            },
            "Move up" to { lib.moveCategory(name, -1); refreshAll(); manageCategories() },
            "Move down" to { lib.moveCategory(name, 1); refreshAll(); manageCategories() },
            (if (lib.isCategoryHidden(name)) "Show category" else "Hide category") to {
                lib.toggleCategoryHidden(name); refreshAll(); manageCategories()
            },
            "Delete category (its channels move to \"${Library.OTHER}\")" to {
                lib.deleteCategory(name); refreshAll(); manageCategories()
            }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }
            .show()
    }

    // ---------- playlist ----------
    private fun playlistDialog() {
        val url = EditText(this).apply {
            hint = "http://…/playlist.m3u"
            setText(lib.playlistUrl)
            setSingleLine()
        }
        val ua = EditText(this).apply {
            hint = "User-Agent (optional)"
            setText(lib.userAgent)
            setSingleLine()
        }
        val note = TextView(this).apply {
            text = "Your edits are kept when you reload or change the link."
            alpha = 0.7f
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Playlist")
            .setView(pad(url, ua, note))
            .setPositiveButton("Load") { _, _ ->
                lib.playlistUrl = url.text.toString().trim()
                lib.userAgent = ua.text.toString().trim()
                lib.save()
                reload(quiet = false)
            }
            .setNeutralButton("Open file") { _, _ -> importDoc.launch(arrayOf("*/*")) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun reload(quiet: Boolean) {
        val url = lib.playlistUrl
        if (url.isEmpty()) {
            if (!quiet) playlistDialog()
            return
        }
        if (!quiet) {
            status.text = "Loading playlist…"
            status.visibility = View.VISIBLE
        }
        io.execute {
            try {
                val text = Library.fetch(url, lib.userAgent)
                main.post {
                    try {
                        lib.setSource(text)
                        refreshAll()
                        if (!quiet) toast("${lib.channels().size} channels loaded")
                    } catch (e: Exception) {
                        if (!quiet) showError(e)
                    }
                }
            } catch (e: Exception) {
                main.post { if (!quiet) showError(e) else refreshList() }
            }
        }
    }

    private fun importFrom(uri: Uri) {
        io.execute {
            try {
                val text = contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
                main.post {
                    try {
                        lib.setSource(text)
                        lib.playlistUrl = ""
                        lib.save()
                        refreshAll()
                        toast("${lib.channels().size} channels loaded")
                    } catch (e: Exception) {
                        showError(e)
                    }
                }
            } catch (e: Exception) {
                main.post { showError(e) }
            }
        }
    }

    private fun exportTo(uri: Uri) {
        val text = lib.exportM3u()
        io.execute {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                true
            } catch (_: Exception) {
                false
            }
            main.post { toast(if (ok) "Playlist saved" else "Could not save the file") }
        }
    }

    private fun showError(e: Exception) {
        refreshList()
        toast("Could not load playlist: ${e.message}")
    }

    // ---------- helpers ----------
    private fun input(title: String, value: String, hint: String = "", onOk: (String) -> Unit) {
        val e = EditText(this).apply {
            setText(value)
            this.hint = hint
            setSingleLine()
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(pad(e))
            .setPositiveButton("OK") { _, _ -> onOk(e.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pad(vararg views: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (20 * resources.displayMetrics.density).toInt()
        setPadding(p, p / 2, p, 0)
        views.forEach { addView(it) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
