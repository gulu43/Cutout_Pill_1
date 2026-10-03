package com.fizz.cutoutpill

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fizz.cutoutpill.ui.theme.CutoutPillTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val prefs by lazy { Cfg.prefs(this) }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        val dm = resources.displayMetrics
        val maxW = dm.widthPixels.toFloat()
        val maxDpW = maxW / dm.density
        val maxY = 200f // 200 dp max for Y
        val maxDpWSlider = 300f
        val maxDpHSlider = 200f

        setContent {
            CutoutPillTheme {
                val context = LocalContext.current
                
                // Read current once or on reset
                var refreshTrigger by remember { mutableStateOf(0) }
                
                var cx by remember(refreshTrigger) { mutableStateOf(Cfg.get(context)[0].toFloat()) }
                var cy by remember(refreshTrigger) { mutableStateOf(Cfg.get(context)[1].toFloat()) }
                var w by remember(refreshTrigger) { mutableStateOf(Cfg.get(context)[2].toFloat()) }
                var h by remember(refreshTrigger) { mutableStateOf(Cfg.get(context)[3].toFloat()) }

                // Save when changed
                LaunchedEffect(cx) { prefs.edit().putInt(Cfg.CX, cx.roundToInt()).apply() }
                LaunchedEffect(cy) { prefs.edit().putInt(Cfg.CY, cy.roundToInt()).apply() }
                LaunchedEffect(w) { prefs.edit().putInt(Cfg.W, w.roundToInt()).apply() }
                LaunchedEffect(h) { prefs.edit().putInt(Cfg.H, h.roundToInt()).apply() }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        LargeTopAppBar(
                            title = { Text("Cutout position") },
                            colors = TopAppBarDefaults.largeTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    },
                    bottomBar = {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 3.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .windowInsetsPadding(WindowInsets.navigationBars)
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        Cfg.reset(context)
                                        refreshTrigger++
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Reset")
                                }
                                Button(
                                    onClick = { finish() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Done")
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Place the red circle directly under the camera cutout. It should be hidden, and you should only see a thin red edge near the camera. This area is also your tap target and where the pill grows from.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }

                        SettingSliderRow("X position", cx, 0f, maxW, { cx = it }, isDp = false)
                        SettingSliderRow("Y position", cy, 0f, maxY * dm.density, { cy = it }, isDp = false)
                        SettingSliderRow("Width", w, 16f * dm.density, maxDpWSlider * dm.density, { w = it }, isDp = true)
                        SettingSliderRow("Height", h, 16f * dm.density, maxDpHSlider * dm.density, { h = it }, isDp = true)
                        
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingSliderRow(
        label: String,
        value: Float,
        minVal: Float,
        maxVal: Float,
        onValueChange: (Float) -> Unit,
        isDp: Boolean
    ) {
        val density = LocalContext.current.resources.displayMetrics.density
        val displayVal = if (isDp) (value / density).roundToInt() else (value / density).roundToInt()
        
        Card(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "$label: $displayVal dp",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    // Minus button
                    FilledIconButton(
                        onClick = { onValueChange((value - density).coerceAtLeast(minVal)) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Text("-", style = MaterialTheme.typography.titleLarge)
                    }
                    
                    Slider(
                        value = value,
                        onValueChange = onValueChange,
                        valueRange = minVal..maxVal,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    
                    // Plus button
                    FilledIconButton(
                        onClick = { onValueChange((value + density).coerceAtMost(maxVal)) },
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        Text("+", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.edit().putBoolean(Cfg.CALIBRATING, true).apply()
    }

    override fun onPause() {
        prefs.edit().putBoolean(Cfg.CALIBRATING, false).apply()
        super.onPause()
    }
}
