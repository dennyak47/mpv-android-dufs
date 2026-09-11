package `is`.xyz.mpv

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.Future

class DufsBrowserActivity : AppCompatActivity() {
    private lateinit var thumbnailManager: ThumbnailManager
    private val executor = Executors.newSingleThreadExecutor()
    private var loadTask: Future<*>? = null
    private val servers = mutableListOf<String>()
    private var lastPlayed: LastPlayed? = null
    private var uiState by mutableStateOf(BrowserUiState())

    private var rootUrl: String? = null
    private var directoryUrl: String? = null
    private var parentUrls = emptyList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        enableEdgeToEdge()
        thumbnailManager = ThumbnailManager(applicationContext)

        loadServers()
        rootUrl = savedInstanceState?.getString(STATE_ROOT_URL)
        directoryUrl = savedInstanceState?.getString(STATE_DIRECTORY_URL)
        parentUrls = savedInstanceState?.getStringArrayList(STATE_PARENT_URLS) ?: emptyList()

        setContent {
            DufsBrowserTheme {
                DufsBrowserScreen(
                    state = uiState,
                    thumbnailManager = thumbnailManager,
                    onBack = ::navigateBack,
                    onAddServer = ::addServer,
                    onRemoveServer = ::removeServer,
                    onRowClick = ::onRowClicked,
                    onRetry = ::retryDirectory,
                    onShowServers = ::showServerList,
                    onRefresh = ::refreshDirectory,
                )
            }
        }

        onBackPressedDispatcher.addCallback(this) { navigateBack() }

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

    private fun showServerList() {
        loadTask?.cancel(true)
        rootUrl = null
        directoryUrl = null
        parentUrls = emptyList()
        val recentRow = lastPlayed?.let {
            BrowserRow(
                getString(R.string.dufs_continue_playing, it.label),
                it.fileUrl,
                isDirectory = false,
                isServer = false,
                size = it.size,
                modifiedTime = it.modifiedTime,
                isVideo = it.isVideo,
                isRecent = true,
            )
        }
        uiState = BrowserUiState(
            titleRes = R.string.dufs_servers_title,
            rows = listOfNotNull(recentRow) + servers.map {
                BrowserRow(it, it, isDirectory = false, isServer = true)
            },
            emptyMessageRes = R.string.dufs_no_servers,
        )
    }

    private fun addServer(input: String) {
        val url = DufsClient.normalizeServerUrl(input)
        if (!servers.contains(url)) {
            servers.add(url)
            saveServers()
        }
        loadDirectory(url, url, emptyList())
    }

    private fun removeServer(url: String) {
        servers.remove(url)
        if (lastPlayed?.rootUrl == url) {
            lastPlayed = null
            saveLastPlayed()
        }
        saveServers()
        showServerList()
    }

    private fun onRowClicked(row: BrowserRow) {
        when {
            row.isRecent -> resumeLastPlayed()
            row.isServer -> loadDirectory(row.url, row.url, emptyList())
            row.isDirectory -> {
                val root = rootUrl ?: return
                val current = directoryUrl ?: return
                loadDirectory(root, row.url, parentUrls + current)
            }
            else -> {
                if (row.isVideo)
                    rememberLastPlayed(row)
                playFile(row.url)
            }
        }
    }

