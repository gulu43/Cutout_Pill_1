package com.fizz.cutoutpill

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fizz.cutoutpill.ui.theme.CutoutPillTheme

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CutoutPillTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .windowInsetsPadding(WindowInsets.systemBars)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = "Cutout Pill",
                            style = MaterialTheme.typography.displayMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "An invisible touch area over your camera cutout that opens a Material You brightness pill on tap.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "Setup & Permissions",
                                    style = MaterialTheme.typography.titleLarge
                                )

                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        startActivity(Intent(this@HomeActivity, MainActivity::class.java))
                                    }
                                ) {
                                    Text("Position Setup & Preview")
                                }

                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }
                                ) {
                                    Text("Enable Accessibility Service")
                                }

                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                                Uri.parse("package:$packageName")
                                            )
                                        )
                                    }
                                ) {
                                    Text("Grant Brightness Permission")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}