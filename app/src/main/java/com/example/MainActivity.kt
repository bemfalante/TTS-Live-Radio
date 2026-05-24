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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialise local Room database & access layers
        val database = AppDatabase.getDatabase(applicationContext)
        val repository = RadioRepository(database.radioDao())

        // 2. Initialise ViewModel through Factory injection
        val vmFactory = RadioViewModelFactory(application, repository)
        val viewModel = ViewModelProvider(this, vmFactory)[RadioViewModel::class.java]

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
}