    private fun loadDirectory(
        root: String,
        directory: String,
        parents: List<String>,
        onLoaded: (() -> Unit)? = null,
    ) {
        loadTask?.cancel(true)
        rootUrl = root
        directoryUrl = directory
        parentUrls = parents
        uiState = BrowserUiState(
            titleRes = R.string.action_open_dufs,
            path = directory,
            isLoading = true,
            emptyMessageRes = R.string.dufs_empty_folder,
        )

        loadTask = executor.submit {
            try {
                val entries = DufsClient.listDirectory(directory)
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed || directoryUrl != directory)
                        return@post
                    uiState = BrowserUiState(
                        titleRes = R.string.action_open_dufs,
                        path = directory,
                        rows = entries.map {
                            BrowserRow(
                                it.name,
                                it.url,
                                it.isDirectory,
                                isServer = false,
                                it.size,
                                it.modifiedTime,
                                it.isVideo,
                            )
                        },
                        emptyMessageRes = R.string.dufs_empty_folder,
                    )
                    onLoaded?.invoke()
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                if (Thread.currentThread().isInterrupted)
                    return@submit
                Log.e(TAG, "Failed to load Dufs directory", e)
                Handler(Looper.getMainLooper()).post {
                    if (isFinishing || isDestroyed || directoryUrl != directory)
                        return@post
                    uiState = BrowserUiState(
                        titleRes = R.string.action_open_dufs,
                        path = directory,
                        emptyMessageRes = R.string.dufs_empty_folder,
                        errorMessage = e.message ?: getString(R.string.dufs_error_unknown),
                    )
                }
            }
        }
    }

    private fun retryDirectory() {
        val root = rootUrl ?: return
        val directory = directoryUrl ?: return
        loadDirectory(root, directory, parentUrls)
    }

    private fun refreshDirectory() {
        if (directoryUrl != null)
            retryDirectory()
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

    private fun rememberLastPlayed(row: BrowserRow) {
        val root = rootUrl ?: return
        val directory = directoryUrl ?: return
        lastPlayed = LastPlayed(
            label = row.label,
            fileUrl = row.url,
            rootUrl = root,
            directoryUrl = directory,
            parentUrls = parentUrls,
            size = row.size,
            modifiedTime = row.modifiedTime,
            isVideo = row.isVideo,
        )
        saveLastPlayed()
    }

    private fun resumeLastPlayed() {
        val recent = lastPlayed ?: return
        loadDirectory(recent.rootUrl, recent.directoryUrl, recent.parentUrls) {
            playFile(recent.fileUrl)
        }
    }

    private fun loadServers() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.getString(PREF_SERVERS, null)?.let { serialized ->
            try {
                val values = JSONArray(serialized)
                for (index in 0 until values.length())
                    servers.add(values.getString(index))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read saved Dufs servers", e)
            }
        }

        prefs.getString(PREF_PREVIOUS_URL, null)?.let { previousUrl ->
            if (!servers.contains(previousUrl))
                servers.add(previousUrl)
            prefs.edit().remove(PREF_PREVIOUS_URL).apply()
            saveServers()
        }

        prefs.getString(PREF_LAST_PLAYED, null)?.let { serialized ->
            try {
                val value = JSONObject(serialized)
                lastPlayed = LastPlayed(
                    label = value.getString("label"),
                    fileUrl = value.getString("fileUrl"),
                    rootUrl = value.getString("rootUrl"),
                    directoryUrl = value.getString("directoryUrl"),
                    parentUrls = value.getJSONArray("parentUrls").let { parents ->
                        List(parents.length()) { parents.getString(it) }
                    },
                    size = value.optLong("size", -1L),
                    modifiedTime = value.optLong("modifiedTime", -1L),
                    isVideo = value.optBoolean("isVideo", true),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read last played Dufs file", e)
                prefs.edit().remove(PREF_LAST_PLAYED).apply()
            }
        }
    }

    private fun saveServers() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putString(PREF_SERVERS, JSONArray(servers).toString())
            .apply()
    }

    private fun saveLastPlayed() {
        val editor = PreferenceManager.getDefaultSharedPreferences(this).edit()
        val recent = lastPlayed
        if (recent == null) {
            editor.remove(PREF_LAST_PLAYED)
        } else {
            editor.putString(
                PREF_LAST_PLAYED,
                JSONObject()
                    .put("label", recent.label)
                    .put("fileUrl", recent.fileUrl)
                    .put("rootUrl", recent.rootUrl)
                    .put("directoryUrl", recent.directoryUrl)
                    .put("parentUrls", JSONArray(recent.parentUrls))
                    .put("size", recent.size)
                    .put("modifiedTime", recent.modifiedTime)
                    .put("isVideo", recent.isVideo)
                    .toString(),
            )
        }
        editor.apply()
    }

    override fun onDestroy() {
        loadTask?.cancel(true)
        executor.shutdownNow()
        if (this::thumbnailManager.isInitialized)
            thumbnailManager.close()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "mpv"
        private const val PREF_SERVERS = "DufsBrowserActivity_servers"
        private const val PREF_PREVIOUS_URL = "MainScreenFragment_dufs_url"
        private const val PREF_LAST_PLAYED = "DufsBrowserActivity_last_played"
        private const val STATE_ROOT_URL = "root_url"
        private const val STATE_DIRECTORY_URL = "directory_url"
        private const val STATE_PARENT_URLS = "parent_urls"
    }
}

