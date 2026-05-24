package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.data.AppDatabase
import com.example.data.RadioRepository
import com.example.ui.BroadcastScreen
import com.example.ui.RadioViewModel
import com.example.ui.RadioViewModelFactory
import com.example.ui.theme.MyApplicationTheme

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: RadioViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialise local Room database & access layers
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = RadioRepository(database.radioDao())

        // 2. Initialise ViewModel through Factory injection
        val vmFactory = RadioViewModelFactory(application, repository)
        viewModel = ViewModelProvider(this, vmFactory)[RadioViewModel::class.java]

        // 3. Process potential direct share file launch intent
        handleShareIntent(intent)

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BroadcastScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("audio/")) {
                val audioUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }
                if (audioUri != null) {
                    processSharedAudioUri(audioUri)
                }
            }
        }
    }

    private fun processSharedAudioUri(uri: Uri) {
        try {
            var displayName = "Arquivo Compartilhado.mp3"
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        val nameValue = it.getString(nameIndex)
                        if (!nameValue.isNullOrEmpty()) {
                            displayName = nameValue
                        }
                    }
                }
            }

            val inputStream = contentResolver.openInputStream(uri)
            if (inputStream != null) {
                // Ensure unique name for local caching
                val tempFile = File(cacheDir, "shared_${System.currentTimeMillis()}_${displayName.replace(" ", "_")}")
                tempFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                Log.d("MainActivity", "Successfully cached shared file to: ${tempFile.absolutePath}")
                viewModel.importSharedAudio(tempFile, displayName)
            } else {
                Log.e("MainActivity", "Input stream resolved to null for content Uri")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load and cache shared audio file uri content", e)
        }
    }
}
