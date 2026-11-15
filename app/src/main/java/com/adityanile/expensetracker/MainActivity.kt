package com.adityanile.expensetracker

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adityanile.expensetracker.ui.theme.ExpenseTrackerTheme
import java.text.DateFormat
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var sharedReceiptPayload by mutableStateOf<SharedReceiptPayload?>(null)

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
        sharedReceiptPayload = intent.toSharedReceiptPayload(packageManager)

        setContent {
            ExpenseTrackerTheme {
                MainScreen(
                    onEnableAccessibilityClick = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onGrantCaptureClick = {
                        screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                    },
                    incomingSharedReceipt = sharedReceiptPayload,
                    onSharedReceiptHandled = { sharedReceiptPayload = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedReceiptPayload = intent.toSharedReceiptPayload(packageManager)
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

data class DashboardSummary(
    val totalSpent: Double,
    val transactionCount: Int,
    val averageSpend: Double,
    val topCategory: String,
    val topApp: String,
    val categoryTotals: List<Pair<String, Double>>,
    val appTotals: List<Pair<String, Double>>,
    val monthlyTotals: List<Pair<String, Double>>,
    val yearlyTotals: List<Pair<String, Double>>,
)

private enum class BreakdownType {
    CATEGORY,
    APP,
    MONTH,
    YEAR,
}

private data class BreakdownDrillDown(
    val title: String,
    val type: BreakdownType,
    val key: String,
)

private data class SharedReceiptPayload(
    val imageUri: Uri,
    val sourcePackageName: String?,
    val sourceAppName: String?,
    val requestId: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    onEnableAccessibilityClick: () -> Unit,
    onGrantCaptureClick: () -> Unit,
    incomingSharedReceipt: SharedReceiptPayload?,
    onSharedReceiptHandled: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var savedAnalyses by remember { mutableStateOf<List<ExpenseAnalysisRecord>>(emptyList()) }
    var capturePermissionReady by remember { mutableStateOf(ScreenCaptureManager.isReady()) }
    var accessibilityReady by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var notificationReady by remember { mutableStateOf(isNotificationPermissionGranted(context)) }

    val tableSettings = remember { AzureTableSettingsProvider.fromBuildConfig() }

    var savedAnalysesLoading by remember { mutableStateOf(false) }
    var savedAnalysesError by remember { mutableStateOf<String?>(null) }
    var deletingRecordId by remember { mutableStateOf<Long?>(null) }
    var savingRecordId by remember { mutableStateOf<Long?>(null) }
    var editingRecord by remember { mutableStateOf<ExpenseAnalysisRecord?>(null) }

    var selectedMonth by remember { mutableStateOf<String?>(null) }
    var showExpenseList by remember { mutableStateOf(false) }
    var drillDown by remember { mutableStateOf<BreakdownDrillDown?>(null) }

    var sharedReceiptProcessing by remember { mutableStateOf(false) }
    var sharedReceiptSaving by remember { mutableStateOf(false) }
    var sharedReceiptEditableFields by remember { mutableStateOf<Map<String, String>?>(null) }

    fun refreshSavedAnalysesFromAzure() {
        if (!tableSettings.isConfigured()) {
            savedAnalyses = emptyList()
            savedAnalysesError = "Azure Table is not configured in app secrets."
            return
        }

        savedAnalysesLoading = true
        savedAnalysesError = null
        thread {
            val fetchResult = AzureTableExpenseClient.fetchExpenses(tableSettings)
            Handler(Looper.getMainLooper()).post {
                savedAnalysesLoading = false
                if (fetchResult.isSuccess) {
                    val records = fetchResult.getOrNull().orEmpty()
                    savedAnalyses = records
                    ExpenseAnalysisStore.replaceAll(context, records)
                    savedAnalysesError = null
                } else {
                    savedAnalyses = emptyList()
                    savedAnalysesError = fetchResult.exceptionOrNull()?.message ?: "Failed to fetch expenses from Azure Table"
                }
            }
        }
    }

    fun refreshAll() {
        capturePermissionReady = ScreenCaptureManager.isReady()
        accessibilityReady = isAccessibilityServiceEnabled(context)
        notificationReady = isNotificationPermissionGranted(context)
        refreshSavedAnalysesFromAzure()
    }

    fun deleteSavedAnalysis(record: ExpenseAnalysisRecord) {
        if (deletingRecordId != null || savingRecordId != null) return

        deletingRecordId = record.id
        thread {
            val deleteResult = AzureTableExpenseClient.deleteExpense(record, tableSettings)
            Handler(Looper.getMainLooper()).post {
                deletingRecordId = null
                if (deleteResult.isSuccess) {
                    ExpenseAnalysisStore.deleteRecord(context, record.id)
                    refreshSavedAnalysesFromAzure()
                    Toast.makeText(context, "Expense deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        deleteResult.exceptionOrNull()?.message ?: "Failed to delete expense",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    fun updateSavedAnalysis(record: ExpenseAnalysisRecord, updatedFields: Map<String, String>) {
        if (deletingRecordId != null || savingRecordId != null) return

        savingRecordId = record.id
        val sanitizedFields = updatedFields.filterKeys { !ExpenseFieldRules.isExcludedFieldKey(it) }
        val updatedRecord = record.copy(fields = sanitizedFields)

        thread {
            val upsertResult = AzureTableExpenseClient.upsertExpense(updatedRecord, tableSettings)
            Handler(Looper.getMainLooper()).post {
                savingRecordId = null
                if (upsertResult.isSuccess) {
                    Toast.makeText(context, "Expense updated", Toast.LENGTH_SHORT).show()
                    refreshSavedAnalysesFromAzure()
                } else {
                    Toast.makeText(
                        context,
                        upsertResult.exceptionOrNull()?.message ?: "Failed to update expense",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(incomingSharedReceipt?.requestId) {
        val payload = incomingSharedReceipt ?: return@LaunchedEffect
        onSharedReceiptHandled()

        val settings = AzureOpenAiSettingsProvider.fromBuildConfig()
        if (!settings.isConfigured()) {
            Toast.makeText(context, "Azure OpenAI settings are missing", Toast.LENGTH_LONG).show()
            return@LaunchedEffect
        }

        sharedReceiptProcessing = true
        Toast.makeText(context, "Analyzing shared receipt…", Toast.LENGTH_SHORT).show()

        thread {
            val imageBytesResult = runCatching {
                context.contentResolver.openInputStream(payload.imageUri)?.use { stream ->
                    stream.readBytes()
                } ?: error("Unable to read shared image")
            }

            if (imageBytesResult.isFailure) {
                Handler(Looper.getMainLooper()).post {
                    sharedReceiptProcessing = false
                    Toast.makeText(
                        context,
                        imageBytesResult.exceptionOrNull()?.message ?: "Failed to read shared image",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@thread
            }

            val analyzeResult = AzureOpenAiExpenseClient.analyzeImageBytes(
                imageBytes = imageBytesResult.getOrThrow(),
                settings = settings,
            )

            Handler(Looper.getMainLooper()).post {
                sharedReceiptProcessing = false
                if (analyzeResult.isSuccess) {
                    sharedReceiptEditableFields = toEditableFieldMap(
                        fields = analyzeResult.getOrNull().orEmpty(),
                        sourceAppName = payload.sourceAppName,
                        sourcePackageName = payload.sourcePackageName,
                    )
                } else {
                    Toast.makeText(
                        context,
                        analyzeResult.exceptionOrNull()?.message ?: "Failed to analyze shared image",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        refreshAll()
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

    val availableMonths = remember(savedAnalyses) {
        savedAnalyses
            .mapNotNull { record -> record.expenseMonthLabelOrNull() }
            .distinct()
            .sortedByDescending { monthLabel ->
                parseMonthLabelToMillis(monthLabel) ?: Long.MIN_VALUE
            }
    }

    LaunchedEffect(availableMonths) {
        if (selectedMonth.isNullOrBlank() && availableMonths.isNotEmpty()) {
            selectedMonth = availableMonths.first()
        }
    }

    val filteredRecords = remember(savedAnalyses, selectedMonth) {
        val scoped = if (selectedMonth.isNullOrBlank()) {
            savedAnalyses
        } else {
            savedAnalyses.filter {
                it.expenseMonthLabelOrNull() == selectedMonth
            }
        }

        scoped.sortedByDescending { it.sortDateMillis() }
    }
    val dashboardSummary = remember(filteredRecords) {
        buildDashboardSummary(filteredRecords)
    }
    val allTimeSummary = remember(savedAnalyses) {
        buildDashboardSummary(savedAnalyses)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Expense Tracker") },
                actions = {
                    TextButton(onClick = { refreshSavedAnalysesFromAzure() }) {
                        Text("Refresh")
                    }
                },
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
                            "Capture from overlay anytime. This dashboard gives a fast overview and detailed editable records."
                        } else {
                            "Complete setup once. After that, only analytics and records appear here."
                        },
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item {
                MonthSelectorCard(
                    availableMonths = availableMonths,
                    selectedMonth = selectedMonth,
                    onSelectMonth = { selectedMonth = it },
                )
            }

            item {
                DashboardOverviewCards(summary = dashboardSummary)
            }

            item {
                SpendingBreakdownSection(
                    summary = dashboardSummary,
                    onCategoryClick = { category ->
                        drillDown = BreakdownDrillDown(
                            title = "Category: $category",
                            type = BreakdownType.CATEGORY,
                            key = category,
                        )
                    },
                    onAppClick = { app ->
                        drillDown = BreakdownDrillDown(
                            title = "App: $app",
                            type = BreakdownType.APP,
                            key = app,
                        )
                    },
                )
            }

            if (selectedMonth == null) {
                item {
                    TimeTrendSection(
                        summary = allTimeSummary,
                        onMonthClick = { month ->
                            drillDown = BreakdownDrillDown(
                                title = "Month: $month",
                                type = BreakdownType.MONTH,
                                key = month,
                            )
                        },
                        onYearClick = { year ->
                            drillDown = BreakdownDrillDown(
                                title = "Year: $year",
                                type = BreakdownType.YEAR,
                                key = year,
                            )
                        },
                    )
                }
            } else {
                item {
                    ElevatedCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Time Trends",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "Hidden for a single month view. Choose 'All' to see month/year trend analysis.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Interactive Breakdown",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Tap any bar to view and edit matching expenses.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        if (savedAnalysesLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "All Expenses",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "${filteredRecords.size} records for ${selectedMonth ?: "all months"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        OutlinedButton(onClick = { showExpenseList = !showExpenseList }) {
                            Text(if (showExpenseList) "Hide" else "View")
                        }
                    }
                }
            }

            if (savedAnalysesError != null) {
                item {
                    Text(
                        text = savedAnalysesError ?: "Failed to load expenses",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (showExpenseList) {
                if (filteredRecords.isEmpty()) {
                    item {
                        Text(
                            text = "No expenses found for this month.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    items(filteredRecords, key = { it.rowKey }) { record ->
                        ExpenseRecordItem(
                            record = record,
                            deleting = deletingRecordId == record.id,
                            saving = savingRecordId == record.id,
                            onEdit = { editingRecord = record },
                            onDelete = { deleteSavedAnalysis(record) },
                        )
                    }
                }
            }
        }
    }

    val drillDownRecords = remember(filteredRecords, drillDown) {
        val active = drillDown ?: return@remember emptyList()
        val scoped = when (active.type) {
            BreakdownType.CATEGORY -> filteredRecords.filter {
                it.fieldValueOrFallback("ExpenseCategory").equals(active.key, ignoreCase = true)
            }

            BreakdownType.APP -> filteredRecords.filter {
                it.fieldValueOrFallback("PaymentApp").equals(active.key, ignoreCase = true)
            }

            BreakdownType.MONTH -> filteredRecords.filter {
                it.expenseMonthLabelOrNull().equals(active.key, ignoreCase = true)
            }

            BreakdownType.YEAR -> filteredRecords.filter {
                it.expenseYearLabelOrNull().equals(active.key, ignoreCase = true)
            }
        }

        scoped.sortedByDescending { it.sortDateMillis() }
    }

    drillDown?.let { active ->
        ExpenseDrillDownDialog(
            title = active.title,
            records = drillDownRecords,
            deletingRecordId = deletingRecordId,
            savingRecordId = savingRecordId,
            onDismiss = { drillDown = null },
            onEdit = { editingRecord = it },
            onDelete = { deleteSavedAnalysis(it) },
        )
    }

    editingRecord?.let { record ->
        ExpenseEditDialog(
            record = record,
            saving = savingRecordId == record.id,
            onDismiss = { editingRecord = null },
            onSave = { updatedFields ->
                editingRecord = null
                updateSavedAnalysis(record, updatedFields)
            },
        )
    }

    sharedReceiptEditableFields?.let { editableFields ->
        val draftRecord = ExpenseAnalysisRecord(
            id = -1,
            savedAtMillis = System.currentTimeMillis(),
            fields = editableFields,
            partitionKey = "",
            rowKey = "shared-receipt-draft",
        )

        ExpenseEditDialog(
            record = draftRecord,
            saving = sharedReceiptSaving,
            onDismiss = {
                if (!sharedReceiptSaving) {
                    sharedReceiptEditableFields = null
                }
            },
            onSave = { updatedFields ->
                val cleaned = updatedFields
                    .filterKeys { key -> !ExpenseFieldRules.isExcludedFieldKey(key) }
                    .mapValues { (_, value) -> value.trim() }
                    .filterValues { it.isNotBlank() }

                if (cleaned.isEmpty()) {
                    Toast.makeText(context, "Nothing to save", Toast.LENGTH_SHORT).show()
                    return@ExpenseEditDialog
                }

                if (!tableSettings.isConfigured()) {
                    Toast.makeText(context, "Azure Table settings are missing", Toast.LENGTH_LONG).show()
                    return@ExpenseEditDialog
                }

                sharedReceiptSaving = true
                thread {
                    val savedRecord = ExpenseAnalysisStore.saveRecord(context, cleaned)
                    val upsertResult = AzureTableExpenseClient.upsertExpense(savedRecord, tableSettings)

                    Handler(Looper.getMainLooper()).post {
                        sharedReceiptSaving = false
                        if (upsertResult.isSuccess) {
                            sharedReceiptEditableFields = null
                            Toast.makeText(context, "Shared expense saved", Toast.LENGTH_SHORT).show()
                            refreshSavedAnalysesFromAzure()
                        } else {
                            Toast.makeText(
                                context,
                                upsertResult.exceptionOrNull()?.message ?: "Failed to save shared expense",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun MonthSelectorCard(
    availableMonths: List<String>,
    selectedMonth: String?,
    onSelectMonth: (String?) -> Unit,
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Choose Month",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val isAllSelected = selectedMonth == null
                if (isAllSelected) {
                    Button(onClick = { onSelectMonth(null) }) { Text("All") }
                } else {
                    OutlinedButton(onClick = { onSelectMonth(null) }) { Text("All") }
                }

                availableMonths.forEach { month ->
                    val isSelected = selectedMonth == month
                    if (isSelected) {
                        Button(onClick = { onSelectMonth(month) }) { Text(month) }
                    } else {
                        OutlinedButton(onClick = { onSelectMonth(month) }) { Text(month) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardOverviewCards(summary: DashboardSummary) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                title = "Total Spent",
                value = formatCurrency(summary.totalSpent),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "Transactions",
                value = summary.transactionCount.toString(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MetricCard(
                title = "Avg Expense",
                value = formatCurrency(summary.averageSpend),
                modifier = Modifier.weight(1f),
            )
            MetricCard(
                title = "Top Category",
                value = summary.topCategory,
                modifier = Modifier.weight(1f),
            )
        }
        MetricCard(
            title = "Top Payment App",
            value = summary.topApp,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp)
                .heightIn(min = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SpendingBreakdownSection(
    summary: DashboardSummary,
    onCategoryClick: (String) -> Unit,
    onAppClick: (String) -> Unit,
) {
    ElevatedCard(
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Spending Breakdown",
                style = MaterialTheme.typography.titleMedium,
            )
            HorizontalAmountBars(
                title = "By Category",
                entries = summary.categoryTotals,
                emptyLabel = "No category data",
                onEntryClick = onCategoryClick,
                maxItems = null,
            )
            HorizontalAmountBars(
                title = "By Payment App",
                entries = summary.appTotals,
                emptyLabel = "No app data",
                onEntryClick = onAppClick,
            )
        }
    }
}

@Composable
private fun TimeTrendSection(
    summary: DashboardSummary,
    onMonthClick: (String) -> Unit,
    onYearClick: (String) -> Unit,
) {
    ElevatedCard(
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Time Trends",
                style = MaterialTheme.typography.titleMedium,
            )
            HorizontalAmountBars(
                title = "Monthly",
                entries = summary.monthlyTotals.takeLast(8),
                emptyLabel = "No monthly trend data",
                onEntryClick = onMonthClick,
            )
            HorizontalAmountBars(
                title = "Yearly",
                entries = summary.yearlyTotals,
                emptyLabel = "No yearly trend data",
                onEntryClick = onYearClick,
            )
        }
    }
}

@Composable
private fun HorizontalAmountBars(
    title: String,
    entries: List<Pair<String, Double>>,
    emptyLabel: String,
    onEntryClick: (String) -> Unit,
    maxItems: Int? = 6,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )

        if (entries.isEmpty()) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.bodySmall,
            )
            return
        }

        val visibleEntries = maxItems?.let { entries.take(it) } ?: entries
        val totalAmount = visibleEntries.sumOf { it.second }.takeIf { it > 0.0 } ?: 1.0
        val maxAmount = visibleEntries.maxOfOrNull { it.second }?.takeIf { it > 0.0 } ?: 1.0

        val barColors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.75f),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.75f),
        )

        visibleEntries.forEachIndexed { index, (label, amount) ->
            val widthFraction = (amount / maxAmount).coerceIn(0.0, 1.0).toFloat()
            val percent = ((amount / totalAmount) * 100.0).coerceIn(0.0, 100.0)
            val barColor = barColors[index % barColors.size]

            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEntryClick(label) },
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${String.format(Locale.US, "%.1f", percent)}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatCurrency(amount),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Tap to view",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(9.dp)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(999.dp),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(widthFraction)
                                .height(9.dp)
                                .background(
                                    color = barColor,
                                    shape = RoundedCornerShape(999.dp),
                                ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseDrillDownDialog(
    title: String,
    records: List<ExpenseAnalysisRecord>,
    deletingRecordId: Long?,
    savingRecordId: Long?,
    onDismiss: () -> Unit,
    onEdit: (ExpenseAnalysisRecord) -> Unit,
    onDelete: (ExpenseAnalysisRecord) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .heightIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )

                Text(
                    text = "${records.size} expenses",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                if (records.isEmpty()) {
                    Text(
                        text = "No matching expenses found.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(records, key = { it.rowKey }) { record ->
                            ExpenseRecordItem(
                                record = record,
                                deleting = deletingRecordId == record.id,
                                saving = savingRecordId == record.id,
                                onEdit = { onEdit(record) },
                                onDelete = { onDelete(record) },
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun ExpenseRecordItem(
    record: ExpenseAnalysisRecord,
    deleting: Boolean,
    saving: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val amount = record.extractAmount()
    val merchant = record.fieldValueOrFallback("MerchantName")
    val category = record.fieldValueOrFallback("ExpenseCategory")
    val app = record.fieldValueOrFallback("PaymentApp")
    val paymentDate = record.displayDateTime()

    ElevatedCard {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = merchant,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = paymentDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = formatCurrency(amount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = "$category • $app",
                style = MaterialTheme.typography.bodySmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    enabled = !deleting && !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (saving) "Saving..." else "Edit")
                }
                Button(
                    onClick = onDelete,
                    enabled = !deleting && !saving,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (deleting) "Deleting..." else "Delete")
                }
            }
        }
    }
}

@Composable
private fun ExpenseEditDialog(
    record: ExpenseAnalysisRecord,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
) {
    var fieldValues by remember(record.rowKey) { mutableStateOf(record.fields) }

    val orderedKeys = remember(fieldValues) {
        val preferred = listOf(
            "Amount",
            "MerchantName",
            "ExpenseCategory",
            "PaymentApp",
            "PaymentDateTime",
            "ItemDescription",
            "UpiTransactionId",
        )

        val presentPreferred = preferred.filter { key ->
            fieldValues.keys.any { it.equals(key, ignoreCase = true) }
        }
        val matchedPreferredRealKeys = presentPreferred.mapNotNull { preferredKey ->
            fieldValues.keys.firstOrNull { it.equals(preferredKey, ignoreCase = true) }
        }

        val remaining = fieldValues.keys
            .filterNot { it in matchedPreferredRealKeys }
            .sortedBy { it.lowercase(Locale.getDefault()) }

        matchedPreferredRealKeys + remaining
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }) {
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .heightIn(max = 540.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Edit Expense",
                    style = MaterialTheme.typography.titleMedium,
                )

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    orderedKeys.forEach { key ->
                        OutlinedTextField(
                            value = fieldValues[key].orEmpty(),
                            onValueChange = { newValue ->
                                fieldValues = fieldValues.toMutableMap().apply {
                                    put(key, newValue)
                                }.toMap()
                            },
                            label = { Text(key) },
                            enabled = !saving,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = key.equals("Amount", ignoreCase = true),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            val cleaned = fieldValues.mapValues { (_, value) -> value.trim() }
                            onSave(cleaned)
                        },
                        enabled = !saving,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (saving) "Saving..." else "Save")
                    }
                }
            }
        }
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

private fun toEditableFieldMap(
    fields: List<AzureExtractedField>,
    sourceAppName: String?,
    sourcePackageName: String?,
): Map<String, String> {
    val filtered = linkedMapOf<String, String>()
    fields.forEach { field ->
        if (!ExpenseFieldRules.isExcludedFieldKey(field.name)) {
            filtered[field.name] = field.value
        }
    }

    val safeApp = sourceAppName
        ?.trim()
        ?.takeIf { it.isNotBlank() && !isGenericAppName(it) }
        ?: inferAppNameFromPackageName(sourcePackageName)
        ?: ""
    if (safeApp.isNotBlank()) {
        val paymentAppKey = filtered.keys.firstOrNull { key -> key.equals("PaymentApp", ignoreCase = true) }
        if (paymentAppKey == null) {
            val withApp = linkedMapOf<String, String>()
            withApp["PaymentApp"] = safeApp
            withApp.putAll(filtered)
            return withApp
        }
        filtered[paymentAppKey] = safeApp
    }

    return filtered
}

private fun Intent.toSharedReceiptPayload(packageManager: PackageManager): SharedReceiptPayload? {
    if (action != Intent.ACTION_SEND) return null
    val intentType = type.orEmpty()
    if (!intentType.startsWith("image/")) return null

    val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    } ?: clipData?.getItemAt(0)?.uri ?: return null

    val sourcePackage = resolveShareSourcePackage() ?: inferPackageFromUriAuthority(imageUri)
    val sourceAppName = resolveShareSourceAppName(
        packageManager = packageManager,
        sourcePackage = sourcePackage,
    ) ?: inferAppNameFromPackageName(sourcePackage)

    return SharedReceiptPayload(
        imageUri = imageUri,
        sourcePackageName = sourcePackage,
        sourceAppName = sourceAppName,
        requestId = System.currentTimeMillis(),
    )
}

private fun Intent.resolveShareSourcePackage(): String? {
    val referrerName = getStringExtra(Intent.EXTRA_REFERRER_NAME)
    val referrerPackage = runCatching {
        Uri.parse(referrerName).takeIf { it.scheme == "android-app" }?.host
    }.getOrNull()

    val extraPackage = extras?.getString("android.intent.extra.PACKAGE_NAME")
    val chooserPackage = extras?.getString(Intent.EXTRA_CHOSEN_COMPONENT)
    return referrerPackage ?: extraPackage ?: chooserPackage
}

private fun resolveShareSourceAppName(
    packageManager: PackageManager,
    sourcePackage: String?,
): String? {
    val candidatePackages = listOfNotNull(
        sourcePackage,
        sourcePackage?.substringBeforeLast(".fileprovider"),
        sourcePackage?.substringBeforeLast(".provider"),
    )
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    candidatePackages.forEach { candidate ->
        val label = runCatching {
            val appInfo = packageManager.getApplicationInfo(candidate, 0)
            packageManager.getApplicationLabel(appInfo).toString().trim()
        }.getOrNull()

        if (!label.isNullOrBlank() && !isGenericAppName(label)) {
            return label
        }
    }

    return null
}

private fun inferPackageFromUriAuthority(uri: Uri): String? {
    val authority = uri.authority?.trim().orEmpty()
    if (authority.isBlank()) return null

    val providerSuffixes = listOf(
        ".fileprovider",
        ".provider",
        ".contentprovider",
        ".files",
        ".documents",
    )

    val normalized = providerSuffixes.firstNotNullOfOrNull { suffix ->
        authority.takeIf { it.endsWith(suffix, ignoreCase = true) }?.removeSuffix(suffix)
    } ?: authority

    return normalized.takeIf { it.contains('.') }
}

private fun inferAppNameFromPackageName(packageName: String?): String? {
    val safePackage = packageName?.trim()?.lowercase(Locale.US).orEmpty()
    if (safePackage.isBlank()) return null

    val known = when {
        safePackage.contains("phonepe") -> "PhonePe"
        safePackage.contains("paisa.user") || safePackage.contains("google") && safePackage.contains("pay") -> "Google Pay"
        safePackage.contains("paytm") -> "Paytm"
        safePackage.contains("bhim") -> "BHIM"
        safePackage.contains("mobikwik") -> "MobiKwik"
        safePackage.contains("cred") -> "CRED"
        safePackage.contains("amazon") && safePackage.contains("pay") -> "Amazon Pay"
        else -> null
    }

    if (!known.isNullOrBlank()) return known

    val segment = safePackage
        .split('.')
        .asReversed()
        .firstOrNull { token -> token.isNotBlank() && token !in setOf("app", "android", "provider", "fileprovider", "files") }
        ?: return null

    return segment.replace('_', ' ').replace('-', ' ')
        .replaceFirstChar { character ->
            if (character.isLowerCase()) character.titlecase(Locale.US) else character.toString()
        }
        .takeIf { !isGenericAppName(it) }
}

private fun isGenericAppName(value: String): Boolean {
    val normalized = value.trim().lowercase(Locale.US)
    return normalized.isBlank() || normalized in setOf("unknown", "user", "app", "application", "android")
}

private fun buildDashboardSummary(records: List<ExpenseAnalysisRecord>): DashboardSummary {
    val amountRecords = records.map { record ->
        record to record.extractAmount()
    }

    val totalSpent = amountRecords.sumOf { it.second }
    val transactionCount = records.size
    val averageSpend = if (transactionCount == 0) 0.0 else totalSpent / transactionCount

    val categoryTotals = amountRecords
        .groupBy { (record, _) -> record.fieldValueOrFallback("ExpenseCategory") }
        .mapValues { (_, values) -> values.sumOf { it.second } }
        .entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }

    val appTotals = amountRecords
        .groupBy { (record, _) -> record.fieldValueOrFallback("PaymentApp") }
        .mapValues { (_, values) -> values.sumOf { it.second } }
        .entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }

    val monthFormatter = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    val monthKeyFormatter = SimpleDateFormat("yyyyMM", Locale.US)
    val monthTotalsByKey = linkedMapOf<String, Double>()

    amountRecords.forEach { (record, amount) ->
        val monthLabel = record.expenseMonthLabelOrNull() ?: return@forEach
        val monthMillis = parseMonthLabelToMillis(monthLabel) ?: return@forEach
        val key = monthKeyFormatter.format(Date(monthMillis))
        monthTotalsByKey[key] = (monthTotalsByKey[key] ?: 0.0) + amount
    }

    val monthlyTotals = monthTotalsByKey.entries
        .sortedBy { it.key }
        .map { entry ->
            val parsedDate = runCatching {
                SimpleDateFormat("yyyyMM", Locale.US).parse(entry.key)
            }.getOrNull()
            val label = parsedDate?.let { monthFormatter.format(it) } ?: entry.key
            label to entry.value
        }

    val yearTotals = amountRecords
        .mapNotNull { (record, amount) ->
            record.expenseYearLabelOrNull()?.let { yearLabel ->
                yearLabel to amount
            }
        }
        .groupBy { (yearLabel, _) -> yearLabel }
        .mapValues { (_, values) -> values.sumOf { it.second } }
        .entries
        .sortedBy { it.key }
        .map { it.key to it.value }

    return DashboardSummary(
        totalSpent = totalSpent,
        transactionCount = transactionCount,
        averageSpend = averageSpend,
        topCategory = categoryTotals.firstOrNull()?.first ?: "N/A",
        topApp = appTotals.firstOrNull()?.first ?: "N/A",
        categoryTotals = categoryTotals,
        appTotals = appTotals,
        monthlyTotals = monthlyTotals,
        yearlyTotals = yearTotals,
    )
}

private fun ExpenseAnalysisRecord.fieldValueOrFallback(primaryKey: String): String {
    val preferredCandidates = when (primaryKey) {
        "Amount" -> listOf("Amount", "amount")
        "MerchantName" -> listOf("MerchantName", "merchant_name", "merchant", "payee")
        "ExpenseCategory" -> listOf("ExpenseCategory", "expense_category", "category")
        "PaymentApp" -> listOf("PaymentApp", "payment_app", "app")
        "PaymentDateTime" -> listOf("PaymentDateTime", "payment_date_time", "date", "datetime")
        "ItemDescription" -> listOf("ItemDescription", "item_description", "description")
        "UpiTransactionId" -> listOf("UpiTransactionId", "upi_transaction_id", "txn_id")
        else -> listOf(primaryKey)
    }

    preferredCandidates.forEach { candidate ->
        val hit = fields.entries.firstOrNull { it.key.equals(candidate, ignoreCase = true) }?.value
        if (!hit.isNullOrBlank()) return hit
    }

    return "Unknown"
}

private fun ExpenseAnalysisRecord.extractAmount(): Double {
    val raw = fieldValueOrFallback("Amount")
    val cleaned = raw
        .replace(Regex("[^0-9.,-]"), "")
        .replace(",", "")

    return cleaned.toDoubleOrNull() ?: 0.0
}

private fun ExpenseAnalysisRecord.displayDateTime(): String {
    val parsed = expenseDateMillisOrNull()
    if (parsed != null) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(parsed))
    }

    val raw = fieldValueOrFallback("PaymentDateTime")
    return if (!raw.equals("Unknown", ignoreCase = true) && raw.isNotBlank()) {
        raw
    } else {
        "Date unavailable"
    }
}

private fun ExpenseAnalysisRecord.expenseDateMillisOrNull(): Long? {
    val raw = fieldValueOrFallback("PaymentDateTime")
    if (raw.equals("Unknown", ignoreCase = true) || raw.isBlank()) {
        return null
    }
    return parseFlexibleDate(raw)
}

private fun ExpenseAnalysisRecord.expenseMonthLabelOrNull(): String? {
    expenseDateMillisOrNull()?.let { parsed ->
        return monthLabelFor(parsed)
    }

    val raw = fieldValueOrFallback("PaymentDateTime")
    if (raw.equals("Unknown", ignoreCase = true) || raw.isBlank()) return null
    return parseMonthFromLooseDate(raw)
}

private fun ExpenseAnalysisRecord.expenseYearLabelOrNull(): String? {
    expenseDateMillisOrNull()?.let { parsed ->
        return yearLabelFor(parsed)
    }

    val raw = fieldValueOrFallback("PaymentDateTime")
    if (raw.equals("Unknown", ignoreCase = true) || raw.isBlank()) return null
    return parseYearFromLooseDate(raw)
}

private fun ExpenseAnalysisRecord.sortDateMillis(): Long {
    return expenseDateMillisOrNull() ?: Long.MIN_VALUE
}

private fun parseFlexibleDate(value: String): Long? {
    val raw = value.trim()
    if (raw.isBlank()) return null

    val normalized = raw
        .replace(Regex("(?i)\\bon\\b"), " ")
        .replace(Regex("(?i)sept"), "Sep")
        .replace(Regex("\\s+"), " ")
        .trim()

    val formats = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.S",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "dd MMM yyyy, hh:mm a",
        "dd MMM yyyy, h:mma",
        "dd MMM yyyy, hh:mma",
        "dd MMM yyyy, hh:mm:ss a",
        "dd MMMM yyyy, hh:mm a",
        "dd MMMM yyyy, hh:mm:ss a",
        "hh:mm a dd MMM yyyy",
        "hh:mm:ss a dd MMM yyyy",
        "dd MMM yyyy hh:mm a",
        "dd MMMM yyyy hh:mm a",
        "dd-MM-yyyy HH:mm:ss",
        "dd/MM/yyyy HH:mm:ss",
        "dd/MM/yyyy hh:mm a",
        "MMM dd, yyyy hh:mm a",
    )

    formats.forEach { pattern ->
        val parsed = runCatching {
            SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                isLenient = false
            }.parse(normalized)
        }.getOrNull()

        if (parsed != null) return parsed.time
    }

    return normalized.toLongOrNull()
}

private fun parseMonthLabelToMillis(label: String): Long? {
    return runCatching {
        SimpleDateFormat("MMM yyyy", Locale.getDefault()).apply {
            isLenient = false
        }.parse(label)?.time
    }.getOrNull()
}

private fun parseMonthFromLooseDate(rawValue: String): String? {
    val normalized = rawValue
        .replace(Regex("(?i)sept"), "Sep")
        .replace(Regex("(?i)\\bon\\b"), " ")
        .replace(Regex("\\s+"), " ")

    val monthYearRegex = Regex("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\s+(20\\d{2})")
    val match = monthYearRegex.find(normalized) ?: return null

    val monthToken = match.groupValues[1].replaceFirstChar { it.uppercase() }
    val yearToken = match.groupValues[2]
    val monthAbbrev = monthToken.take(3)
    return "$monthAbbrev $yearToken"
}

private fun parseYearFromLooseDate(rawValue: String): String? {
    val yearRegex = Regex("(20\\d{2})")
    return yearRegex.find(rawValue)?.groupValues?.getOrNull(1)
}

private fun monthLabelFor(millis: Long): String {
    return SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(Date(millis))
}

private fun yearLabelFor(millis: Long): String {
    return Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR).toString()
}

private fun formatCurrency(value: Double): String {
    val rounded = (value * 100.0).roundToLong() / 100.0
    val formatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN"))
    return runCatching { formatter.format(rounded) }.getOrElse { "₹${"%.2f".format(Locale.US, rounded)}" }
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