private data class BrowserUiState(
    val titleRes: Int = R.string.dufs_servers_title,
    val path: String? = null,
    val rows: List<BrowserRow> = emptyList(),
    val isLoading: Boolean = false,
    val emptyMessageRes: Int = R.string.dufs_no_servers,
    val errorMessage: String? = null,
)

private data class BrowserRow(
    val label: String,
    val url: String,
    val isDirectory: Boolean,
    val isServer: Boolean,
    val size: Long = -1L,
    val modifiedTime: Long = -1L,
    val isVideo: Boolean = false,
    val isRecent: Boolean = false,
)

private data class LastPlayed(
    val label: String,
    val fileUrl: String,
    val rootUrl: String,
    val directoryUrl: String,
    val parentUrls: List<String>,
    val size: Long,
    val modifiedTime: Long,
    val isVideo: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DufsBrowserScreen(
    state: BrowserUiState,
    thumbnailManager: ThumbnailManager,
    onBack: () -> Unit,
    onAddServer: (String) -> Unit,
    onRemoveServer: (String) -> Unit,
    onRowClick: (BrowserRow) -> Unit,
    onRetry: () -> Unit,
    onShowServers: () -> Unit,
    onRefresh: () -> Unit,
) {
    var showAddServer by remember { mutableStateOf(false) }
    var serverToRemove by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val path = state.path
                    if (path == null) {
                        Text(
                            text = stringResource(state.titleRes),
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        val directoryPath = serverPath(path)
                        Column {
                            Text(
                                text = serverName(path),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (directoryPath != "/") {
                                Text(
                                    text = directoryPath,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.dufs_back),
                        )
                    }
                },
                actions = {
                    AnimatedVisibility(visible = state.path != null) {
                        IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.dufs_refresh),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = state.path == null) {
                ExtendedFloatingActionButton(
                    onClick = { showAddServer = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.dufs_add_server)) },
                )
            }
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 920.dp)
                    .fillMaxWidth(),
            ) {
                AnimatedContent(
                    targetState = when {
                        state.isLoading -> ContentState.Loading
                        state.errorMessage != null -> ContentState.Error
                        state.rows.isEmpty() -> ContentState.Empty
                        else -> ContentState.Rows
                    },
                    label = "Dufs content",
                    modifier = Modifier.weight(1f),
                ) { content ->
                    when (content) {
                        ContentState.Loading -> LoadingState()
                        ContentState.Error -> ErrorState(
                            message = state.errorMessage.orEmpty(),
                            onRetry = onRetry,
                            onShowServers = onShowServers,
                        )
                        ContentState.Empty -> EmptyState(
                            message = stringResource(state.emptyMessageRes),
                            showAddAction = state.path == null,
                            onAddServer = { showAddServer = true },
                        )
                        ContentState.Rows -> BrowserRows(
                            rows = state.rows,
                            thumbnailManager = thumbnailManager,
                            onRowClick = onRowClick,
                            onRemoveServer = { serverToRemove = it },
                        )
                    }
                }
            }
        }
    }

    if (showAddServer) {
        AddServerDialog(
            onDismiss = { showAddServer = false },
            onAdd = {
                showAddServer = false
                onAddServer(it)
            },
        )
    }

    serverToRemove?.let { url ->
        AlertDialog(
            onDismissRequest = { serverToRemove = null },
            icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.dufs_remove_server)) },
            text = { Text(url) },
            confirmButton = {
                Button(
                    onClick = {
                        serverToRemove = null
                        onRemoveServer(url)
                    },
                ) {
                    Text(stringResource(R.string.dufs_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToRemove = null }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun BrowserRows(
    rows: List<BrowserRow>,
    thumbnailManager: ThumbnailManager,
    onRowClick: (BrowserRow) -> Unit,
    onRemoveServer: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(rows, key = { "${it.isRecent}:${it.isServer}:${it.url}" }) { row ->
            BrowserRowCard(
                row = row,
                thumbnailManager = thumbnailManager,
                onClick = { onRowClick(row) },
                onRemove = { onRemoveServer(row.url) },
            )
        }
    }
}

@Composable
private fun BrowserRowCard(
    row: BrowserRow,
    thumbnailManager: ThumbnailManager,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = if (row.isRecent) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = colors,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserArtwork(row, thumbnailManager)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (row.isServer) serverName(row.url) else row.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val supportingText = rowSupportingText(row)
                if (supportingText.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row.isServer) {
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(onClick = onRemove) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.dufs_remove),
                    )
                }
            }
        }
    }
}

