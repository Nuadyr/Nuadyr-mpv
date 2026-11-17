package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.FragmentLibraryBinding
import `is`.xyz.mpv.databinding.ItemTvMediaBinding
import `is`.xyz.mpv.preferences.PreferenceActivity
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import android.content.ActivityNotFoundException
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Phone and tablet home screen. A copy of [TvLibraryFragment] for touch: the same grid of
 * thumbnails, folder cards, progress bars and auto queue, with the options in a top bar menu
 * (and on long press) instead of the Menu key. The home screen's old buttons (open folder tree,
 * open URL, browse folders, settings) are entries of that menu. The TV fragment is left alone.
 */
class LibraryFragment : Fragment(R.layout.fragment_library) {
    private lateinit var binding: FragmentLibraryBinding

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var documentTreeOpener: ActivityResultLauncher<Uri?>
    private lateinit var playerLauncher: ActivityResultLauncher<Intent>

    private val adapter = MediaAdapter()
    private lateinit var thumbs: ThumbnailLoader

    private val queryExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loadGeneration = 0
    private var resumedOnce = false
    private var lastPath = ""

    private var allEntries: List<MediaEntry> = emptyList()
    private val byName = Comparator<MediaEntry> { a, b -> compareNatural(a.title, b.title) }
    private val byId = compareBy<MediaEntry>({ it.isAudio }, { it.id })
    /** Where playback stopped, by file path. Empty when "Save position on quit" is off. */
    private var savedProgress: Map<String, WatchProgress.Entry> = emptyMap()
    private var sortOrder = SortOrder.NEWEST
    private var kindFilter = KindFilter.ALL
    private var grouping = Grouping.NONE
    /** A custom folder of files shows as one card; files in system folders stay as single tiles. */
    private var folderCards = true
    /** Key of the card whose files are listed, or null on the main grid. */
    private var openGroupKey: String? = null
    private var openGroupName = ""
    /** Item id of the card to focus when the grid comes back from a group. */
    private var pendingFocusId: Long? = null
    private var returnFocusId: Long? = null
    private lateinit var closeGroupCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbs = ThumbnailLoader(requireContext().applicationContext)
        savedProgress = loadProgress()

        val saved = viewPrefs()
        sortOrder = enumPref(saved, PREF_SORT, SortOrder.NEWEST)
        kindFilter = enumPref(saved, PREF_FILTER, KindFilter.ALL)
        grouping = enumPref(saved, PREF_GROUP, Grouping.NONE)
        folderCards = saved.getBoolean(PREF_FOLDER_CARDS, true)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { refresh(askForAccess = false) }

