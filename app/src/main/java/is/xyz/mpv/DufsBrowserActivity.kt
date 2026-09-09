package `is`.xyz.mpv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import `is`.xyz.mpv.databinding.ActivityDufsBrowserBinding
import `is`.xyz.mpv.databinding.DufsBrowserItemBinding
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.Future

class DufsBrowserActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDufsBrowserBinding
    private lateinit var thumbnailManager: ThumbnailManager
    private lateinit var adapter: BrowserAdapter
    private val executor = Executors.newSingleThreadExecutor()
    private var loadTask: Future<*>? = null
    private val servers = mutableListOf<String>()

    private var rootUrl: String? = null
    private var directoryUrl: String? = null
    private var parentUrls = emptyList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDufsBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Utils.handleInsetsAsPadding(binding.root)
        thumbnailManager = ThumbnailManager(applicationContext)
        adapter = BrowserAdapter(thumbnailManager, ::onRowClicked, ::confirmRemoveServer)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ) = false

            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ): Int {
                return if (adapter.isServer(viewHolder.bindingAdapterPosition)) {
                    super.getSwipeDirs(recyclerView, viewHolder)
                } else {
                    0
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter.revealRemove(viewHolder.bindingAdapterPosition)
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.3f

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                val holder = viewHolder as? BrowserAdapter.Holder ?: return
                holder.setSwipeOffset(
                    if (adapter.isRevealed(viewHolder.bindingAdapterPosition)) {
                        holder.actionWidth
                    } else {
                        0f
                    }
                )
            }

            override fun onChildDraw(
                canvas: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean,
            ) {
                val holder = viewHolder as? BrowserAdapter.Holder ?: return
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE)
                    holder.setSwipeOffset(dX.coerceIn(0f, holder.actionWidth))
            }
        }).attachToRecyclerView(binding.list)
        binding.addServerBtn.setOnClickListener { showAddServerDialog() }
        binding.upBtn.setOnClickListener { navigateBack() }
        onBackPressedDispatcher.addCallback(this) { navigateBack() }

        loadServers()
        rootUrl = savedInstanceState?.getString(STATE_ROOT_URL)
        directoryUrl = savedInstanceState?.getString(STATE_DIRECTORY_URL)
        parentUrls = savedInstanceState?.getStringArrayList(STATE_PARENT_URLS) ?: emptyList()

        val restoredDirectory = directoryUrl
        val restoredRoot = rootUrl
        if (restoredDirectory != null && restoredRoot != null) {
            loadDirectory(restoredRoot, restoredDirectory, parentUrls)
        } else {
            showServerList()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ROOT_URL, rootUrl)
        outState.putString(STATE_DIRECTORY_URL, directoryUrl)
        outState.putStringArrayList(STATE_PARENT_URLS, ArrayList(parentUrls))
    }

    override fun onSupportNavigateUp(): Boolean {
        navigateBack()
        return true
    }

    private fun showServerList() {
        loadTask?.cancel(true)
        rootUrl = null
        directoryUrl = null
        parentUrls = emptyList()
        supportActionBar?.setTitle(R.string.dufs_servers_title)
        binding.path.isVisible = false
        binding.progress.isVisible = false
        binding.list.isVisible = servers.isNotEmpty()
        binding.empty.isVisible = servers.isEmpty()
        binding.empty.setText(R.string.dufs_no_servers)
        binding.addServerBtn.isVisible = true
        binding.upBtn.isVisible = false
        adapter.submit(servers.map { Row(it, it, false, true) })
    }

    private fun showAddServerDialog() {
        val helper = Utils.OpenUrlDialog(
            this,
            R.string.dufs_add_server,
            setOf("http", "https"),
        )
        with (helper) {
            builder.setPositiveButton(R.string.dialog_ok) { _, _ ->
                val url = DufsClient.normalizeServerUrl(helper.text)
                if (!servers.contains(url)) {
                    servers.add(url)
                    saveServers()
                }
                loadDirectory(url, url, emptyList())
            }
            builder.setNegativeButton(R.string.dialog_cancel) { dialog, _ -> dialog.cancel() }
            create().show()
        }
    }

    private fun confirmRemoveServer(url: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.dufs_remove_server)
            .setMessage(url)
            .setPositiveButton(R.string.dufs_remove) { _, _ ->
                servers.remove(url)
                saveServers()
                showServerList()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun onRowClicked(row: Row) {
        when {
            row.isServer -> loadDirectory(row.url, row.url, emptyList())
            row.isDirectory -> {
                val root = rootUrl ?: return
                val current = directoryUrl ?: return
                loadDirectory(root, row.url, parentUrls + current)
            }
            else -> playFile(row.url)
        }
    }

    private fun loadDirectory(root: String, directory: String, parents: List<String>) {
        loadTask?.cancel(true)
        rootUrl = root
        directoryUrl = directory
        parentUrls = parents
        supportActionBar?.setTitle(R.string.action_open_dufs)
        binding.path.text = Uri.decode(directory)
        binding.path.isVisible = true
        binding.progress.isVisible = true
        binding.list.isVisible = false
        binding.empty.isVisible = false
        binding.addServerBtn.isVisible = false
        binding.upBtn.isVisible = true

        loadTask = executor.submit {
            try {
                val entries = DufsClient.listDirectory(directory)
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed)
                        return@post
                    binding.progress.isVisible = false
                    binding.list.isVisible = entries.isNotEmpty()
                    binding.empty.isVisible = entries.isEmpty()
                    binding.empty.setText(R.string.dufs_empty_folder)
                    adapter.submit(entries.map {
                        Row(
                            it.name,
                            it.url,
                            it.isDirectory,
                            false,
                            it.size,
                            it.modifiedTime,
                            it.isVideo,
                        )
                    })
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted)
                    return@submit
                Log.e(TAG, "Failed to load Dufs directory", e)
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed)
                        return@post
                    binding.progress.isVisible = false
                    AlertDialog.Builder(this)
                        .setTitle(R.string.dufs_error_title)
                        .setMessage(e.message ?: getString(R.string.dufs_error_unknown))
                        .setPositiveButton(R.string.dufs_retry) { _, _ ->
                            loadDirectory(root, directory, parents)
                        }
                        .setNegativeButton(R.string.dufs_servers_title) { _, _ ->
                            showServerList()
                        }
                        .show()
                }
            }
        }
    }

    private fun navigateBack() {
        val currentRoot = rootUrl
        if (currentRoot == null) {
            finish()
        } else if (parentUrls.isNotEmpty()) {
            loadDirectory(currentRoot, parentUrls.last(), parentUrls.dropLast(1))
        } else {
            showServerList()
        }
    }

    private fun playFile(url: String) {
        startActivity(Intent(this, MPVActivity::class.java).putExtra("filepath", url))
    }

    private fun loadServers() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val serialized = prefs.getString(PREF_SERVERS, null)
        if (serialized != null) {
            try {
                val values = JSONArray(serialized)
                for (index in 0 until values.length())
                    servers.add(values.getString(index))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read saved Dufs servers", e)
            }
        }

        val previousUrl = prefs.getString(PREF_PREVIOUS_URL, null)
        if (previousUrl != null) {
            if (!servers.contains(previousUrl))
                servers.add(previousUrl)
            prefs.edit().remove(PREF_PREVIOUS_URL).apply()
            saveServers()
        }
    }

    private fun saveServers() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putString(PREF_SERVERS, JSONArray(servers).toString())
            .apply()
    }

    override fun onDestroy() {
        loadTask?.cancel(true)
        executor.shutdownNow()
        if (this::binding.isInitialized)
            binding.list.adapter = null
        if (this::thumbnailManager.isInitialized)
            thumbnailManager.close()
        super.onDestroy()
    }

    private data class Row(
        val label: String,
        val url: String,
        val isDirectory: Boolean,
        val isServer: Boolean,
        val size: Long = -1L,
        val modifiedTime: Long = -1L,
        val isVideo: Boolean = false,
    )

    private class BrowserAdapter(
        private val thumbnailManager: ThumbnailManager,
        private val onClick: (Row) -> Unit,
        private val onRemove: (String) -> Unit,
    ) : RecyclerView.Adapter<BrowserAdapter.Holder>() {
        private var rows = emptyList<Row>()
        private var revealedPosition = RecyclerView.NO_POSITION

        fun submit(newRows: List<Row>) {
            rows = newRows
            revealedPosition = RecyclerView.NO_POSITION
            notifyDataSetChanged()
        }

        fun isServer(position: Int): Boolean =
            position in rows.indices && rows[position].isServer

        fun isRevealed(position: Int): Boolean = position == revealedPosition

        fun revealRemove(position: Int) {
            if (!isServer(position))
                return
            val previous = revealedPosition
            revealedPosition = position
            if (previous != RecyclerView.NO_POSITION && previous != position)
                notifyItemChanged(previous)
            notifyItemChanged(position)
        }

        private fun hideRemove() {
            val previous = revealedPosition
            revealedPosition = RecyclerView.NO_POSITION
            if (previous != RecyclerView.NO_POSITION)
                notifyItemChanged(previous)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = DufsBrowserItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(rows[position], position == revealedPosition)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.recycle()
        }

        override fun getItemCount() = rows.size

        inner class Holder(
            private val binding: DufsBrowserItemBinding,
        ) : RecyclerView.ViewHolder(binding.root) {
            private var thumbnailRequest: ThumbnailManager.Request? = null
            private var boundUrl: String? = null

            val actionWidth: Float
                get() = 96 * binding.root.resources.displayMetrics.density

            fun setSwipeOffset(offset: Float) {
                binding.foreground.translationX = offset
            }

            fun bind(row: Row, removeRevealed: Boolean) {
                recycle()
                boundUrl = row.url
                binding.name.text = row.label
                binding.icon.setImageResource(when {
                    row.isServer -> R.drawable.ic_dufs_48dp
                    row.isDirectory -> R.drawable.ic_folder_white_48dp
                    else -> R.drawable.ic_file_open_48dp
                })
                binding.icon.isVisible = true
                binding.thumbnail.isVisible = false
                binding.thumbnail.setImageDrawable(null)
                if (row.isVideo) {
                    thumbnailRequest = thumbnailManager.load(
                        row.url,
                        row.size,
                        row.modifiedTime,
                    ) { bitmap ->
                        if (boundUrl != row.url || bitmap == null)
                            return@load
                        binding.thumbnail.setImageBitmap(bitmap)
                        binding.thumbnail.isVisible = true
                        binding.icon.isVisible = false
                    }
                }
                binding.removeBtn.isVisible = row.isServer
                setSwipeOffset(if (removeRevealed) actionWidth else 0f)
                binding.foreground.setOnClickListener {
                    if (removeRevealed) {
                        hideRemove()
                    } else {
                        onClick(row)
                    }
                }
                binding.removeBtn.setOnClickListener {
                    hideRemove()
                    onRemove(row.url)
                }
            }

            fun recycle() {
                thumbnailRequest?.cancel()
                thumbnailRequest = null
                boundUrl = null
                binding.thumbnail.setImageDrawable(null)
                binding.thumbnail.isVisible = false
            }
        }
    }

    companion object {
        private const val TAG = "mpv"
        private const val PREF_SERVERS = "DufsBrowserActivity_servers"
        private const val PREF_PREVIOUS_URL = "MainScreenFragment_dufs_url"
        private const val STATE_ROOT_URL = "root_url"
        private const val STATE_DIRECTORY_URL = "directory_url"
        private const val STATE_PARENT_URLS = "parent_urls"
    }
}
