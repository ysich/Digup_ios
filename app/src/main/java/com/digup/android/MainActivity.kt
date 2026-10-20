package com.digup.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.digup.android.ui.DigUpRoot
import com.digup.android.ui.theme.DigUpTheme

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DigUpTheme {
                val state by model.state.collectAsStateWithLifecycle()
                DigUpRoot(state = state, model = model)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        model.refreshPermissions()
    }
}
