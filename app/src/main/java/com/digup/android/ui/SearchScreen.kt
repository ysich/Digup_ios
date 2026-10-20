package com.digup.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.digup.android.R
import com.digup.android.UiState
import com.digup.android.data.SearchHit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    state: UiState,
    onQueryChange: (String) -> Unit,
    onPickFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPreview: (SearchHit) -> Unit,
) {
    val focus = LocalFocusManager.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.search_title), fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onPickFolder) {
                        Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.add_folder))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.settings))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.close))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            )

            state.progress?.let { progress ->
                if (state.isIndexing) {
                    IndexBanner(
                        progress = progress,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
            if (state.searching) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                )
            }
            if (state.isIndexing && state.query.isNotBlank()) {
                Text(
                    text = stringResource(R.string.search_while_indexing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                )
            }
            if (!state.isIndexing && state.query.isNotBlank() &&
                state.counts.files > 0 && state.counts.vectors == 0
            ) {
                Text(
                    text = stringResource(R.string.search_without_vectors),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 5.dp),
                )
            }

            if (state.results.isEmpty()) {
                val searching = state.query.isNotBlank()
                EmptyState(
                    title = stringResource(if (searching) R.string.no_matches_title else R.string.empty_search_title),
                    body = stringResource(if (searching) R.string.no_matches_body else R.string.empty_search_body),
                    icon = Icons.Outlined.Search,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.results, key = { it.segmentId }) { hit ->
                        ResultRow(hit = hit, onClick = { onOpenPreview(hit) })
                    }
                }
            }
        }
    }
}
