package com.example.questnotify.quest

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.questnotify.shared.DiscoveryBeacon
import com.example.questnotify.shared.NotificationPayload
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QuestMainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val prefs = getSharedPreferences("quest_notify_prefs", Context.MODE_PRIVATE)
        val savedIp = prefs.getString("last_host_ip", "").orEmpty()
        val savedPin = prefs.getString("last_pin", "").orEmpty()

        // Start UDP discovery listener immediately so phones on the LAN appear automatically
        triggerScanLan()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7C4DFF),
                    secondary = Color(0xFF00E5FF),
                    background = Color(0xFF0B0E14),
                    surface = Color(0xFF161B26)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QuestDashboardScreen(
                        initialIp = savedIp,
                        initialPin = savedPin,
                        onScanLan = { triggerScanLan() },
                        onConnect = { ip, pin ->
                            prefs.edit()
                                .putString("last_host_ip", ip)
                                .putString("last_pin", pin)
                                .apply()
                            connectToHost(ip, pin)
                        },
                        onDisconnect = { disconnectFromHost() }
                    )
                }
            }
        }
    }

    private fun triggerScanLan() {
        val intent = Intent(this, QuestReceiverService::class.java).apply {
            action = QuestReceiverService.ACTION_SCAN_LAN
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun connectToHost(ip: String, pin: String) {
        val intent = Intent(this, QuestReceiverService::class.java).apply {
            action = QuestReceiverService.ACTION_CONNECT
            putExtra(QuestReceiverService.EXTRA_HOST_IP, ip)
            putExtra(QuestReceiverService.EXTRA_PAIRING_PIN, pin)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun disconnectFromHost() {
        val intent = Intent(this, QuestReceiverService::class.java).apply {
            action = QuestReceiverService.ACTION_DISCONNECT
        }
        startService(intent)
    }
}

@Composable
fun QuestDashboardScreen(
    initialIp: String,
    initialPin: String,
    onScanLan: () -> Unit,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit
) {
    val connectionState by QuestClientRepository.connectionState.collectAsState()
    val statusMessage by QuestClientRepository.statusMessage.collectAsState()
    val discoveredPhones by QuestClientRepository.discoveredPhones.collectAsState()
    val notifications by QuestClientRepository.notifications.collectAsState()
    val showHeadsUpToasts by QuestClientRepository.showHeadsUpToasts.collectAsState()
    val speakNotifications by QuestClientRepository.speakNotifications.collectAsState()

    var hostIpInput by remember { mutableStateOf(initialIp) }
    var pinInput by remember { mutableStateOf(initialPin) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        val isWidePanel = maxWidth >= 680.dp

        if (isWidePanel) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.44f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    QuestControlColumn(
                        connectionState = connectionState,
                        statusMessage = statusMessage,
                        discoveredPhones = discoveredPhones,
                        hostIpInput = hostIpInput,
                        onHostIpChange = { hostIpInput = it },
                        pinInput = pinInput,
                        onPinChange = { pinInput = it },
                        showHeadsUpToasts = showHeadsUpToasts,
                        speakNotifications = speakNotifications,
                        onScanLan = onScanLan,
                        onConnect = { onConnect(hostIpInput, pinInput) },
                        onDisconnect = onDisconnect
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.56f)
                        .fillMaxHeight()
                ) {
                    QuestNotificationsFeed(
                        notifications = notifications,
                        onClearAll = { QuestClientRepository.clearNotifications() },
                        onDismissOne = { QuestClientRepository.onNotificationRemoved(it) }
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    QuestControlColumn(
                        connectionState = connectionState,
                        statusMessage = statusMessage,
                        discoveredPhones = discoveredPhones,
                        hostIpInput = hostIpInput,
                        onHostIpChange = { hostIpInput = it },
                        pinInput = pinInput,
                        onPinChange = { pinInput = it },
                        showHeadsUpToasts = showHeadsUpToasts,
                        speakNotifications = speakNotifications,
                        onScanLan = onScanLan,
                        onConnect = { onConnect(hostIpInput, pinInput) },
                        onDisconnect = onDisconnect
                    )
                }
                item {
                    Box(modifier = Modifier.height(420.dp)) {
                        QuestNotificationsFeed(
                            notifications = notifications,
                            onClearAll = { QuestClientRepository.clearNotifications() },
                            onDismissOne = { QuestClientRepository.onNotificationRemoved(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestControlColumn(
    connectionState: QuestConnectionState,
    statusMessage: String,
    discoveredPhones: List<DiscoveryBeacon>,
    hostIpInput: String,
    onHostIpChange: (String) -> Unit,
    pinInput: String,
    onPinChange: (String) -> Unit,
    showHeadsUpToasts: Boolean,
    speakNotifications: Boolean,
    onScanLan: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Quest Notify VR",
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White
        )

        // Connection Status Banner
        val statusColor = when (connectionState) {
            QuestConnectionState.CONNECTED -> Color(0xFF66BB6A)
            QuestConnectionState.CONNECTING -> Color(0xFFFFCA28)
            QuestConnectionState.ERROR -> Color(0xFFEF5350)
            QuestConnectionState.DISCONNECTED -> Color(0xFF90A4AE)
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = connectionState.name,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusMessage,
                    fontSize = 13.sp,
                    color = Color(0xFFD0D7DE)
                )
            }
        }

        // Discovered Phones Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Discovered Phones on Wi-Fi",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    TextButton(onClick = onScanLan) {
                        Text("Scan LAN", color = Color(0xFF00E5FF))
                    }
                }

                if (discoveredPhones.isEmpty()) {
                    Text(
                        text = "No phones detected yet. Make sure 'Start Host' is active on your phone on the same Wi-Fi.",
                        fontSize = 12.sp,
                        color = Color(0xFF90A4AE)
                    )
                } else {
                    discoveredPhones.forEach { beacon ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1F2736))
                                .clickable { onHostIpChange(beacon.hostIp) }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = beacon.hostName,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = beacon.hostIp,
                                    color = Color(0xFF00E5FF),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = "Use IP",
                                color = Color(0xFF00E5FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = hostIpInput,
                        onValueChange = onHostIpChange,
                        label = { Text("Phone IP Address") },
                        singleLine = true,
                        modifier = Modifier.weight(0.62f)
                    )
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (it.length <= 6) onPinChange(it) },
                        label = { Text("4-Digit PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(0.38f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onConnect,
                        enabled = hostIpInput.isNotBlank() && pinInput.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                    ) {
                        Text("Pair & Connect", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    if (connectionState == QuestConnectionState.CONNECTED ||
                        connectionState == QuestConnectionState.CONNECTING
                    ) {
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        // VR Notification Behavior Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "VR Alert Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Floating VR Heads-Up Toasts", color = Color.White, fontSize = 14.sp)
                        Text(
                            "Shows native Horizon OS popup notifications even inside VR games",
                            color = Color(0xFF90A4AE),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = showHeadsUpToasts,
                        onCheckedChange = { QuestClientRepository.setShowHeadsUpToasts(it) }
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Voice Readout (TTS)", color = Color.White, fontSize = 14.sp)
                        Text(
                            "Reads incoming phone notifications aloud through Quest speakers",
                            color = Color(0xFF90A4AE),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = speakNotifications,
                        onCheckedChange = { QuestClientRepository.setSpeakNotifications(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuestNotificationsFeed(
    notifications: List<NotificationPayload>,
    onClearAll: () -> Unit,
    onDismissOne: (String) -> Unit
) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("h:mm:ss a", Locale.getDefault()) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Phone Notifications (${notifications.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                if (notifications.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("Clear All", color = Color(0xFF00E5FF))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (notifications.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No phone notifications yet.\nTap 'Send Test Notification' on your phone host app to test VR delivery!",
                        color = Color(0xFF90A4AE),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(notifications, key = { it.key }) { item ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2736)),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                val iconBitmap = rememberBitmapFromBase64(item.iconBase64)
                                if (iconBitmap != null) {
                                    Image(
                                        bitmap = iconBitmap,
                                        contentDescription = item.appName,
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFF7C4DFF).copy(alpha = 0.25f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = item.appName.take(1).uppercase(),
                                            color = Color(0xFFB388FF),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color(0xFF7C4DFF).copy(alpha = 0.25f))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = item.appName,
                                                    color = Color(0xFFB388FF),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = timeFormat.format(Date(item.timestamp)),
                                                color = Color(0xFF90A4AE),
                                                fontSize = 12.sp
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(
                                                onClick = {
                                                    QuestReceiverService.acknowledgeNotification(context, item.key)
                                                }
                                            ) {
                                                Text("Acknowledge", fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold)
                                            }
                                            TextButton(onClick = { QuestClientRepository.mutePackage(item.packageName) }) {
                                                Text("Mute", fontSize = 11.sp, color = Color(0xFFEF9A9A))
                                            }
                                            TextButton(onClick = { onDismissOne(item.key) }) {
                                                Text("Dismiss", fontSize = 11.sp, color = Color(0xFF90A4AE))
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.title,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (item.text.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = item.text,
                                            color = Color(0xFFE0E6ED),
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberBitmapFromBase64(base64: String): ImageBitmap? {
    return remember(base64) {
        if (base64.isBlank()) null
        else {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
}