        documentTreeOpener = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
            it?.let { root ->
                requireContext().contentResolver.takePersistableUriPermission(
                    root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val i = Intent(context, FilePickerActivity::class.java)
                i.putExtra("skip", FilePickerActivity.DOC_PICKER)
                i.putExtra("root", root.toString())
                i.putExtra("allow_dir", true)
                filePickerLauncher.launch(i)
            }
        }
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode != Activity.RESULT_OK)
                return@registerForActivityResult
            it.data?.getStringExtra("last_path")?.let { path -> lastPath = path }
            it.data?.getStringExtra("path")?.let { path ->
                playFile(path)
                return@registerForActivityResult
            }
            it.data?.getStringArrayExtra("paths")?.let { paths ->
                if (paths.size == 1)
                    playFile(paths[0])
                else
                    playFile(Utils.makeMemoryPlaylist(paths.asIterable()))
            }
        }
        playerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            Log.v(TAG, "returned from player ($it)")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentLibraryBinding.bind(view)
        Utils.handleInsetsAsPadding(binding.root)

        ViewCompat.setAccessibilityHeading(binding.heading, true)

        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val spanCount = max(2, ((widthDp - 32f) / TILE_WIDTH_DP).toInt())
        val layout = GridLayoutManager(requireContext(), spanCount)
        // section titles take a whole row, tiles take one cell
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (adapter.isHeader(position)) spanCount else ITEM_SPAN
        }
        binding.grid.layoutManager = layout
        binding.grid.adapter = adapter
        binding.grid.setHasFixedSize(true)

        // Back leaves a group and returns to the grid before it leaves the app.
        closeGroupCallback = object : OnBackPressedCallback(openGroupKey != null) {
            override fun handleOnBackPressed() = closeGroup()
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, closeGroupCallback)
        updateHeading()

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) =
                menuInflater.inflate(R.menu.menu_library, menu)

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId != R.id.libraryOptions)
                    return false
                showMenu()
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        // With nothing in the grid there is nothing else to focus, so this is the way to the menu.
        binding.emptyText.setOnClickListener { showMenu() }
        binding.emptyText.setOnLongClickListener { showMenu(); true }

        refresh(askForAccess = true)
    }

    override fun onResume() {
        super.onResume()
        // pick up files added while we were away, without asking for permission again
        if (resumedOnce) {
            // the player may have moved bars, whether or not the list of files changed
            refreshProgress()
            refresh(askForAccess = false)
        }
        resumedOnce = true
    }

    override fun onDestroy() {
        super.onDestroy()
        queryExecutor.shutdownNow()
        thumbs.shutdown()
    }

    // ---- progress ----

    private fun loadProgress(): Map<String, WatchProgress.Entry> {
        val ctx = requireContext()
        val resume = PreferenceManager.getDefaultSharedPreferences(ctx)
            .getBoolean("save_position", resources.getBoolean(R.bool.save_position_default))
        return if (resume) WatchProgress.readAll(ctx) else emptyMap()
    }

    private fun refreshProgress() {
        val updated = loadProgress()
        if (updated == savedProgress)
            return
        val old = savedProgress
        savedProgress = updated
        adapter.progressChanged(old, updated)
    }

    // ---- access ----

    private fun usesAllFilesAccess() =
        BuildConfig.FLAVOR == "allstorage" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @SuppressLint("NewApi") // usesAllFilesAccess() checks the SDK level
    private fun hasAccess(): Boolean {
        val ctx = requireContext()
        fun granted(p: String) =
            ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return when {
            usesAllFilesAccess() -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                granted(Manifest.permission.READ_MEDIA_VIDEO) || granted(Manifest.permission.READ_MEDIA_AUDIO)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    @SuppressLint("NewApi") // usesAllFilesAccess() checks the SDK level
    private fun requestAccess() {
        if (usesAllFilesAccess()) {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${requireContext().packageName}"))
            try {
                startActivity(i)
            } catch (e: Exception) {
                Log.w(TAG, "cannot open all files access settings", e)
            }
            return
        }
        val wanted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        else
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        permissionLauncher.launch(wanted)
    }

    // ---- loading ----

    /**
     * [rescan] also asks Android to index files it has not seen yet. Files copied on by USB, a network
     * share, adb or a file manager are often never indexed, and a plain MediaStore query cannot see them.
     */
    private fun refresh(askForAccess: Boolean, rescan: Boolean = false) {
        if (!hasAccess()) {
            allEntries = emptyList()
            adapter.submit(emptyList())
            showEmpty(R.string.lib_no_access)
            if (askForAccess)
                requestAccess()
            return
        }
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        try {
            queryExecutor.execute {
                if (rescan)
                    rescanStorage(appContext)
                val found = queryAll(appContext)
                mainHandler.post {
                    if (!isAdded || generation != loadGeneration)
                        return@post
                    allEntries = found
                    showEntries(forceFocus = false)
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            Log.w(TAG, "library query rejected", e)
        }
    }

    /** Applies the sort, filter and grouping choices to [allEntries] and shows the result. */
    private fun showEntries(forceFocus: Boolean) {
        val rows = buildRows(allEntries)
        updateHeading()
        val changed = adapter.submit(rows)
        when {
            allEntries.isEmpty() -> showEmpty(R.string.lib_empty)
            rows.isEmpty() -> showEmpty(R.string.lib_no_match)
            changed || forceFocus || !binding.grid.isVisible -> showGrid(forceFocus)
        }
    }

    private fun showGrid(forceFocus: Boolean) {
        val hadFocus = binding.emptyText.hasFocus()
        binding.emptyText.isVisible = false
        binding.grid.isVisible = true
        val returnTo = pendingFocusId
        pendingFocusId = null
        // The tile that had focus may be gone or elsewhere now, so start again at the top.
        if (forceFocus)
            binding.grid.scrollToPosition(0)
        // A TV has no pointer, so the remote needs a first tile in focus.
        if (returnTo != null || forceFocus || hadFocus || binding.root.findFocus() == null) {
            binding.grid.doOnNextLayout {
                val back = returnTo?.let { adapter.positionOfId(it) } ?: RecyclerView.NO_POSITION
                val target = if (back != RecyclerView.NO_POSITION) back
                             else adapter.initialFocusPosition(openGroupKey != null)
                focusPosition(target)
            }
        }
    }

    private fun focusPosition(position: Int) {
        if (position == RecyclerView.NO_POSITION)
            return
        val holder = binding.grid.findViewHolderForAdapterPosition(position)
        if (holder != null) {
            holder.itemView.requestFocus()
            return
        }
        // not laid out yet: scroll there and try again once it is
        binding.grid.scrollToPosition(position)
        binding.grid.doOnNextLayout {
            binding.grid.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
        }
    }

    private fun updateHeading() {
        if (!::binding.isInitialized)
            return
        val group = openGroupKey
        if (group != null)
            binding.heading.text = openGroupName
        else
            binding.heading.setText(kindFilter.title)
        if (::closeGroupCallback.isInitialized)
            closeGroupCallback.isEnabled = group != null
    }

    private fun openGroup(row: Row.Group, position: Int) {
        openGroupKey = row.key
        openGroupName = row.name
        // the card to come back to when the group is closed
        returnFocusId = adapter.idAt(position)
        showEntries(forceFocus = true)
    }

    private fun closeGroup() {
        openGroupKey = null
        openGroupName = ""
        pendingFocusId = returnFocusId
        returnFocusId = null
        showEntries(forceFocus = false)
    }

    private fun showEmpty(messageRes: Int) {
        binding.grid.isVisible = false
        binding.emptyText.setText(messageRes)
        binding.emptyText.isVisible = true
        if (binding.root.findFocus() == null)
            binding.emptyText.requestFocus()
    }

    // ---- menu ----

    /** Options list: view settings plus the old home screen buttons. From the top bar or a long press. */
    fun showMenu() {
        val ctx = context ?: return
        val entries = listOf<Pair<String, () -> Unit>>(
            getString(if (hasAccess()) R.string.tv_menu_refresh else R.string.tv_menu_allow_access) to
                { refresh(askForAccess = true, rescan = true) },
            menuValue(R.string.tv_menu_sort, sortOrder.label) to { chooseSort() },
            menuValue(R.string.tv_menu_filter, kindFilter.label) to { chooseFilter() },
            menuValue(R.string.tv_menu_group, grouping.label) to { chooseGrouping() },
            menuValue(R.string.tv_menu_series,
                if (folderCards) R.string.tv_series_on else R.string.tv_series_off) to {
                folderCards = !folderCards
                openGroupKey = null
                applyViewOptions()
            },
            getString(R.string.action_open_doc_tree) to { openDocumentTree() },
            getString(R.string.action_open_url) to { openUrl() },
            getString(R.string.tv_menu_folders) to { openFolders() },
            getString(R.string.action_settings) to
                { startActivity(Intent(ctx, PreferenceActivity::class.java)) },
        )
        AlertDialog.Builder(ctx)
            .setItems(entries.map { it.first }.toTypedArray()) { dialog, which ->
                dialog.dismiss()
                entries[which].second()
            }
            .show()
    }

    private fun menuValue(@StringRes title: Int, @StringRes value: Int) =
        getString(R.string.tv_menu_value_format, getString(title), getString(value))

    // ---- sort, filter, group ----

    private enum class SortOrder(@StringRes val label: Int) {
        NEWEST(R.string.tv_sort_newest),
        OLDEST(R.string.tv_sort_oldest),
        NAME_ASC(R.string.tv_sort_name_asc),
        NAME_DESC(R.string.tv_sort_name_desc),
        LONGEST(R.string.tv_sort_longest),
        SHORTEST(R.string.tv_sort_shortest),
    }

    private enum class KindFilter(@StringRes val label: Int, @StringRes val title: Int) {
        ALL(R.string.tv_filter_all, R.string.tv_library_title),
        VIDEO(R.string.tv_filter_video, R.string.tv_library_title_video),
        AUDIO(R.string.tv_filter_audio, R.string.tv_library_title_audio);

        fun accepts(entry: MediaEntry) = when (this) {
            ALL -> true
            VIDEO -> !entry.isAudio
            AUDIO -> entry.isAudio
        }
    }

    private enum class Grouping(@StringRes val label: Int) {
        NONE(R.string.tv_group_none),
        TYPE(R.string.tv_group_type),
        FOLDER(R.string.tv_group_folder),
    }

    /** One cell run of the grid: either a section title or a tile. */
    private sealed interface Row {
        data class Header(val label: String) : Row
        data class Item(val entry: MediaEntry) : Row
        /** One card for several related files. [key] is stable across refreshes. */
        data class Group(val key: String, val name: String, val members: List<MediaEntry>) : Row
    }

    private fun chooseSort() =
        chooseOption(R.string.tv_menu_sort, SortOrder.entries, { it.label }, sortOrder) {
            sortOrder = it
            applyViewOptions()
        }

    private fun chooseFilter() =
        chooseOption(R.string.tv_menu_filter, KindFilter.entries, { it.label }, kindFilter) {
            kindFilter = it
            applyViewOptions()
        }

    private fun chooseGrouping() =
        chooseOption(R.string.tv_menu_group, Grouping.entries, { it.label }, grouping) {
            grouping = it
            applyViewOptions()
        }

    private fun <T> chooseOption(
        @StringRes title: Int,
        options: List<T>,
        labelOf: (T) -> Int,
        current: T,
        onPicked: (T) -> Unit,
    ) {
        val ctx = context ?: return
        val labels = options.map { ctx.getString(labelOf(it)) }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                onPicked(options[which])
            }
            .show()
    }

    private fun applyViewOptions() {
        viewPrefs().edit()
            .putString(PREF_SORT, sortOrder.name)
            .putString(PREF_FILTER, kindFilter.name)
            .putString(PREF_GROUP, grouping.name)
            .putBoolean(PREF_FOLDER_CARDS, folderCards)
            .apply()
        updateHeading()
        showEntries(forceFocus = true)
    }

    private fun viewPrefs() =
        requireContext().getSharedPreferences(VIEW_PREFS, Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> enumPref(
        prefs: SharedPreferences, key: String, default: T,
    ): T = prefs.getString(key, null)
        ?.let { name -> enumValues<T>().firstOrNull { it.name == name } }
        ?: default

    private fun buildRows(all: List<MediaEntry>): List<Row> {
        val rows = buildSectionRows(all)
        val open = openGroupKey ?: return rows
        val group = rows.firstOrNull { it is Row.Group && it.key == open } as? Row.Group
        if (group == null) {
            // the files of this group are gone (or filtered out), so go back to the grid
            openGroupKey = null
            openGroupName = ""
            return rows
        }
        openGroupName = group.name
        // episodes read in order whatever the grid is sorted by
        return group.members.sortedWith(byName.then(byId)).map { Row.Item(it) }
    }

    private fun buildSectionRows(all: List<MediaEntry>): List<Row> {
        val shown = all
            .filter { kindFilter.accepts(it) }
            .sortedWith(comparatorFor(sortOrder))
        val groups: List<Pair<String, List<MediaEntry>>> = when (grouping) {
            Grouping.NONE -> return collapse("", shown)
            Grouping.TYPE -> {
                val (audio, video) = shown.partition { it.isAudio }
                listOf(
                    getString(R.string.tv_group_video) to video,
                    getString(R.string.tv_group_audio) to audio,
                )
            }
            Grouping.FOLDER -> shown
                .groupBy { folderLabel(it.folder) }
                .toList()
                .sortedWith { a, b -> compareNatural(a.first, b.first) }
        }
        return groups
            .filter { it.second.isNotEmpty() }
            .flatMap { (label, entries) -> listOf<Row>(Row.Header(label)) + collapse(label, entries) }
    }

    /**
     * Turns the files of one section into tiles. The files of a custom folder (two or more of
     * them) become one [Row.Group] that sits where the folder's first file was. Files in system
     * folders such as Downloads, or in the root of a drive, stay as single tiles.
     */
    private fun collapse(section: String, entries: List<MediaEntry>): List<Row> {
        // with Group by Folder every section already is a folder
        if (!folderCards || grouping == Grouping.FOLDER)
            return entries.map { Row.Item(it) }
        val byFolder = LinkedHashMap<String, MutableList<MediaEntry>>()
        for (e in entries) {
            val card = cardFolder(e.folder) ?: continue
            byFolder.getOrPut(card) { ArrayList() }.add(e)
        }
        val shown = HashSet<String>()
        return entries.mapNotNull { e ->
            val card = cardFolder(e.folder)
            val members = if (card == null) null else byFolder[card]
            val row: Row? = when {
                card == null || members == null || members.size < 2 -> Row.Item(e)
                shown.add(card) -> Row.Group("$section|$card", card.substringAfterLast('/'), members)
                else -> null // already inside its card
            }
            row
        }
    }

    /**
     * The folder whose card a file belongs to, or null if the file stays a single tile. A season
     * folder ("Season 2") belongs to the folder above it, so a show is one card, not one per season.
     */
    private fun cardFolder(folder: String): String? {
        val rest = VOLUME_ROOT.replaceFirst(folder, "")
        var parts = rest.split('/').filter { it.isNotEmpty() }
        while (parts.isNotEmpty() && SEASON_FOLDER.matches(parts.last()))
            parts = parts.dropLast(1)
        if (parts.isEmpty() || parts.last().lowercase() in SYSTEM_FOLDERS)
            return null
        return folder.substring(0, folder.length - rest.length) + "/" + parts.joinToString("/")
    }

    private fun comparatorFor(order: SortOrder): Comparator<MediaEntry> {
        val primary: Comparator<MediaEntry> = when (order) {
            SortOrder.NEWEST -> compareByDescending<MediaEntry> { it.dateAdded }
            SortOrder.OLDEST -> compareBy<MediaEntry> { it.dateAdded }
            SortOrder.NAME_ASC -> byName
            SortOrder.NAME_DESC -> byName.reversed()
            SortOrder.LONGEST -> compareByDescending<MediaEntry> { it.durationMs }
            SortOrder.SHORTEST -> compareBy<MediaEntry> { it.durationMs }
        }
        // equal keys (many files share a duration or a date) still need a fixed order
        return primary.then(byName).then(byId)
    }

    /** Folder of a file as shown on a section title, without the storage prefix. */
    private fun folderLabel(path: String): String {
        if (path.isEmpty())
            return getString(R.string.tv_group_unknown_folder)
        return path.removePrefix(INTERNAL_STORAGE_ROOT).removePrefix(STORAGE_ROOT).trim('/')
            .ifEmpty { getString(R.string.tv_group_storage_root) }
    }

    private fun openDocumentTree() {
        try {
            documentTreeOpener.launch(null)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no document picker on this device", e)
        }
    }

    private fun openUrl() {
        val helper = Utils.OpenUrlDialog(requireContext())
        with(helper) {
            builder.setPositiveButton(R.string.dialog_ok) { _, _ -> playFile(helper.text) }
            builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            create().show()
        }
    }

    private fun openFolders() {
        val i = Intent(context, FilePickerActivity::class.java)
        i.putExtra("skip", FilePickerActivity.FILE_PICKER)
        i.putExtra("allow_dir", true)
        if (lastPath != "")
            i.putExtra("default_path", lastPath)
        filePickerLauncher.launch(i)
    }

    /**
     * Plays a tile. The file and the ones after it in its section are handed over as one
     * playlist. That needs plain paths, so a file that cannot be read by path is played alone,
     * through its content URI, as before (it then has no progress bar).
     */
    private fun playEntry(entry: MediaEntry) {
        if (!isPlayablePath(entry.path)) {
            playFile(entry.uri.toString())
            return
        }
        val queue = adapter.queueFrom(entry)
        if (queue.size > 1)
            playFile(Utils.makeMemoryPlaylist(queue), tvQueue = true)
        else
            playFile(entry.path)
    }

    private fun isPlayablePath(path: String) =
        path.startsWith("/") && File(path).canRead()

    private fun playFile(filepath: String, tvQueue: Boolean = false) {
        val i: Intent
        if (filepath.startsWith("content://")) {
            i = Intent(Intent.ACTION_VIEW, Uri.parse(filepath))
            i.putExtra(MPVActivity.EXTRA_TV_LIBRARY, true)
        } else {
            i = Intent()
            // if we pass 'filepath' it will go directly to mpv without any processing
            i.putExtra("filepath", filepath)
            i.putExtra(MPVActivity.EXTRA_TV_LIBRARY, true)
            if (tvQueue)
                i.putExtra(MPVActivity.EXTRA_TV_QUEUE, true)
        }
        i.setClass(requireContext(), MPVActivity::class.java)
        playerLauncher.launch(i)
    }

    // ---- MediaStore ----

    private data class MediaEntry(
        val id: Long,
        val uri: Uri,
        val title: String,
        val durationMs: Long,
        val isAudio: Boolean,
        val dateAdded: Long,
        val folder: String,
        /** Full file path, or empty when MediaStore does not give one. */
        val path: String,
    )

    /**
     * Walks internal storage and removable volumes for media files MediaStore does not list, and asks
     * the media scanner to index them. Best effort: it only sees what the app is allowed to list, and
     * the walk is capped in depth, file count and time so a big drive cannot stall the refresh.
     */
    private fun rescanStorage(ctx: Context) {
        try {
            val known = HashSet<String>()
            for (e in queryAll(ctx))
                if (e.path.isNotEmpty()) known.add(e.path)

            val roots = ArrayList<File>()
            roots.add(File(INTERNAL_STORAGE_ROOT))
            File(STORAGE_ROOT).listFiles()?.forEach {
                if (it.isDirectory && it.name != "self" && it.name != "emulated")
                    roots.add(it)
            }

            val missing = ArrayList<String>()
            val deadline = System.currentTimeMillis() + RESCAN_WALK_LIMIT_MS
            fun walk(dir: File, depth: Int) {
                if (depth > RESCAN_MAX_DEPTH || missing.size >= RESCAN_MAX_FILES ||
                    System.currentTimeMillis() > deadline)
                    return
                val children = dir.listFiles() ?: return
                for (f in children) {
                    if (f.name.startsWith('.'))
                        continue
                    if (f.isDirectory) {
                        if (f.name != "Android")
                            walk(f, depth + 1)
                    } else if (f.extension.lowercase(Locale.ROOT) in RESCAN_EXTENSIONS &&
                        f.absolutePath !in known) {
                        missing.add(f.absolutePath)
                    }
                }
            }
            roots.forEach { walk(it, 0) }
            Log.v(TAG, "rescan: ${missing.size} files not in MediaStore")
            if (missing.isEmpty())
                return

            val done = CountDownLatch(missing.size)
            MediaScannerConnection.scanFile(ctx, missing.toTypedArray(), null) { _, _ -> done.countDown() }
            done.await(RESCAN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // includes SecurityException and InterruptedException; the plain query still runs after this
            Log.w(TAG, "storage rescan failed", e)
        }
    }

    private fun queryAll(ctx: Context): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        queryCollection(ctx, false, out)
        queryCollection(ctx, true, out)
        return out
    }

    // DATA gives the folder to group by and the path to play and track progress by. Entries without
    // it land in "unknown folder", are played through their content URI and have no progress bar.
    @Suppress("DEPRECATION")
    @SuppressLint("NewApi") // q checks the SDK level
    private fun queryCollection(ctx: Context, audio: Boolean, out: MutableList<MediaEntry>) {
        val q = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val base: Uri
        val projection: Array<String>
        var selection: String? = null
        if (audio) {
            base = if (q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                   else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.DATA)
            // leave out ringtones, notification sounds and alarms
            selection = "${MediaStore.Audio.Media.IS_RINGTONE}=0 AND " +
                "${MediaStore.Audio.Media.IS_NOTIFICATION}=0 AND ${MediaStore.Audio.Media.IS_ALARM}=0"
        } else {
            base = if (q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                   else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            projection = arrayOf(
                MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DATA)
        }
        try {
            ctx.contentResolver.query(base, projection, selection, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(projection[0])
                val nameCol = c.getColumnIndexOrThrow(projection[1])
                val durCol = c.getColumnIndexOrThrow(projection[2])
                val addedCol = c.getColumnIndexOrThrow(projection[3])
                val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol) ?: continue
                    val path = if (dataCol >= 0) (c.getString(dataCol) ?: "") else ""
                    out.add(MediaEntry(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        title = name.substringBeforeLast('.', name),
                        durationMs = c.getLong(durCol),
                        isAudio = audio,
                        dateAdded = c.getLong(addedCol),
                        folder = path.substringBeforeLast('/', ""),
                        path = path,
                    ))
                }
            }
        } catch (e: Exception) {
            // includes SecurityException when access was withdrawn mid-query
            Log.w(TAG, "MediaStore query failed (audio=$audio)", e)
        }
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0)
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else
            String.format(Locale.getDefault(), "%d:%02d", m, s)
    }

    // ---- grid ----

    private inner class MediaAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        init {
            setHasStableIds(true)
        }

        /** Returns false (and leaves the grid alone, so focus is kept) if nothing changed. */
        fun submit(newRows: List<Row>): Boolean {
            if (newRows == rows)
                return false
            rows = newRows
            notifyDataSetChanged()
            return true
        }

        fun isHeader(position: Int) = rows.getOrNull(position) is Row.Header

        fun idAt(position: Int): Long = getItemId(position)

        fun positionOfId(id: Long): Int = rows.indices.firstOrNull { getItemId(it) == id }
            ?: RecyclerView.NO_POSITION

        /** Position of the first tile (section titles cannot take focus), or NO_POSITION. */
        fun firstItemPosition() = rows.indexOfFirst { it !is Row.Header }

        /**
         * Where focus starts. Inside a group that is the file left half-watched, so OK carries on
         * where the person stopped; otherwise the first tile.
         */
        fun initialFocusPosition(insideGroup: Boolean): Int {
            if (insideGroup) {
                val resume = rows.indexOfFirst {
                    it is Row.Item && savedProgress.containsKey(it.entry.path)
                }
                if (resume >= 0)
                    return resume
            }
            return firstItemPosition()
        }

        /**
         * Paths of [entry] and the files after it, in the order shown. The queue stops at the next
         * section title and skips the other kind (a video does not queue songs). Empty if [entry]
         * is not in the grid or has no usable path.
         */
        fun queueFrom(entry: MediaEntry): List<String> {
            val start = rows.indexOfFirst {
                it is Row.Item && it.entry.id == entry.id && it.entry.isAudio == entry.isAudio
            }
            if (start < 0)
                return emptyList()
            val out = ArrayList<String>()
            loop@ for (i in start until rows.size) {
                // a group card queues all its files, so the player plays through a whole show
                val entries = when (val row = rows[i]) {
                    is Row.Item -> listOf(row.entry)
                    is Row.Group -> row.members
                    is Row.Header -> break@loop
                }
                for (e in entries) {
                    if (e.isAudio != entry.isAudio || !isQueueable(e.path)) {
                        if (i == start)
                            return emptyList()
                        continue
                    }
                    out.add(e.path)
                    if (out.size >= MAX_QUEUE)
                        break@loop
                }
            }
            return out
        }

        /** Rebinds just the bar of tiles whose saved position changed, so focus stays put. */
        fun progressChanged(old: Map<String, WatchProgress.Entry>, new: Map<String, WatchProgress.Entry>) {
            rows.forEachIndexed { i, row ->
                val changed = when (row) {
                    is Row.Item -> row.entry.path.isNotEmpty() && old[row.entry.path] != new[row.entry.path]
                    is Row.Group -> row.members.any { it.path.isNotEmpty() && old[it.path] != new[it.path] }
                    is Row.Header -> false
                }
                if (changed)
                    notifyItemChanged(i, PAYLOAD_PROGRESS)
            }
        }

        inner class ItemHolder(val b: ItemTvMediaBinding) : RecyclerView.ViewHolder(b.root)

        inner class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemViewType(position: Int) =
            if (isHeader(position)) VIEW_TYPE_HEADER else VIEW_TYPE_ITEM

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            if (viewType == VIEW_TYPE_HEADER) {
                val text = inflater.inflate(R.layout.item_tv_header, parent, false) as TextView
                ViewCompat.setAccessibilityHeading(text, true)
                return HeaderHolder(text)
            }
            return ItemHolder(ItemTvMediaBinding.inflate(inflater, parent, false))
        }

        override fun getItemCount() = rows.size

        override fun getItemId(position: Int): Long = when (val row = rows[position]) {
            // negative, so a title can never share an id with a tile
            is Row.Header -> HEADER_ID_BASE - position
            is Row.Item -> row.entry.id * 2 + if (row.entry.isAudio) 1 else 0
            // far below the title ids, and the same card keeps the same id between refreshes
            is Row.Group -> GROUP_ID_BASE + (row.key.hashCode().toLong() and 0xFFFFFFFFL)
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>,
        ) {
            val row = rows[position]
            if (payloads.contains(PAYLOAD_PROGRESS) && holder is ItemHolder) {
                if (row is Row.Item) {
                    bindProgress(holder.b, row.entry)
                    return
                }
                if (row is Row.Group) {
                    bindGroupProgress(holder.b, row)
                    return
                }
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).text.text = row.label
                is Row.Item -> bindItem((holder as ItemHolder).b, row.entry)
                is Row.Group -> bindGroup((holder as ItemHolder).b, row, position)
            }
        }

        private fun bindGroup(b: ItemTvMediaBinding, row: Row.Group, position: Int) {
            val ctx = b.root.context
            val count = row.members.size
            val cover = row.members[0]

            b.title.text = row.name
            b.meta.text = ctx.getString(R.string.tv_group_meta, count)
            b.badge.text = count.toString()
            b.badge.isVisible = true
            b.root.contentDescription = ctx.getString(R.string.tv_group_description, row.name, count)

            // OK opens the group; the position is read when clicked, as rows can shift
            b.root.setOnClickListener {
                val at = bindingAdapterPositionOf(b)
                if (at != RecyclerView.NO_POSITION)
                    openGroup(row, at)
            }
            b.root.setOnLongClickListener { showMenu(); true }
            bindGroupProgress(b, row)

            val key = Uri.parse("group://" + Uri.encode(row.key))
            b.thumb.tag = key
            val cached = thumbs.cached(key)
            if (cached != null) {
                showCover(b.thumb, cached)
            } else {
                showPlaceholder(b.thumb, cover.isAudio)
                val folders = row.members.map { it.folder }.distinct().take(COVER_FOLDERS)
                thumbs.fetchCover(key, folders, cover.id, cover.uri, cover.isAudio) { bmp ->
                    if (b.thumb.tag == key)
                        showCover(b.thumb, bmp)
                }
            }
        }

        private fun bindingAdapterPositionOf(b: ItemTvMediaBinding): Int =
            (binding.grid.findContainingViewHolder(b.root))?.bindingAdapterPosition
                ?: RecyclerView.NO_POSITION

        /** The bar of a card is that of the first file in it that was left half-watched. */
        private fun bindGroupProgress(b: ItemTvMediaBinding, row: Row.Group) {
            val saved = row.members.firstNotNullOfOrNull { savedProgress[it.path] }
            if (saved == null) {
                b.progress.isVisible = false
                return
            }
            b.progress.progress = saved.percent
            b.progress.contentDescription =
                b.root.context.getString(R.string.tv_progress_description, saved.percent)
            b.progress.isVisible = true
        }

        /** Posters are tall; fitting them whole beats cropping the middle out of one. */
        private fun showCover(view: ImageView, bmp: Bitmap) {
            showBitmap(view, bmp)
            if (bmp.height > bmp.width)
                view.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        private fun bindItem(b: ItemTvMediaBinding, entry: MediaEntry) {
            val ctx = b.root.context

            b.title.text = entry.title
            b.badge.isVisible = false
            b.root.contentDescription = null
            val kind = ctx.getString(if (entry.isAudio) R.string.tv_kind_audio else R.string.tv_kind_video)
            b.meta.text = if (entry.durationMs > 0)
                ctx.getString(R.string.tv_meta_format, kind, formatDuration(entry.durationMs))
            else
                kind

            b.root.setOnClickListener { playEntry(entry) }
            b.root.setOnLongClickListener { showMenu(); true }
            bindProgress(b, entry)

            b.thumb.tag = entry.uri
            val cached = thumbs.cached(entry.uri)
            if (cached != null) {
                showBitmap(b.thumb, cached)
            } else {
                showPlaceholder(b.thumb, entry.isAudio)
                thumbs.fetch(entry.id, entry.uri, entry.isAudio) { bmp ->
                    if (b.thumb.tag == entry.uri)
                        showBitmap(b.thumb, bmp)
                }
            }
        }

        private fun bindProgress(b: ItemTvMediaBinding, entry: MediaEntry) {
            val saved = savedProgress[entry.path]
            if (saved == null) {
                b.progress.isVisible = false
                return
            }
            b.progress.progress = saved.percent
            b.progress.contentDescription =
                b.root.context.getString(R.string.tv_progress_description, saved.percent)
            b.progress.isVisible = true
        }

        private fun showBitmap(view: ImageView, bmp: Bitmap) {
            view.setPadding(0, 0, 0, 0)
            view.imageTintList = null
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageBitmap(bmp)
        }

        private fun showPlaceholder(view: ImageView, audio: Boolean) {
            val pad = (PLACEHOLDER_PADDING_DP * view.resources.displayMetrics.density).toInt()
            view.setPadding(pad, pad, pad, pad)
            view.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(view.context, R.color.aengus_ink_soft))
            view.scaleType = ImageView.ScaleType.FIT_CENTER
            view.setImageResource(if (audio) R.drawable.ic_audiotrack_black_24dp
                                  else R.drawable.round_play_arrow_24)
        }
    }

    /** Small thumbnail loader: three worker threads, in-memory cache, no extra dependencies. */
    private class ThumbnailLoader(private val appContext: Context) {
        private val pool: ExecutorService = Executors.newFixedThreadPool(3)
        private val handler = Handler(Looper.getMainLooper())
        private val failed = java.util.Collections.synchronizedSet(HashSet<Uri>())
        private val cache = object : LruCache<Uri, Bitmap>(
            (Runtime.getRuntime().maxMemory() / 8).toInt()
        ) {
            override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
        }

        fun cached(uri: Uri): Bitmap? = cache.get(uri)

        fun fetch(id: Long, uri: Uri, audio: Boolean, onLoaded: (Bitmap) -> Unit) {
            if (failed.contains(uri))
                return
            try {
                pool.execute {
                    val bmp = load(id, uri, audio)
                    if (bmp == null) {
                        failed.add(uri)
                        return@execute
                    }
                    cache.put(uri, bmp)
                    handler.post { onLoaded(bmp) }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // fragment is going away
            }
        }

        /**
         * Cover of a group: a poster image in one of the files' folders (or the folder above a
         * season folder) if there is one, otherwise the thumbnail of the first file.
         */
        fun fetchCover(
            key: Uri, folders: List<String>, id: Long, uri: Uri, audio: Boolean, onLoaded: (Bitmap) -> Unit,
        ) {
            if (failed.contains(key))
                return
            try {
                pool.execute {
                    val bmp = findPoster(folders) ?: load(id, uri, audio)
                    if (bmp == null) {
                        failed.add(key)
                        return@execute
                    }
                    cache.put(key, bmp)
                    handler.post { onLoaded(bmp) }
                }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // fragment is going away
            }
        }

        private fun findPoster(folders: List<String>): Bitmap? {
            val dirs = LinkedHashSet<File>()
            for (f in folders) {
                if (f.isEmpty())
                    continue
                val dir = File(f)
                dirs.add(dir)
                // Season 1 -> the show's folder
                dir.parentFile?.let { parent ->
                    if (dir.name.matches(SEASON_FOLDER))
                        dirs.add(parent)
                }
            }
            for (dir in dirs) {
                val names = try { dir.list() } catch (e: Exception) { null } ?: continue
                val poster = POSTER_BASENAMES.firstNotNullOfOrNull { base ->
                    names.firstOrNull { n ->
                        val lower = n.lowercase()
                        POSTER_EXTENSIONS.any { ext -> lower == "$base.$ext" }
                    }
                } ?: continue
                decodePoster(File(dir, poster))?.let { return it }
            }
            return null
        }

        private fun decodePoster(file: File): Bitmap? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                    return null
                var sample = 1
                while (bounds.outHeight / (sample * 2) >= POSTER_MAX_HEIGHT)
                    sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) {
                null
            }
        }

        @Suppress("DEPRECATION")
        private fun load(id: Long, uri: Uri, audio: Boolean): Bitmap? {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appContext.contentResolver.loadThumbnail(uri, Size(THUMB_WIDTH, THUMB_HEIGHT), null)
                } else if (!audio) {
                    MediaStore.Video.Thumbnails.getThumbnail(
                        appContext.contentResolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
                } else {
                    null
                }
            } catch (e: Exception) {
                // no thumbnail available (common for audio without cover art)
                null
            }
        }

        fun shutdown() {
            pool.shutdownNow()
        }
    }

    companion object {
        private const val TAG = "mpv"
        private const val TILE_WIDTH_DP = 250f
        private const val PLACEHOLDER_PADDING_DP = 28f
        private const val THUMB_WIDTH = 480
        private const val THUMB_HEIGHT = 270

        private const val ITEM_SPAN = 1
        // The queue travels in an Intent extra, which has a size limit, so it is capped.
        private const val MAX_QUEUE = 300
        private const val PAYLOAD_PROGRESS = "progress"
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_HEADER = 1
        private const val HEADER_ID_BASE = -1L

        private const val VIEW_PREFS = "library_view"
        private const val PREF_SORT = "sort"
        private const val PREF_FILTER = "filter"
        private const val PREF_GROUP = "group"
        private const val PREF_FOLDER_CARDS = "folder_cards"

        private const val GROUP_ID_BASE = Long.MIN_VALUE / 2
        private const val COVER_FOLDERS = 4
        /** Start of a path that is the drive itself, which is never a card. */
        private val VOLUME_ROOT = Regex("^/(?:storage/emulated/\\d+|storage/[^/]+|mnt/media_rw/[^/]+)")
        private val SEASON_FOLDER = Regex("(?i)(?:season|series|s)\\s*\\d+|specials?|extras?")
        /** Folders every phone has: what is inside them is not a collection. */
        private val SYSTEM_FOLDERS = setOf(
            "download", "downloads", "movies", "movie", "video", "videos", "music", "audio", "dcim", "camera",
            "documents", "recordings", "screenrecords", "screenshots", "pictures", "podcasts", "audiobooks",
            "ringtones", "notifications", "alarms", "android", "data", "obb", "media", "bluetooth")
        private const val POSTER_MAX_HEIGHT = 540
        private val POSTER_BASENAMES = listOf("poster", "folder", "cover", "front", "thumb")
        private val POSTER_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp")

        // file paths from MediaStore.DATA, shortened on section titles
        private const val RESCAN_MAX_DEPTH = 12
        private const val RESCAN_MAX_FILES = 5000
        private const val RESCAN_WALK_LIMIT_MS = 15_000L
        private const val RESCAN_WAIT_SECONDS = 20L
        private val RESCAN_EXTENSIONS = setOf(
            "mkv", "mp4", "m4v", "avi", "mov", "wmv", "flv", "webm", "ts", "m2ts", "mpg", "mpeg", "3gp",
            "mts", "vob", "divx", "ogv", "asf", "rm", "rmvb",
            "mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "mka", "ac3", "dts")
        private const val STORAGE_ROOT = "/storage"
        private const val INTERNAL_STORAGE_ROOT = "/storage/emulated/0"

        /** A playlist is plain text, one path per line. */
        private fun isQueueable(path: String) =
            path.startsWith("/") && path.none { it == '\n' || it == '\r' || it == '\u0000' }

        /** Case-insensitive order that sorts digit runs by value, so "2" comes before "10". */
        private fun compareNatural(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                if (a[i].isDigit() && b[j].isDigit()) {
                    val iEnd = a.digitRunEnd(i)
                    val jEnd = b.digitRunEnd(j)
                    // compared as text, so a long run of digits cannot overflow
                    val x = a.substring(i, iEnd).trimStart('0')
                    val y = b.substring(j, jEnd).trimStart('0')
                    val byLength = x.length.compareTo(y.length)
                    if (byLength != 0)
                        return byLength
                    val byDigits = x.compareTo(y)
                    if (byDigits != 0)
                        return byDigits
                    i = iEnd
                    j = jEnd
                } else {
                    val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                    if (byChar != 0)
                        return byChar
                    i++
                    j++
                }
            }
            return (a.length - i).compareTo(b.length - j)
        }

        private fun String.digitRunEnd(from: Int): Int {
            var end = from
            while (end < length && this[end].isDigit())
                end++
            return end
        }
    }
}