@Composable
private fun BrowserArtwork(row: BrowserRow, thumbnailManager: ThumbnailManager) {
    var thumbnail by remember(row.url, row.size, row.modifiedTime) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    if (row.isVideo && !row.isRecent) {
        DisposableEffect(row.url, row.size, row.modifiedTime) {
            val request = thumbnailManager.load(row.url, row.size, row.modifiedTime) {
                thumbnail = it?.asImageBitmap()
            }
            onDispose { request.cancel() }
        }
    }

    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .size(width = 96.dp, height = 60.dp)
            .clip(shape)
            .background(
                if (row.isRecent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        val image = thumbnail
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            val icon = when {
                row.isRecent -> R.drawable.ic_play_arrow_black_24dp
                row.isServer -> R.drawable.ic_dufs_48dp
                row.isDirectory -> R.drawable.ic_folder_white_48dp
                else -> R.drawable.ic_file_open_48dp
            }
            Image(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(34.dp),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun rowSupportingText(row: BrowserRow): String {
    if (row.isServer)
        return row.url
    if (row.isRecent)
        return stringResource(R.string.dufs_recent_hint)
    if (row.isDirectory)
        return stringResource(R.string.dufs_folder)

    val context = LocalContext.current
    val details = buildList {
        if (row.size >= 0)
            add(Formatter.formatShortFileSize(context, row.size))
        if (row.modifiedTime > 0) {
            add(
                DateUtils.getRelativeDateTimeString(
                    context,
                    row.modifiedTime,
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.WEEK_IN_MILLIS,
                    0,
                ).toString()
            )
        }
    }
    return details.joinToString(" • ")
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.dufs_loading),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(
    message: String,
    showAddAction: Boolean,
    onAddServer: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Rounded.Storage,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showAddAction) {
                Spacer(Modifier.height(18.dp))
                Button(onClick = onAddServer) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.dufs_add_server))
                }
            }
        }
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    onShowServers: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedCard(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.dufs_error_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.dufs_retry))
                    }
                    TextButton(onClick = onShowServers) {
                        Text(stringResource(R.string.dufs_servers_title))
                    }
                }
            }
        }
    }
}

@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val isValid = remember(value) {
        runCatching { DufsClient.normalizeServerUrl(value) }.isSuccess
    }
    val submit = {
        if (isValid)
            onAdd(value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Storage, contentDescription = null) },
        title = { Text(stringResource(R.string.dufs_add_server)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dufs_add_server_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.dufs_server_url)) },
                    placeholder = { Text("https://media.example.com") },
                    singleLine = true,
                    isError = value.isNotBlank() && !isValid,
                    supportingText = if (value.isNotBlank() && !isValid) {
                        { Text(stringResource(R.string.uri_invalid_protocol)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
        },
        confirmButton = {
            Button(onClick = submit, enabled = isValid) {
                Text(stringResource(R.string.dufs_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

private fun serverName(url: String): String {
    val uri = Uri.parse(url)
    return uri.host?.let { host ->
        if (uri.port != -1) "$host:${uri.port}" else host
    } ?: url
}

private fun serverPath(url: String): String =
    Uri.parse(url).encodedPath?.let(Uri::decode).orEmpty().ifBlank { "/" }

private enum class ContentState {
    Loading,
    Error,
    Empty,
    Rows,
}

@Composable
private fun DufsBrowserTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(
            primary = Color(0xFFD8A9DB),
            secondary = Color(0xFFD5BBD5),
            tertiary = Color(0xFFF0B8B3),
        )
        else -> lightColorScheme(
            primary = Color(0xFF7B3F7E),
            secondary = Color(0xFF705B70),
            tertiary = Color(0xFF8D4F4A),
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
