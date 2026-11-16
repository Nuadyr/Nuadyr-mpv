package `is`.xyz.mpv

import `is`.xyz.mpv.databinding.FragmentTvLibraryBinding
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Android TV home screen. Lists every video and audio file that MediaStore knows about
 * (internal storage, SD cards and USB drives) as a grid of thumbnails. There are no buttons:
 * OK plays a file, and Menu (or press and hold OK) opens the extra options, which include how
 * the grid is sorted, filtered and grouped.
 */
class TvLibraryFragment : Fragment(R.layout.fragment_tv_library) {
    private lateinit var binding: FragmentTvLibraryBinding

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var filePickerLauncher: ActivityResultLauncher<Intent>
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
    private var sortOrder = SortOrder.NEWEST
    private var kindFilter = KindFilter.ALL
    private var grouping = Grouping.NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thumbs = ThumbnailLoader(requireContext().applicationContext)

        val saved = viewPrefs()
        sortOrder = enumPref(saved, PREF_SORT, SortOrder.NEWEST)
        kindFilter = enumPref(saved, PREF_FILTER, KindFilter.ALL)
        grouping = enumPref(saved, PREF_GROUP, Grouping.NONE)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { refresh(askForAccess = false) }

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
        binding = FragmentTvLibraryBinding.bind(view)
        Utils.handleInsetsAsPadding(binding.root)

        ViewCompat.setAccessibilityHeading(binding.heading, true)
        binding.heading.setText(kindFilter.title)

