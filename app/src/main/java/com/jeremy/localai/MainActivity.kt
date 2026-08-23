package com.jeremy.localai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRootNavigation()
            }
        }
    }
}

class AppPreferences(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("local_ai_prefs", android.content.Context.MODE_PRIVATE)

    var hasSeenOnboarding: Boolean
        get() = prefs.getBoolean("has_seen_onboarding", false)
        set(value) = prefs.edit().putBoolean("has_seen_onboarding", value).apply()

    var useOfflineMode: Boolean
        get() = prefs.getBoolean("use_offline_mode", true)
        set(value) = prefs.edit().putBoolean("use_offline_mode", value).apply()
        
    var selectedModelUrl: String
        get() = prefs.getString("selected_model_url", "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf") ?: ""
        set(value) = prefs.edit().putString("selected_model_url", value).apply()
}

sealed class AppScreen(val route: String) {
    object Splash : AppScreen("splash")
    object Onboarding : AppScreen("onboarding")
    object MainHub : AppScreen("main_hub")
}

@Composable
fun AppRootNavigation() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val prefs = remember { AppPreferences(context) }

    val startDestination = if (!prefs.hasSeenOnboarding) {
        AppScreen.Onboarding.route
    } else {
        AppScreen.Splash.route
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(AppScreen.Onboarding.route) {
            OnboardingScreen(
                onFinished = { offlineSelected, modelUrl ->
                    prefs.hasSeenOnboarding = true
                    prefs.useOfflineMode = offlineSelected
                    prefs.selectedModelUrl = modelUrl
                    navController.navigate(AppScreen.MainHub.route) {
                        popUpTo(AppScreen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }
        composable(AppScreen.Splash.route) {
            SplashScreen(
                onLoadingFinished = {
                    navController.navigate(AppScreen.MainHub.route) {
                        popUpTo(AppScreen.Splash.route) { inclusive = true }
                    }
                }
            )
        }
        composable(AppScreen.MainHub.route) {
            // Replace this with your actual ModelHub/Chat screen call
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "LocalAI Main Hub", 
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (prefs.useOfflineMode) "Mode: Local Storage (Offline)" else "Mode: Web Download Active",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (!prefs.useOfflineMode) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Target: ${prefs.selectedModelUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnboardingScreen(onFinished: (Boolean, String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    var useOffline by remember { mutableStateOf(true) }
    var targetModelUrl by remember { mutableStateOf("https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                when (page) {
                    0 -> OnboardingPageView(
                        title = "100% Offline AI Execution",
                        description = "Run GGUF and LiteRT models natively utilizing your device's hardware acceleration without relying on cloud servers.",
                        icon = Icons.Default.CloudOff
                    )
                    1 -> OnboardingPageView(
                        title = "Zero Data Leakage",
                        description = "Your prompts, session data, and private context remain securely inside your hardware environment.",
                        icon = Icons.Default.Security
                    )
                    2 -> SetupConfigurationPageView(
                        useOffline = useOffline,
                        onOfflineChanged = { useOffline = it },
                        modelUrl = targetModelUrl,
                        onModelUrlChanged = { targetModelUrl = it }
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(
                    modifier = Modifier.padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .padding(4.dp)
                                .size(if (pagerState.currentPage == index) 12.dp else 8.dp)
                                .background(
                                    color = if (pagerState.currentPage == index) 
                                        MaterialTheme.colorScheme.primary 
                                    else 
                                        MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape
                                )
                        )
                    }
                }

                Button(
                    onClick = {
                        if (pagerState.currentPage < 2) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        } else {
                            onFinished(useOffline, targetModelUrl)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(text = if (pagerState.currentPage == 2) "Initialize App" else "Next")
                }
            }
        }
    }
}

@Composable
fun OnboardingPageView(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(110.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SetupConfigurationPageView(
    useOffline: Boolean,
    onOfflineChanged: (Boolean) -> Unit,
    modelUrl: String,
    onModelUrlChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Engine Setup Options",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Configure how your local runtime acquires model weights.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Pure Offline Storage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = "I will manually manage my local GGUF/LiteRT files.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = useOffline, onCheckedChange = onOfflineChanged)
            }
        }

        if (!useOffline) {
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = modelUrl,
                onValueChange = onModelUrlChanged,
                label = { Text("Model Download URL (.gguf)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "The app will fetch weights from this link if they are missing locally.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun SplashScreen(onLoadingFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1800L) // Simulating engine/tensor library startup
        onLoadingFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = "Engine Loading",
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Initializing Neural Runtimes...",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                strokeWidth = 3.dp
            )
        }
    }
}
