package com.example.questnotify.host

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.questnotify.shared.NetworkUtils
import com.example.questnotify.shared.NotificationPayload
import com.example.questnotify.shared.ProtocolConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    companion object {
        const val ENABLE_ADS = false
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Google Mobile Ads SDK (disabled by default)
        if (ENABLE_ADS) {
            MobileAds.initialize(this) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        HostBridgeRepository.initPreferences(this)
        HostBridgeRepository.updateLocalIp(NetworkUtils.getLocalIpv4Address())

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF4FC3F7),
                    secondary = Color(0xFF81C784),
                    background = Color(0xFF0F141C),
                    surface = Color(0xFF1B2230)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhoneHostScreen(
                        onStartServer = { startBridgeService() },
                        onStopServer = { stopBridgeService() },
                        onOpenListenerSettings = { openNotificationListenerSettings() },
                        onSendTestNotification = { triggerTestNotification() }
                    )
                }
            }
        }
    }

    private fun startBridgeService() {
        val intent = Intent(this, HostBridgeService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopBridgeService() {
        val intent = Intent(this, HostBridgeService::class.java).apply {
            action = HostBridgeService.ACTION_STOP
        }
        startService(intent)
    }

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun triggerTestNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val channelId = HostBridgeService.CHANNEL_ID_TEST
        if (nm != null && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Test Notifications",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val testId = Random.nextInt(2000, 8999)
        val sampleMessages = listOf(
            "Alex: Hey! Are you in VR right now? Let's hop into Walkabout Mini Golf!",
            "Calendar: Product Design Review starts in 10 minutes",
            "Uber Eats: Your driver Michael is approaching with your dinner order!",
            "Doorbell: Motion detected at the Front Porch camera",
            "WhatsApp - Sarah: Can you send me the Wi-Fi password when you get a chance?",
            "Discord - Quest Community: Beat Saber tournament bracket is live in #announcements",
            "Bank Alert: Direct deposit of $2,450.00 has posted to your checking account",
            "Flight Tracker: Flight UA 482 to San Francisco is boarding at Gate B14",
            "Lyft: Your driver arrives in 3 minutes (Silver Toyota Camry)",
            "Smart Home: Living room thermostat set to 71°F Eco Mode",
            "Mom: Calling you! Pick up whenever you're free :)",
            "Steam: A game on your wishlist is now 50% off",
            "Slack - Lead Dev: Pull request #42 was approved and merged to main!",
            "Package Tracker: Delivered: Package placed inside your mailbox"
        )
        val picked = sampleMessages.random()
        val title = picked.substringBefore(":")
        val body = picked.substringAfter(":").trim()

        // Post an actual Android system notification if Notification Listener is enabled,
        // and also push directly to HostBridgeRepository so the user can test immediately.
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val hasListener = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)

        if (hasListener && nm != null) {
            nm.notify(testId, notification)
        } else {
            val appIconBase64 = try {
                val iconDrawable = packageManager.getApplicationIcon(packageName)
                val bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                iconDrawable.setBounds(0, 0, 96, 96)
                iconDrawable.draw(canvas)
                val stream = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, stream)
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } catch (_: Exception) {
                ""
            }

            HostBridgeRepository.onPhoneNotificationPosted(
                NotificationPayload(
                    key = "test:$testId",
                    packageName = packageName,
                    appName = "Quest Notify Test",
                    title = title,
                    text = body,
                    subText = "Test Alert",
                    timestamp = System.currentTimeMillis(),
                    iconBase64 = appIconBase64
                )
            )
        }
    }
}

