package com.adityanile.expensetracker

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.material3.CircularProgressIndicator
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
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

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
    var savedAnalyses by remember { mutableStateOf(ExpenseAnalysisStore.loadRecords(context)) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var capturePermissionReady by remember { mutableStateOf(ScreenCaptureManager.isReady()) }
    var accessibilityReady by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var notificationReady by remember { mutableStateOf(isNotificationPermissionGranted(context)) }
    val azureSettings = remember { AzureAnalyzerSettingsProvider.fromBuildConfig() }

    var analysisFieldsByPath by remember { mutableStateOf<Map<String, List<AzureExtractedField>>>(emptyMap()) }
    var analysisErrorByPath by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var analysisInProgressPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun refreshAll() {
        screenshots = ScreenshotStore.listScreenshots(context)
        savedAnalyses = ExpenseAnalysisStore.loadRecords(context)
        capturePermissionReady = ScreenCaptureManager.isReady()
        accessibilityReady = isAccessibilityServiceEnabled(context)
        notificationReady = isNotificationPermissionGranted(context)

        val validPaths = screenshots.map { it.absolutePath }.toSet()
        analysisFieldsByPath = analysisFieldsByPath.filterKeys { it in validPaths }
        analysisErrorByPath = analysisErrorByPath.filterKeys { it in validPaths }
        analysisInProgressPaths = analysisInProgressPaths.filter { it in validPaths }.toSet()
    }

    fun runAnalysis(file: File) {
        if (!azureSettings.isConfigured()) {
            Toast.makeText(
                context,
                "Azure analyzer is not configured in app secrets.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val path = file.absolutePath
        analysisInProgressPaths = analysisInProgressPaths + path
        analysisErrorByPath = analysisErrorByPath - path

        thread {
            val result = AzureContentUnderstandingClient.analyzeScreenshot(file, azureSettings)
            Handler(Looper.getMainLooper()).post {
                analysisInProgressPaths = analysisInProgressPaths - path
                if (result.isSuccess) {
                    val fields = result.getOrNull().orEmpty()
                    analysisFieldsByPath = analysisFieldsByPath + (path to fields)
                    analysisErrorByPath = analysisErrorByPath - path
                } else {
                    analysisFieldsByPath = analysisFieldsByPath - path
                    analysisErrorByPath = analysisErrorByPath + (
                        path to (result.exceptionOrNull()?.message ?: "Analysis failed")
                        )
                }
            }
        }
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
                            "Setup complete. Tap floating Capture button anywhere to save payment screenshots. Tap Analyze to extract fields."
                        } else {
                            "Complete setup once. This card disappears automatically after permissions are ready."
                        },
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                Text(
                    text = "Saved Analyses",
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            if (savedAnalyses.isEmpty()) {
                item {
                    Text(
                        text = "No saved analysis records yet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                items(savedAnalyses, key = { it.id }) { record ->
                    SavedAnalysisItem(record = record)
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
                    val path = file.absolutePath
                    ScreenshotItem(
                        file = file,
                        extractedFields = analysisFieldsByPath[path].orEmpty(),
                        analysisError = analysisErrorByPath[path],
                        isAnalyzing = path in analysisInProgressPaths,
                        onOpen = { selectedFile = file },
                        onAnalyze = { runAnalysis(file) },
                        onDelete = {
                            ScreenshotStore.delete(file)
                            analysisFieldsByPath = analysisFieldsByPath - path
                            analysisErrorByPath = analysisErrorByPath - path
                            analysisInProgressPaths = analysisInProgressPaths - path
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
                val deletedPath = file.absolutePath
                ScreenshotStore.delete(file)
                analysisFieldsByPath = analysisFieldsByPath - deletedPath
                analysisErrorByPath = analysisErrorByPath - deletedPath
                analysisInProgressPaths = analysisInProgressPaths - deletedPath
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
    extractedFields: List<AzureExtractedField>,
    analysisError: String?,
    isAnalyzing: Boolean,
    onOpen: () -> Unit,
    onAnalyze: () -> Unit,
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

            Button(
                onClick = onAnalyze,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isAnalyzing,
            ) {
                Text(if (isAnalyzing) "Analyzing..." else "Analyze with Azure")
            }

            if (isAnalyzing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = "Extracting fields from screenshot",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (analysisError != null) {
                Text(
                    text = analysisError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (extractedFields.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Captured Fields",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        extractedFields.forEach { field ->
                            val valueText = field.value.ifBlank { "-" }
                            Text(
                                text = "${field.name}: $valueText",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
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

@Composable
private fun SavedAnalysisItem(record: ExpenseAnalysisRecord) {
    ElevatedCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM,
                    DateFormat.SHORT,
                ).format(Date(record.savedAtMillis)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            if (record.fields.isEmpty()) {
                Text(
                    text = "No fields in this record",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                record.fields.forEach { (key, value) ->
                    Text(
                        text = "$key: ${value.ifBlank { "-" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
