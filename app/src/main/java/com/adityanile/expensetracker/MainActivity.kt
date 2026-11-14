package com.adityanile.expensetracker

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.adityanile.expensetracker.ui.theme.ExpenseTrackerTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        mediaProjectionManager = getSystemService<MediaProjectionManager>()
            ?: error("MediaProjectionManager unavailable")

        requestNotificationPermissionIfNeeded()

        setContent {
            ExpenseTrackerTheme {
                MainScreen(
                    onEnableAccessibilityClick = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onGrantCaptureClick = {
                        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    },
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 1001)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    onEnableAccessibilityClick: () -> Unit,
    onGrantCaptureClick: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var screenshots by remember { mutableStateOf(ScreenshotStore.listScreenshots(context)) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var capturePermissionReady by remember { mutableStateOf(ScreenCaptureManager.isReady()) }
    var accessibilityReady by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var notificationReady by remember { mutableStateOf(isNotificationPermissionGranted(context)) }

    fun refreshAll() {
        screenshots = ScreenshotStore.listScreenshots(context)
        capturePermissionReady = ScreenCaptureManager.isReady()
        accessibilityReady = isAccessibilityServiceEnabled(context)
        notificationReady = isNotificationPermissionGranted(context)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val setupDone = capturePermissionReady && accessibilityReady && notificationReady

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expense Tracker") },
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!setupDone) {
                item {
                    SetupCard(
                        capturePermissionReady = capturePermissionReady,
                        accessibilityReady = accessibilityReady,
                        notificationReady = notificationReady,
                        onGrantCaptureClick = onGrantCaptureClick,
                        onEnableAccessibilityClick = onEnableAccessibilityClick,
                        onRefresh = { refreshAll() },
                    )
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Text(
                        text = if (setupDone) {
                            "Setup complete. Tap floating Capture button anywhere to save payment screenshots."
                        } else {
                            "Complete setup once. This card disappears automatically after permissions are ready."
                        },
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (screenshots.isEmpty()) {
                item {
                    Text(
                        text = "No captured screenshots yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                items(screenshots, key = { it.absolutePath }) { file ->
                    ScreenshotItem(
                        file = file,
                        onOpen = { selectedFile = file },
                        onDelete = {
                            ScreenshotStore.delete(file)
                            refreshAll()
                        },
                    )
                }
            }
        }
    }

    selectedFile?.let { file ->
        ScreenshotViewer(
            file = file,
            onDelete = {
                ScreenshotStore.delete(file)
                selectedFile = null
                refreshAll()
            },
            onClose = { selectedFile = null },
        )
    }
}

@Composable
private fun SetupCard(
    capturePermissionReady: Boolean,
    accessibilityReady: Boolean,
    notificationReady: Boolean,
    onGrantCaptureClick: () -> Unit,
    onEnableAccessibilityClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Complete Setup",
                style = MaterialTheme.typography.titleMedium,
            )
            PermissionStatusRow("Screen capture", capturePermissionReady)
            PermissionStatusRow("Accessibility service", accessibilityReady)
            PermissionStatusRow("Notifications", notificationReady)
            Button(onClick = onGrantCaptureClick, modifier = Modifier.fillMaxWidth()) {
                Text("Grant Screen Capture")
            }
            Button(onClick = onEnableAccessibilityClick, modifier = Modifier.fillMaxWidth()) {
                Text("Open Accessibility Settings")
            }
            TextButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                Text("I have completed setup")
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(label: String, enabled: Boolean) {
    val statusText = if (enabled) "Granted" else "Required"
    val statusColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = statusText, color = statusColor, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ScreenshotItem(
    file: File,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }

    ElevatedCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.labelLarge,
            )
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                        ),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text("Preview unavailable")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Text("Open")
                }
                Button(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ScreenshotViewer(
    file: File,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = file.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text("Unable to open this screenshot")
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                        Text("Close")
                    }
                    Button(onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

private fun isNotificationPermissionGranted(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expected = ComponentName(context, PaymentOverlayAccessibilityService::class.java)
        .flattenToString()
    val settingValue = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false

    return settingValue
        .split(':')
        .any { it.equals(expected, ignoreCase = true) }
}
