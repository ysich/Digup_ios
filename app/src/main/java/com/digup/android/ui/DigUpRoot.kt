package com.digup.android.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.digup.android.MainViewModel
import com.digup.android.UiState

@Composable
fun DigUpRoot(state: UiState, model: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(model::addFolder)
    }
    val photoPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        model.onPhotoPermissionResult(result.values.any { it })
    }
    val pickFolder = { folderPicker.launch(null) }
    val includePhotos = {
        if (state.photoPermission) model.setPhotos(true)
        else photoPermissions.launch(model.requestedPhotoPermissions())
    }
    val togglePhotos: (Boolean) -> Unit = { on ->
        if (!on) model.setPhotos(false)
        else if (state.photoPermission) model.setPhotos(true)
        else photoPermissions.launch(model.requestedPhotoPermissions())
    }

    LaunchedEffect(state.error) {
        state.error?.let { message ->
            snackbar.showSnackbar(message)
            model.dismissError()
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbar.showSnackbar(message)
            model.dismissMessage()
        }
    }

    BackHandler(enabled = state.preview != null) { model.closePreview() }
    BackHandler(enabled = state.preview == null && state.settingsOpen) { model.closeSettings() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            if (state.showOnboarding) {
                OnboardingScreen(
                    state = state,
                    onDownloadModel = model::downloadModel,
                    onIncludePhotos = includePhotos,
                    onStopPhotos = { model.setPhotos(false) },
                    onPickFolder = pickFolder,
                    onStartIndex = { model.startIndexing() },
                )
            } else {
                SearchScreen(
                    state = state,
                    onQueryChange = model::onQueryChange,
                    onPickFolder = pickFolder,
                    onOpenSettings = model::openSettings,
                    onOpenPreview = model::openPreview,
                )
            }

            if (state.settingsOpen) {
                SettingsScreen(
                    state = state,
                    onClose = model::closeSettings,
                    onPickFolder = pickFolder,
                    onRemoveFolder = model::removeFolder,
                    onPhotosToggle = togglePhotos,
                    onUpdateIndex = { model.startIndexing(retryFailed = true) },
                    onRebuildIndex = model::rebuildIndex,
                    onClearIndex = model::clearIndex,
                    onFreeSearchModel = model::freeSearchModel,
                )
            }

            state.preview?.let { hit ->
                PreviewScreen(
                    hit = hit,
                    onClose = model::closePreview,
                    segmentText = model::segmentText,
                )
            }
        }
    }
}