        val metrics = resources.displayMetrics
        val widthDp = metrics.widthPixels / metrics.density
        val spanCount = max(2, ((widthDp - 96f) / TILE_WIDTH_DP).toInt())
        val layout = GridLayoutManager(requireContext(), spanCount)
        // section titles take a whole row, tiles take one cell
        layout.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) =
                if (adapter.isHeader(position)) spanCount else ITEM_SPAN
        }
        binding.grid.layoutManager = layout
        binding.grid.adapter = adapter
        binding.grid.setHasFixedSize(true)

        // With nothing in the grid there is nothing else to focus, so this is the way to the menu.
        binding.emptyText.setOnClickListener { showMenu() }
        binding.emptyText.setOnLongClickListener { showMenu(); true }

        refresh(askForAccess = true)
    }

    override fun onResume() {
        super.onResume()
        // pick up files added while we were away, without asking for permission again
        if (resumedOnce)
            refresh(askForAccess = false)
        resumedOnce = true
    }

    override fun onDestroy() {
        super.onDestroy()
        queryExecutor.shutdownNow()
        thumbs.shutdown()
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

    private fun refresh(askForAccess: Boolean) {
        if (!hasAccess()) {
            allEntries = emptyList()
            adapter.submit(emptyList())
            showEmpty(R.string.tv_library_no_access)
            if (askForAccess)
                requestAccess()
            return
        }
        val generation = ++loadGeneration
        val appContext = requireContext().applicationContext
        try {
            queryExecutor.execute {
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
        val changed = adapter.submit(rows)
        when {
            allEntries.isEmpty() -> showEmpty(R.string.tv_library_empty)
            rows.isEmpty() -> showEmpty(R.string.tv_library_no_match)
            changed || forceFocus || !binding.grid.isVisible -> showGrid(forceFocus)
        }
    }

    private fun showGrid(forceFocus: Boolean) {
        val hadFocus = binding.emptyText.hasFocus()
        binding.emptyText.isVisible = false
        binding.grid.isVisible = true
        // The tile that had focus may be gone or elsewhere now, so start again at the top.
        if (forceFocus)
            binding.grid.scrollToPosition(0)
        // A TV has no pointer, so the remote needs a first tile in focus.
        if (forceFocus || hadFocus || binding.root.findFocus() == null) {
            binding.grid.doOnNextLayout {
                val first = adapter.firstItemPosition()
                if (first != RecyclerView.NO_POSITION)
                    binding.grid.findViewHolderForAdapterPosition(first)?.itemView?.requestFocus()
            }
        }
    }

    private fun showEmpty(messageRes: Int) {
        binding.grid.isVisible = false
        binding.emptyText.setText(messageRes)
        binding.emptyText.isVisible = true
        if (binding.root.findFocus() == null)
            binding.emptyText.requestFocus()
    }

    // ---- menu ----

    /** Extra options that used to be buttons. Called from MainActivity on the Menu key. */
    fun showMenu() {
        val ctx = context ?: return
        val entries = listOf<Pair<String, () -> Unit>>(
            getString(if (hasAccess()) R.string.tv_menu_refresh else R.string.tv_menu_allow_access) to
                { refresh(askForAccess = true) },
            menuValue(R.string.tv_menu_sort, sortOrder.label) to { chooseSort() },
            menuValue(R.string.tv_menu_filter, kindFilter.label) to { chooseFilter() },
            menuValue(R.string.tv_menu_group, grouping.label) to { chooseGrouping() },
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
            .apply()
        binding.heading.setText(kindFilter.title)
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
        val shown = all
            .filter { kindFilter.accepts(it) }
            .sortedWith(comparatorFor(sortOrder))
        val groups: List<Pair<String, List<MediaEntry>>> = when (grouping) {
            Grouping.NONE -> return shown.map { Row.Item(it) }
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
            .flatMap { (label, entries) -> listOf<Row>(Row.Header(label)) + entries.map { Row.Item(it) } }
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

    private fun playFile(filepath: String) {
        val i: Intent
        if (filepath.startsWith("content://")) {
            i = Intent(Intent.ACTION_VIEW, Uri.parse(filepath))
        } else {
            i = Intent()
            // if we pass 'filepath' it will go directly to mpv without any processing
            i.putExtra("filepath", filepath)
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
    )

    private fun queryAll(ctx: Context): List<MediaEntry> {
        val out = ArrayList<MediaEntry>()
        queryCollection(ctx, false, out)
        queryCollection(ctx, true, out)
        return out
    }

    // DATA is only read for its folder, to group by it. Entries without it land in "unknown folder".
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
                    out.add(MediaEntry(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        title = name.substringBeforeLast('.', name),
                        durationMs = c.getLong(durCol),
                        isAudio = audio,
                        dateAdded = c.getLong(addedCol),
                        folder = if (dataCol >= 0)
                            (c.getString(dataCol) ?: "").substringBeforeLast('/', "")
                        else
                            "",
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

        /** Position of the first tile (section titles cannot take focus), or NO_POSITION. */
        fun firstItemPosition() = rows.indexOfFirst { it is Row.Item }

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
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).text.text = row.label
                is Row.Item -> bindItem((holder as ItemHolder).b, row.entry)
            }
        }

        private fun bindItem(b: ItemTvMediaBinding, entry: MediaEntry) {
            val ctx = b.root.context

            b.title.text = entry.title
            val kind = ctx.getString(if (entry.isAudio) R.string.tv_kind_audio else R.string.tv_kind_video)
            b.meta.text = if (entry.durationMs > 0)
                ctx.getString(R.string.tv_meta_format, kind, formatDuration(entry.durationMs))
            else
                kind

            b.root.setOnClickListener { playFile(entry.uri.toString()) }
            b.root.setOnLongClickListener { showMenu(); true }

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
        private const val VIEW_TYPE_ITEM = 0
        private const val VIEW_TYPE_HEADER = 1
        private const val HEADER_ID_BASE = -1L

        private const val VIEW_PREFS = "tv_library_view"
        private const val PREF_SORT = "sort"
        private const val PREF_FILTER = "filter"
        private const val PREF_GROUP = "group"

        // file paths from MediaStore.DATA, shortened on section titles
        private const val STORAGE_ROOT = "/storage"
        private const val INTERNAL_STORAGE_ROOT = "/storage/emulated/0"

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