@Composable
fun PhoneHostScreen(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onOpenListenerSettings: () -> Unit,
    onSendTestNotification: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var isListenerEnabled by remember {
        mutableStateOf(
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isListenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
                HostBridgeRepository.updateLocalIp(NetworkUtils.getLocalIpv4Address())
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isServerRunning by HostBridgeRepository.isServerRunning.collectAsState()
    val localIp by HostBridgeRepository.localIp.collectAsState()
    val pairingPin by HostBridgeRepository.pairingPin.collectAsState()
    val ignoreOngoing by HostBridgeRepository.ignoreOngoing.collectAsState()
    val filterMode by HostBridgeRepository.filterMode.collectAsState()
    val blockedPackages by HostBridgeRepository.blockedPackages.collectAsState()
    val allowedPackages by HostBridgeRepository.allowedPackages.collectAsState()
    val knownPackages by HostBridgeRepository.knownPackages.collectAsState()
    val connectedClients by HostBridgeRepository.connectedClients.collectAsState()
    val forwardedHistory by HostBridgeRepository.forwardedHistory.collectAsState()
    val isWebViewEnabled by HostBridgeRepository.isWebViewEnabled.collectAsState()

    var showAppFilterDialog by remember { mutableStateOf(false) }
    var showInAppWebViewDialog by remember { mutableStateOf(false) }

    if (showInAppWebViewDialog) {
        val webUrl = "http://${localIp}:${ProtocolConstants.HTTP_WEB_PORT}"
        InAppWebViewDialog(
            url = webUrl,
            onDismissRequest = { showInAppWebViewDialog = false }
        )
    }

    if (showAppFilterDialog) {
        AppFilterDialog(onDismissRequest = { showAppFilterDialog = false })
    }

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize()) {
        if (MainActivity.ENABLE_ADS) {
            TopBannerAd(modifier = Modifier.fillMaxWidth())
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
        item {
            Text(
                text = "Quest Notify — Phone Host",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = "Streams your Android phone notifications over Wi-Fi to your Meta Quest 3",
                fontSize = 14.sp,
                color = Color(0xFFB0BEC5)
            )
        }

        // 1. Notification Access Permission Card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isListenerEnabled) Color(0xFF1B3326) else Color(0xFF3E2222)
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isListenerEnabled) Color(0xFF66BB6A) else Color(0xFFEF5350)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isListenerEnabled) {
                                "Notification Listener Access Granted"
                            } else {
                                "Notification Access Required"
                            },
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isListenerEnabled) {
                            "Phone notifications will automatically stream to paired Meta Quest 3 headsets."
                        } else {
                            "Grant Notification Access in Android Settings so this app can forward incoming notifications."
                        },
                        fontSize = 13.sp,
                        color = Color(0xFFD0D7DE)
                    )
                    if (!isListenerEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(onClick = onOpenListenerSettings) {
                            Text("Grant Notification Access")
                        }
                    }
                }
            }
        }

        // 2. Host Server & Pairing PIN Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Wi-Fi Bridge Server",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Color.White
                            )
                            Text(
                                text = if (isServerRunning) "Broadcasting on LAN" else "Stopped",
                                fontSize = 13.sp,
                                color = if (isServerRunning) Color(0xFF81C784) else Color(0xFF90A4AE)
                            )
                        }
                        Button(
                            onClick = {
                                if (isServerRunning) onStopServer() else onStartServer()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isServerRunning) Color(0xFFE57373) else Color(0xFF4FC3F7)
                            )
                        ) {
                            Text(
                                text = if (isServerRunning) "Stop Host" else "Start Host",
                                color = Color(0xFF0A0E14),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF101620))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // First row: Phone IP Address
                        Column {
                            Text(
                                text = "PHONE IP ADDRESS",
                                fontSize = 11.sp,
                                color = Color(0xFF90A4AE)
                            )
                            Text(
                                text = "$localIp:${ProtocolConstants.TCP_STREAM_PORT}",
                                fontSize = 17.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        // Second row: Pairing PIN
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "PAIRING PIN (VALID FOR 24H)",
                                    fontSize = 11.sp,
                                    color = Color(0xFF90A4AE)
                                )
                                Text(
                                    text = pairingPin,
                                    fontSize = 24.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF4FC3F7)
                                )
                                Text(
                                    text = "Auto-connects paired Quests",
                                    fontSize = 11.sp,
                                    color = Color(0xFF81C784)
                                )
                            }
                            TextButton(onClick = { HostBridgeRepository.regeneratePin() }) {
                                Text("New PIN", fontSize = 12.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ignore Ongoing Notifications",
                                fontSize = 14.sp,
                                color = Color.White
                            )
                            Text(
                                text = "Skips persistent media/workout progress bars",
                                fontSize = 12.sp,
                                color = Color(0xFF90A4AE)
                            )
                        }
                        Switch(
                            checked = ignoreOngoing,
                            onCheckedChange = { HostBridgeRepository.setIgnoreOngoing(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onSendTestNotification,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Send Test Notification to Quest 3")
                    }
                }
            }
        }

        // 3. App Notification Filters Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "App Notification Filter",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val filterSummary = when (filterMode) {
                                AppFilterMode.ALL_APPS -> {
                                    if (blockedPackages.isEmpty()) "Forwarding from all apps"
                                    else "All apps allowed (${blockedPackages.size} blocked)"
                                }
                                AppFilterMode.SELECTED_APPS_ONLY -> {
                                    if (allowedPackages.isEmpty()) "No apps selected (blocking all)"
                                    else "Forwarding from ${allowedPackages.size} selected app(s)"
                                }
                            }
                            Text(
                                text = filterSummary,
                                fontSize = 13.sp,
                                color = if (filterMode == AppFilterMode.SELECTED_APPS_ONLY && allowedPackages.isEmpty()) Color(0xFFEF5350) else Color(0xFF81C784)
                            )
                        }

                        Button(
                            onClick = { showAppFilterDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263248))
                        ) {
                            Text("Manage Apps", color = Color(0xFF4FC3F7), fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Mode selector row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF101620))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (filterMode == AppFilterMode.ALL_APPS) Color(0xFF1E2D40) else Color.Transparent)
                                .clickable { HostBridgeRepository.setFilterMode(AppFilterMode.ALL_APPS) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "All Apps (Blocklist)",
                                fontSize = 12.sp,
                                fontWeight = if (filterMode == AppFilterMode.ALL_APPS) FontWeight.Bold else FontWeight.Normal,
                                color = if (filterMode == AppFilterMode.ALL_APPS) Color(0xFF4FC3F7) else Color(0xFF90A4AE)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (filterMode == AppFilterMode.SELECTED_APPS_ONLY) Color(0xFF1E2D40) else Color.Transparent)
                                .clickable { HostBridgeRepository.setFilterMode(AppFilterMode.SELECTED_APPS_ONLY) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Selected Apps Only",
                                fontSize = 12.sp,
                                fontWeight = if (filterMode == AppFilterMode.SELECTED_APPS_ONLY) FontWeight.Bold else FontWeight.Normal,
                                color = if (filterMode == AppFilterMode.SELECTED_APPS_ONLY) Color(0xFF4FC3F7) else Color(0xFF90A4AE)
                            )
                        }
                    }
                }
            }
        }

        // 4. Local Webview Dashboard Card (Sliding Switch, disabled by default)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Local Webview Dashboard",
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (isWebViewEnabled) Color(0xFF1B3326) else Color(0xFF263238))
                                        .padding(horizontal = 7.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (isWebViewEnabled) "ACTIVE" else "DISABLED",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isWebViewEnabled) Color(0xFF81C784) else Color(0xFF90A4AE)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isWebViewEnabled) "Broadcasting on LAN" else "Disabled by default",
                                    fontSize = 12.sp,
                                    color = if (isWebViewEnabled) Color(0xFF81C784) else Color(0xFF90A4AE)
                                )
                            }
                        }

                        Switch(
                            checked = isWebViewEnabled,
                            onCheckedChange = { HostBridgeRepository.setWebViewEnabled(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Secure local web portal to view and action notifications from any browser or headset",
                        fontSize = 12.sp,
                        color = Color(0xFF90A4AE)
                    )

                    if (isWebViewEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        val webUrl = "http://${localIp}:${ProtocolConstants.HTTP_WEB_PORT}"

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF101620))
                                .padding(12.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Web Portal URL:",
                                        fontSize = 12.sp,
                                        color = Color(0xFF90A4AE)
                                    )
                                    Text(
                                        text = "🔒 PIN Encrypted",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF81C784)
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = webUrl,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4FC3F7)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "AES-GCM encryption is locked with your 4-digit PIN ($pairingPin). Enter this PIN in the browser to decrypt and action/dismiss alerts.",
                                    fontSize = 11.sp,
                                    color = Color(0xFF78909C)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(webUrl))
                                    context.startActivity(intent)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Open in Browser", fontSize = 12.sp)
                            }

                            Button(
                                onClick = { showInAppWebViewDialog = true },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E3A5F))
                            ) {
                                Text("Preview in App", fontSize = 12.sp, color = Color(0xFF4FC3F7))
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Disabled by default. Toggle the switch above to host the local web dashboard.",
                            fontSize = 12.sp,
                            color = Color(0xFF78909C)
                        )
                    }
                }
            }
        }

        // 5. Connected Quest 3 Devices Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Paired Meta Quest Headsets (${connectedClients.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (connectedClients.isEmpty()) {
                        Text(
                            text = "No Quest headset connected yet. Open 'Quest Notify' on your Meta Quest 3 and enter the 4-digit PIN above.",
                            fontSize = 13.sp,
                            color = Color(0xFF90A4AE)
                        )
                    } else {
                        connectedClients.forEach { client ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF66BB6A))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = client.deviceName,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                Text(
                                    text = client.ipAddress,
                                    color = Color(0xFF90A4AE),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Forwarded Notifications Log
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Forwarded Notifications (${forwardedHistory.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                if (forwardedHistory.isNotEmpty()) {
                    TextButton(onClick = { HostBridgeRepository.clearHistory() }) {
                        Text("Clear")
                    }
                }
            }
        }

        items(forwardedHistory) { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161D28)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    val iconBitmap = remember(item.iconBase64) {
                        if (item.iconBase64.isNotBlank()) {
                            try {
                                val bytes = Base64.decode(item.iconBase64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                    }

                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap,
                            contentDescription = item.appName,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = item.appName,
                                fontSize = 12.sp,
                                color = Color(0xFF4FC3F7),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = timeFormat.format(Date(item.timestamp)),
                                fontSize = 11.sp,
                                color = Color(0xFF78909C)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (item.text.isNotEmpty()) {
                            Text(
                                text = item.text,
                                fontSize = 13.sp,
                                color = Color(0xFFCFD8DC)
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * Top Banner Ad powered by Google Mobile Ads (AdMob).
 * Uses Google's official sample banner unit ID:
 * ca-app-pub-3940256099942544/6300978111
 * Replace with your production AdMob Banner Unit ID when publishing.
 */
@Composable
fun TopBannerAd(modifier: Modifier = Modifier) {
    if (!MainActivity.ENABLE_ADS) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0F141C))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { context ->
                AdView(context).apply {
                    setAdSize(AdSize.BANNER)
                    // Official sample ad unit ID for testing banner ads
                    adUnitId = "ca-app-pub-3940256099942544/6300978111"
                    loadAd(AdRequest.Builder().build())
                }
            }
        )
    }
}

data class AppInfoItem(
    val packageName: String,
    val appName: String
)

@Composable
fun AppFilterDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val filterMode by HostBridgeRepository.filterMode.collectAsState()
    val blockedPackages by HostBridgeRepository.blockedPackages.collectAsState()
    val allowedPackages by HostBridgeRepository.allowedPackages.collectAsState()
    val knownPackages by HostBridgeRepository.knownPackages.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<AppInfoItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(knownPackages) {
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val launcherApps = try {
                pm.queryIntentActivities(intent, 0)
            } catch (_: Exception) {
                emptyList()
            }

            val map = mutableMapOf<String, AppInfoItem>()
            for (resolveInfo in launcherApps) {
                val pkg = resolveInfo.activityInfo.packageName
                if (pkg == context.packageName) continue
                val label = resolveInfo.loadLabel(pm)?.toString() ?: pkg
                map[pkg] = AppInfoItem(packageName = pkg, appName = label)
            }

            for (pkg in knownPackages) {
                if (pkg == context.packageName) continue
                if (!map.containsKey(pkg)) {
                    val label = try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (_: Exception) {
                        pkg.substringAfterLast('.').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    }
                    map[pkg] = AppInfoItem(packageName = pkg, appName = label)
                }
            }

            val sorted = map.values.sortedBy { it.appName.lowercase() }
            withContext(Dispatchers.Main) {
                installedApps = sorted
                isLoading = false
            }
        }
    }

    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141A24))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allowed App Notifications",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color.White
                        )
                        Text(
                            text = if (filterMode == AppFilterMode.ALL_APPS)
                                "Uncheck apps to mute them on your Quest"
                            else
                                "Only checked apps will be forwarded to Quest",
                            fontSize = 12.sp,
                            color = Color(0xFF90A4AE)
                        )
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text("Done", fontWeight = FontWeight.Bold, color = Color(0xFF4FC3F7), fontSize = 15.sp)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps...", color = Color(0xFF78909C), fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Summary & Quick Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val allowedCount = installedApps.count { HostBridgeRepository.isPackageAllowed(it.packageName) }
                    Text(
                        text = "$allowedCount of ${installedApps.size} apps allowed",
                        fontSize = 12.sp,
                        color = Color(0xFF81C784),
                        fontWeight = FontWeight.SemiBold
                    )

                    Row {
                        TextButton(
                            onClick = {
                                val targets = filteredApps.map { it.packageName }
                                HostBridgeRepository.setAllAppsAllowed(targets, true)
                            }
                        ) {
                            Text("Allow All", fontSize = 12.sp, color = Color(0xFF4FC3F7))
                        }
                        TextButton(
                            onClick = {
                                val targets = filteredApps.map { it.packageName }
                                HostBridgeRepository.setAllAppsAllowed(targets, false)
                            }
                        ) {
                            Text("Block All", fontSize = 12.sp, color = Color(0xFFEF5350))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Loading apps...", color = Color(0xFF90A4AE))
                    }
                } else if (filteredApps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No matching apps found", color = Color(0xFF90A4AE))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            val isAllowed = HostBridgeRepository.isPackageAllowed(app.packageName)
                            val iconBitmap = remember(app.packageName) {
                                try {
                                    val drawable = context.packageManager.getApplicationIcon(app.packageName)
                                    val sizePx = 72
                                    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
                                    val canvas = Canvas(bmp)
                                    drawable.setBounds(0, 0, sizePx, sizePx)
                                    drawable.draw(canvas)
                                    bmp.asImageBitmap()
                                } catch (_: Exception) {
                                    null
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1B2332))
                                    .clickable {
                                        HostBridgeRepository.setAppAllowed(app.packageName, !isAllowed)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (iconBitmap != null) {
                                        Image(
                                            bitmap = iconBitmap,
                                            contentDescription = app.appName,
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF37474F)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = app.appName.take(1).uppercase(),
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = app.appName,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = app.packageName,
                                            color = Color(0xFF78909C),
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Switch(
                                    checked = isAllowed,
                                    onCheckedChange = { checked ->
                                        HostBridgeRepository.setAppAllowed(app.packageName, checked)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
fun InAppWebViewDialog(
    url: String,
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF0F141C)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B2230))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Local Webview Dashboard",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Text(
                            text = url,
                            fontSize = 12.sp,
                            color = Color(0xFF90A4AE)
                        )
                    }
                    TextButton(onClick = onDismissRequest) {
                        Text("Close", color = Color(0xFF4FC3F7), fontWeight = FontWeight.Bold)
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl(url)
                        }
                    }
                )
            }
        }
    }
}
