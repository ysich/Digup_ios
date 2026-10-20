package com.digup.android

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.digup.android.data.Counts
import com.digup.android.data.IndexProgress
import com.digup.android.data.ModelDownloader
import com.digup.android.data.ModelFiles
import com.digup.android.data.SearchHit
import com.digup.android.data.formatBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SourceRoot(val uri: Uri, val label: String)

data class UiState(
    val modelReady: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val sources: List<SourceRoot> = emptyList(),
    val photosEnabled: Boolean = false,
    val photoPermission: Boolean = false,
    val isIndexing: Boolean = false,
    val progress: IndexProgress? = null,
    val counts: Counts = Counts(),
    val indexBytes: Long = 0L,
    val query: String = "",
    val results: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val settingsOpen: Boolean = false,
    val preview: SearchHit? = null,
    val indexSummary: String? = null,
    val indexIssue: String? = null,
    val failures: List<String> = emptyList(),
) {
    val hasSource: Boolean get() = sources.isNotEmpty() || photosEnabled
    val showOnboarding: Boolean get() = !(modelReady && hasSource)
    val indexSizeText: String get() = if (indexBytes <= 0) "No index yet" else formatBytes(indexBytes)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, 0)
    private val engine = DigUpEngine(app)
    private var indexJob: Job? = null
    private var searchJob: Job? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val granted = photoPermissionGranted()
        val sources = restoredSources()
        val photos = prefs.getBoolean(KEY_PHOTOS, false) && granted
        if (prefs.getBoolean(KEY_PHOTOS, false) && !granted) prefs.edit().putBoolean(KEY_PHOTOS, false).apply()
        _state.value = UiState(
            modelReady = ModelFiles.available(app) != null,
            sources = sources,
            photosEnabled = photos,
            photoPermission = granted,
            indexBytes = engine.indexBytes(),
        )
        viewModelScope.launch {
            val counts = engine.counts()
            val failures = engine.failures()
            _state.update {
                it.copy(counts = counts, indexBytes = engine.indexBytes(), failures = failures)
            }
            val snapshot = _state.value
            if (snapshot.modelReady && snapshot.hasSource) startIndexing()
        }
    }

    fun refreshPermissions() {
        val granted = photoPermissionGranted()
        val available = contentResolverPersisted()
        val sources = _state.value.sources.filter { source ->
            available.any { it == source.uri }
        }
        val photos = _state.value.photosEnabled && granted
        if (sources.size != _state.value.sources.size) saveSources(sources)
        if (!photos && _state.value.photosEnabled) prefs.edit().putBoolean(KEY_PHOTOS, false).apply()
        _state.update { it.copy(sources = sources, photosEnabled = photos, photoPermission = granted) }
    }

    fun requestedPhotoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun onPhotoPermissionResult(granted: Boolean) {
        _state.update { it.copy(photoPermission = granted) }
        if (!granted) {
            _state.update { it.copy(message = "DigUp can read photos only with permission. You can allow it in Android Settings › Apps › DigUp › Permissions.") }
            return
        }
        setPhotos(true)
    }

    fun setPhotos(on: Boolean) {
        if (on && !photoPermissionGranted()) {
            _state.update { it.copy(photoPermission = false) }
            return
        }
        if (_state.value.photosEnabled == on) return
        prefs.edit().putBoolean(KEY_PHOTOS, on).apply()
        _state.update { it.copy(photosEnabled = on, results = if (on) it.results else emptyList()) }
        afterSourceChange(clearWhenEmpty = true)
    }

    fun addFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }
        viewModelScope.launch {
            val label = runCatching { DocumentFile.fromTreeUri(app, uri)?.name }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: Uri.decode(uri.lastPathSegment ?: "Folder")
            val source = SourceRoot(uri, label)
            val sources = _state.value.sources.filterNot { it.uri == uri } + source
            saveSources(sources)
            _state.update { it.copy(sources = sources) }
            startIndexing()
        }
    }

    fun removeFolder(uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val sources = _state.value.sources.filterNot { it.uri == uri }
        saveSources(sources)
        _state.update { it.copy(sources = sources, results = emptyList()) }
        afterSourceChange(clearWhenEmpty = true)
    }

    fun downloadModel() {
        val current = _state.value
        if (current.downloading || current.modelReady) return
        viewModelScope.launch {
            _state.update { it.copy(downloading = true, downloadProgress = 0f, error = null) }
            try {
                ModelDownloader(app).download { progress ->
                    _state.update { it.copy(downloadProgress = progress.toFloat()) }
                }
                val ready = ModelFiles.available(app) != null
                _state.update {
                    it.copy(
                        downloading = false,
                        modelReady = ready,
                        downloadProgress = if (ready) 1f else it.downloadProgress,
                        message = if (ready) "The model is ready" else null,
                    )
                }
                if (ready && _state.value.hasSource) startIndexing()
            } catch (error: Throwable) {
                _state.update { it.copy(downloading = false, error = error.friendly()) }
            }
        }
    }

    fun startIndexing(rebuild: Boolean = false, retryFailed: Boolean = false) {
        val current = _state.value
        if (!current.modelReady) {
            _state.update { it.copy(error = "Download the model before indexing") }
            return
        }
        if (!current.hasSource) return
        indexJob?.cancel()
        engine.cancelSearches()
        indexJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isIndexing = true,
                    progress = IndexProgress(if (rebuild) "Rebuilding" else "Scanning", 0, 0, null),
                    error = null,
                    indexSummary = null,
                )
            }
            try {
                val outcome = engine.index(
                    trees = _state.value.sources.map { it.uri },
                    includePhotos = _state.value.photosEnabled,
                    rebuild = rebuild,
                    retryFailed = retryFailed,
                ) { progress -> _state.update { it.copy(progress = progress) } }
                val failures = engine.failures()
                _state.update {
                    it.copy(
                        isIndexing = false,
                        progress = null,
                        counts = outcome.counts,
                        indexBytes = outcome.bytes,
                        indexSummary = outcome.summary,
                        indexIssue = if (outcome.counts.vectors == 0) {
                            app.getString(R.string.index_no_vectors)
                        } else null,
                        failures = failures,
                    )
                }
                val query = _state.value.query
                if (query.isNotBlank()) runSearch(query)
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isIndexing = false, progress = null) }
                throw cancelled
            } catch (error: Throwable) {
                Log.e("DigUpIndex", "indexing failed", error)
                val failures = engine.failures()
                _state.update {
                    it.copy(
                        isIndexing = false,
                        progress = null,
                        error = error.friendly(),
                        indexIssue = error.friendly(),
                        failures = failures,
                    )
                }
            }
        }
    }

    fun clearIndex() {
        indexJob?.cancel()
        engine.cancelSearches()
        indexJob = viewModelScope.launch {
            _state.update { it.copy(isIndexing = true, progress = IndexProgress("Clearing", 0, 0, null)) }
            try {
                engine.resetIndex()
                _state.update {
                    it.copy(
                        isIndexing = false,
                        progress = null,
                        counts = Counts(),
                        indexBytes = 0L,
                        results = emptyList(),
                        indexSummary = null,
                    )
                }
            } catch (error: Throwable) {
                _state.update { it.copy(isIndexing = false, progress = null, error = error.friendly()) }
            }
        }
    }

    fun rebuildIndex() {
        if (_state.value.hasSource) startIndexing(rebuild = true) else clearIndex()
    }

    fun onQueryChange(text: String) {
        _state.update { it.copy(query = text) }
        searchJob?.cancel()
        engine.cancelSearches()
        if (text.isBlank()) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            runSearch(text)
        }
    }

    private suspend fun runSearch(text: String) {
        val requested = text
        _state.update { it.copy(searching = true) }
        try {
            val hits = engine.search(requested)
            if (_state.value.query == requested) {
                _state.update { it.copy(results = hits, searching = false) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (_state.value.query == requested) {
                _state.update { it.copy(searching = false, error = error.friendly()) }
            }
        }
    }

    fun openSettings() = _state.update { it.copy(settingsOpen = true) }
    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }
    fun openPreview(hit: SearchHit) = _state.update { it.copy(preview = hit) }
    fun closePreview() = _state.update { it.copy(preview = null) }
    fun dismissError() = _state.update { it.copy(error = null) }
    fun dismissMessage() = _state.update { it.copy(message = null) }
    fun freeSearchModel() = engine.closeSearchModel()

    suspend fun segmentText(id: Long): String? = engine.segmentText(id)

    private fun afterSourceChange(clearWhenEmpty: Boolean) {
        viewModelScope.launch {
            if (!_state.value.hasSource && clearWhenEmpty) {
                engine.resetIndex()
                _state.update {
                    it.copy(counts = Counts(), indexBytes = 0L, results = emptyList())
                }
            } else {
                startIndexing()
            }
        }
    }

    private fun photoPermissionGranted(): Boolean {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(app, permission) ==
            PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= 34 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                    granted(Manifest.permission.READ_MEDIA_VIDEO) ||
                    granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 ->
                granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun contentResolverPersisted(): List<Uri> =
        app.contentResolver.persistedUriPermissions.map { it.uri }

    private fun restoredSources(): List<SourceRoot> {
        val raw = prefs.getString(KEY_SOURCES, null) ?: return emptyList()
        val available = contentResolverPersisted()
        val out = ArrayList<SourceRoot>()
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val objectValue = array.optJSONObject(index) ?: continue
                val uri = Uri.parse(objectValue.optString("uri")) ?: continue
                if (available.any { it == uri }) {
                    out.add(SourceRoot(uri, objectValue.optString("label", "Folder")))
                }
            }
        }
        return out
    }

    private fun saveSources(sources: List<SourceRoot>) {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(JSONObject().put("uri", source.uri.toString()).put("label", source.label))
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    override fun onCleared() {
        indexJob?.cancel()
        searchJob?.cancel()
        engine.close()
        super.onCleared()
    }

    companion object {
        private const val PREFS = "digup"
        private const val KEY_SOURCES = "sources"
        private const val KEY_PHOTOS = "photosEnabled"
    }
}

fun Throwable.friendly(): String {
    val message = message?.takeIf { it.isNotBlank() } ?: toString()
    return message.replace(Regex("\\s+"), " ").take(400)
}
