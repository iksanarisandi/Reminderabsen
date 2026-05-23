package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.*
import com.example.reminders.AlarmScheduler
import com.example.ui.MainViewModel
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start foreground service to keep reminders alive in background
        com.example.reminders.ReminderForegroundService.start(this)

        // Handle initial notification extra parameter if launched via push trigger
        intent?.getStringExtra("EXTRA_REMINDER_TYPE")?.let { type ->
            viewModel.showReminderDialog(type)
        }

        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle subsequent notifications while activity lives in background
        intent.getStringExtra("EXTRA_REMINDER_TYPE")?.let { type ->
            viewModel.showReminderDialog(type)
        }
    }
}

@Composable
fun MainAppScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val config by viewModel.scheduleConfig.collectAsStateWithLifecycle()
    val todayRecord by viewModel.todayRecord.collectAsStateWithLifecycle()
    val history by viewModel.attendanceHistory.collectAsStateWithLifecycle()
    val activeDialogType by viewModel.activeReminderDialog.collectAsStateWithLifecycle()

    var showEditConfigDialog by remember { mutableStateOf(false) }

    // Evaluates today's missed alert windows on each resume/foreground
    LaunchedEffect(Unit) {
        viewModel.triggerMissedEvaluation()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateDark),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (config?.onboarded != true) {
                // Onboarding Schedule Setup
                OnboardingSetupLayout(
                    onSave = { activeDays, inS, inE, inInt, outS, outE, outInt ->
                        viewModel.saveSchedule(activeDays, inS, inE, inInt, outS, outE, outInt)
                    }
                )
            } else {
                // Main Dashboard Layout
                DashboardLayout(
                    config = config,
                    todayRecord = todayRecord,
                    history = history,
                    onEditConfigClick = { showEditConfigDialog = true },
                    viewModel = viewModel
                )
            }

            // In-App Center Popup modal overlay
            if (activeDialogType != null) {
                FullscreenReminderPopup(
                    type = activeDialogType!!,
                    onDone = {
                        if (activeDialogType == "CLOCK_IN") {
                            viewModel.onClockInDone()
                        } else {
                            viewModel.onClockOutDone()
                        }
                    },
                    onSnooze = {
                        if (activeDialogType == "CLOCK_IN") {
                            viewModel.onClockInSnooze()
                        } else {
                            viewModel.onClockOutSnooze()
                        }
                    },
                    onDismiss = {
                        viewModel.dismissReminderDialog()
                    }
                )
            }

            // Schedule Editor Modal Dialog
            if (showEditConfigDialog && config != null) {
                ScheduleEditorDialog(
                    currentConfig = config!!,
                    onDismiss = { showEditConfigDialog = false },
                    onSave = { activeDays, inS, inE, inInt, outS, outE, outInt ->
                        viewModel.saveSchedule(activeDays, inS, inE, inInt, outS, outE, outInt)
                        showEditConfigDialog = false
                    }
                )
            }
        }
    }
}

// ==================== DASHBOARD COMPONENTS ====================

@Composable
fun DashboardLayout(
    config: ScheduleConfig?,
    todayRecord: AttendanceRecord?,
    history: List<AttendanceRecord>,
    onEditConfigClick: () -> Unit,
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    var isNotificationPermissionGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    var isBatteryOptimizationExempt by remember {
        mutableStateOf(
            valPowerExemption(context)
        )
    }

    val launcherNotifications = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        isNotificationPermissionGranted = isGranted
    }

    val systemClockText = rememberLiveSystemTime()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // App Identity Header
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Attendance",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = SlateTextLight
                    )
                    Text(
                        text = "REMINDER",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        color = MintPrimary,
                        letterSpacing = 1.sp
                    )
                }

                IconButton(
                    onClick = onEditConfigClick,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(SlateCard)
                        .size(48.dp)
                        .testTag("edit_settings_icon")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Edit Schedule",
                        tint = MintLight
                    )
                }
            }
        }

        // Live Digital Clock Showcase
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, SlateBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "DIGITAL CLOCK",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextLight,
                        letterSpacing = 2.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = systemClockText,
                        fontSize = 44.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(Date()),
                        fontSize = 14.sp,
                        color = SlateTextLight
                    )
                }
            }
        }

        // Permissions banner alert row if missing notifications or battery optimization
        if (Build.VERSION.SDK_INT >= 33 && !isNotificationPermissionGranted) {
            item {
                PermissionRequestBanner(
                    title = "Notifications Required",
                    desc = "Notification access is required to push timely clock-in/out reminders.",
                    buttonText = "Grant Permission",
                    onClick = {
                        launcherNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                )
            }
        }

        if (!isBatteryOptimizationExempt) {
            item {
                PermissionRequestBanner(
                    title = "Battery Saving Alert",
                    desc = "System battery saving might defer alarms. Please whitelist this utility to run in background.",
                    buttonText = "Whitelist App",
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                val intentFallback = Intent(Settings.ACTION_SETTINGS)
                                context.startActivity(intentFallback)
                            }
                        }
                    }
                )
            }
        }

        // Today's Status Cards Grid (Clock In / Clock Out)
        item {
            Text(
                text = "Today's Status",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextDark,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        item {
            if (config != null && todayRecord != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ReminderStatusCard(
                            title = "CLOCK IN",
                            status = todayRecord!!.clockInStatus,
                            timestamp = todayRecord!!.clockInTime,
                            startHour = config.clockInStartHour,
                            startMinute = config.clockInStartMinute,
                            endHour = config.clockInEndHour,
                            endMinute = config.clockInEndMinute,
                            onPrimaryClick = { viewModel.onClockInDone() },
                            onSnoozeClick = { viewModel.onClockInSnooze() },
                            snoozedUntil = todayRecord!!.snoozeInUntil,
                            interval = config.clockInInterval,
                            testTagPrefix = "clock_in"
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        ReminderStatusCard(
                            title = "CLOCK OUT",
                            status = todayRecord!!.clockOutStatus,
                            timestamp = todayRecord!!.clockOutTime,
                            startHour = config.clockOutStartHour,
                            startMinute = config.clockOutStartMinute,
                            endHour = config.clockOutEndHour,
                            endMinute = config.clockOutEndMinute,
                            onPrimaryClick = { viewModel.onClockOutDone() },
                            onSnoozeClick = { viewModel.onClockOutSnooze() },
                            snoozedUntil = todayRecord!!.snoozeOutUntil,
                            interval = config.clockOutInterval,
                            testTagPrefix = "clock_out"
                        )
                    }
                }
            }
        }

        // Configuration Card Detailed Info
        item {
            Text(
                text = "Active Schedule",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextDark,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        item {
            if (config != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SlateCard),
                    border = BorderStroke(1.dp, SlateBorder),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "",
                                tint = MintPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Active Days",
                                fontWeight = FontWeight.SemiBold,
                                color = SlateTextDark
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val daysOfWeek = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                            for (day in daysOfWeek) {
                                val isDayActive = config.isDayActive(day)
                                val initialLetter = day.take(1)
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (isDayActive) MintPrimary else SlateBorder)
                                        .border(
                                            width = if (isDayActive) 0.dp else 1.dp,
                                            color = SlateBorder,
                                            shape = CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = initialLetter,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDayActive) Color.Black else SlateTextLight
                                    )
                                }
                            }
                        }

                        Divider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = SlateBorder
                        )

                        // Timings Column
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Clock-In Window", fontSize = 12.sp, color = SlateTextLight)
                                Text(
                                    text = String.format("%02d:%02d – %02d:%02d", config.clockInStartHour, config.clockInStartMinute, config.clockInEndHour, config.clockInEndMinute),
                                    fontSize = 15.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("Interval: ${config.clockInInterval}m", fontSize = 11.sp, color = MintPrimary)
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text("Clock-Out Window", fontSize = 12.sp, color = SlateTextLight)
                                Text(
                                    text = String.format("%02d:%02d – %02d:%02d", config.clockOutStartHour, config.clockOutStartMinute, config.clockOutEndHour, config.clockOutEndMinute),
                                    fontSize = 15.sp,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("Interval: ${config.clockOutInterval}m", fontSize = 11.sp, color = MintPrimary)
                            }
                        }
                    }
                }
            }
        }

        // Attendance History logs header
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "History Log",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SlateTextDark
                )

                if (history.isNotEmpty()) {
                    Text(
                        text = "Clear All",
                        fontSize = 13.sp,
                        color = CoralAccent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clickable { viewModel.clearAllHistory() }
                            .padding(4.dp)
                    )
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Empty History",
                        tint = SlateBorder,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No history records found",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SlateTextLight,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Once alarms trigger or clock-ins occur, daily log snapshots appear here.",
                        fontSize = 12.sp,
                        color = SlateTextLight.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            items(history) { log ->
                HistoryRecordItem(log)
            }
        }
    }
}

// Check Power battery saver settings whitelisting flag
private fun valPowerExemption(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }
    return true
}

@Composable
fun PermissionRequestBanner(
    title: String,
    desc: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, CoralAccent.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = AmberAccent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = SlateTextLight
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(containerColor = SlateBorder),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("grant_permission_button")
            ) {
                Text(
                    text = buttonText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MintPrimary
                )
            }
        }
    }
}

@Composable
fun ReminderStatusCard(
    title: String,
    status: String,
    timestamp: Long?,
    startHour: Int,
    startMinute: Int,
    endHour: Int,
    endMinute: Int,
    onPrimaryClick: () -> Unit,
    onSnoozeClick: () -> Unit,
    snoozedUntil: Long?,
    interval: Int,
    testTagPrefix: String
) {
    val statusColor = when (status) {
        "DONE" -> MintPrimary
        "MISSED" -> CoralAccent
        "PENDING" -> AmberAccent
        else -> SlateTextLight
    }

    val statusText = when (status) {
        "DONE" -> "Done"
        "MISSED" -> "Missed"
        "PENDING" -> "Pending"
        else -> "Inactive"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${testTagPrefix}_status_card"),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, SlateBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextLight
            )
            Spacer(modifier = Modifier.height(4.dp))
            
            // Highlight Pill
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(statusColor.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Time range details / actual punch timing
            if (status == "DONE" && timestamp != null) {
                Text(
                    text = "Punch Time:",
                    fontSize = 10.sp,
                    color = SlateTextLight
                )
                Text(
                    text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp)),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            } else {
                Text(
                    text = "Alert Window:",
                    fontSize = 10.sp,
                    color = SlateTextLight
                )
                Text(
                    text = String.format("%02d:%02d – %02d:%02d", startHour, startMinute, endHour, endMinute),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // Checks dynamic snooze state
            if (status == "PENDING" && snoozedUntil != null && snoozedUntil > System.currentTimeMillis()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Snoozed",
                        tint = AmberAccent,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Snoozed until: " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(snoozedUntil)),
                        fontSize = 10.sp,
                        color = AmberAccent
                    )
                }
            }

            if (status == "PENDING") {
                Spacer(modifier = Modifier.height(12.dp))
                // Quick Punch Button in-app
                Button(
                    onClick = onPrimaryClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MintPrimary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .testTag("${testTagPrefix}_punch_done_button")
                ) {
                    Text(
                        text = "Clock In",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // Snooze 15 minutes quick action
                OutlinedButton(
                    onClick = onSnoozeClick,
                    border = BorderStroke(1.dp, SlateBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SlateTextLight),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                        .testTag("${testTagPrefix}_snooze_button")
                ) {
                    Text(
                        text = "Snooze ${interval}m",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SlateTextLight
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryRecordItem(record: AttendanceRecord) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("history_item_${record.date}"),
        colors = CardDefaults.cardColors(containerColor = SlateCard),
        border = BorderStroke(1.dp, SlateBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.DateRange,
                        contentDescription = null,
                        tint = SlateTextLight,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = reformatDate(record.date),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                val dayOfWeek = try {
                    val sdfIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    val date = sdfIn.parse(record.date)
                    val sdfOut = SimpleDateFormat("EEEE", Locale.US)
                    if (date != null) sdfOut.format(date) else ""
                } catch (e: Exception) { "" }

                Text(
                    text = dayOfWeek,
                    fontSize = 12.sp,
                    color = SlateTextLight,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // In Row
                Box(modifier = Modifier.weight(1.0f)) {
                    HistoryStatusRow(label = "Clock In", status = record.clockInStatus, time = record.clockInTime)
                }
                // Out Row
                Box(modifier = Modifier.weight(1.0f)) {
                    HistoryStatusRow(label = "Clock Out", status = record.clockOutStatus, time = record.clockOutTime)
                }
            }
        }
    }
}

@Composable
fun HistoryStatusRow(label: String, status: String, time: Long?) {
    val color = when (status) {
        "DONE" -> MintPrimary
        "MISSED" -> CoralAccent
        "PENDING" -> AmberAccent
        else -> SlateTextLight.copy(alpha = 0.5f)
    }

    val text = when (status) {
        "DONE" -> if (time != null) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(time)) else "Done"
        "MISSED" -> "Missed"
        "PENDING" -> "Pending"
        else -> "No Alert"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlateDark, RoundedCornerShape(8.dp))
            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = SlateTextLight,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private fun reformatDate(dateStr: String): String {
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val formatter = SimpleDateFormat("d MMM yyyy", Locale.US)
        val date = parser.parse(dateStr)
        if (date != null) formatter.format(date) else dateStr
    } catch (e: Exception) {
        dateStr
    }
}

// ==================== TIME UP/DOWN CUSTOM SELECTOR ====================

@Composable
fun TimeUpDownSelector(
    title: String,
    hour: Int,
    minute: Int,
    onTimeChanged: (Int, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SlateDark, RoundedCornerShape(12.dp))
            .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MintLight
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Hours Column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = {
                        val newHour = (hour + 1) % 24
                        onTimeChanged(newHour, minute)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "Hour Up", tint = SlateTextDark)
                }
                
                Text(
                    text = String.format("%02d", hour),
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )

                IconButton(
                    onClick = {
                        val newHour = if (hour - 1 < 0) 23 else hour - 1
                        onTimeChanged(newHour, minute)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Hour Down", tint = SlateTextDark)
                }
            }

            Text(
                text = ":",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = SlateTextLight,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            // Minutes Column
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = {
                        val newMin = (minute + 5) % 60
                        onTimeChanged(hour, newMin)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowUp, contentDescription = "Minute Up", tint = SlateTextDark)
                }
                
                Text(
                    text = String.format("%02d", minute),
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )

                IconButton(
                    onClick = {
                        val newMin = if (minute - 5 < 0) 55 else minute - 5
                        onTimeChanged(hour, newMin)
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(imageVector = Icons.Default.KeyboardArrowDown, contentDescription = "Minute Down", tint = SlateTextDark)
                }
            }
        }
    }
}

// ==================== ONBOARDING AND SCHEDULE SETUP SCREEN ====================

@Composable
fun OnboardingSetupLayout(
    onSave: (String, String, String, Int, String, String, Int) -> Unit
) {
    var selectedDays by remember { mutableStateOf(setOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")) }

    var clockInHourStart by remember { mutableStateOf(6) }
    var clockInMinuteStart by remember { mutableStateOf(0) }
    var clockInHourEnd by remember { mutableStateOf(14) }
    var clockInMinuteEnd by remember { mutableStateOf(0) }
    var clockInInterval by remember { mutableStateOf(5) }

    var clockOutHourStart by remember { mutableStateOf(16) }
    var clockOutMinuteStart by remember { mutableStateOf(0) }
    var clockOutHourEnd by remember { mutableStateOf(23) }
    var clockOutMinuteEnd by remember { mutableStateOf(0) }
    var clockOutInterval by remember { mutableStateOf(5) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Welcome",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MintLight,
                letterSpacing = 2.sp
            )
            Text(
                text = "Attendance Alerts",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Configure your shift timings so this utility can issue persistent alerts for you.",
                fontSize = 13.sp,
                color = SlateTextLight,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )
        }

        // Active days section
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, SlateBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "SELECT WORKDAYS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextLight,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val allDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                    
                    allDays.forEach { day ->
                        val isSelected = selectedDays.contains(day)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedDays = if (isSelected) selectedDays - day else selectedDays + day
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = day, color = Color.White, fontSize = 14.sp)
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = {
                                    selectedDays = if (isSelected) selectedDays - day else selectedDays + day
                                },
                                colors = CheckboxDefaults.colors(checkedColor = MintPrimary, uncheckedColor = SlateBorder)
                            )
                        }
                    }
                }
            }
        }

        // Clock In Timings card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, SlateBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "CLOCK-IN WINDOW SETUP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextLight,
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            TimeUpDownSelector(
                                title = "WINDOW START",
                                hour = clockInHourStart,
                                minute = clockInMinuteStart,
                                onTimeChanged = { h, m ->
                                    clockInHourStart = h
                                    clockInMinuteStart = m
                                }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TimeUpDownSelector(
                                title = "WINDOW END",
                                hour = clockInHourEnd,
                                minute = clockInMinuteEnd,
                                onTimeChanged = { h, m ->
                                    clockInHourEnd = h
                                    clockInMinuteEnd = m
                                }
                            )
                        }
                    }

                    // Alert Interval picker
                    Column {
                        Text(
                            text = "Reminder Interval: $clockInInterval minutes",
                            fontSize = 12.sp,
                            color = SlateTextDark,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(5, 10, 15, 30).forEach { mins ->
                                val isChosen = clockInInterval == mins
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isChosen) MintPrimary else SlateBorder)
                                        .clickable { clockInInterval = mins }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${mins}M",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isChosen) Color.Black else SlateTextLight
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Clock Out Timings card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SlateCard),
                border = BorderStroke(1.dp, SlateBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "CLOCK-OUT WINDOW SETUP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SlateTextLight,
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            TimeUpDownSelector(
                                title = "WINDOW START",
                                hour = clockOutHourStart,
                                minute = clockOutMinuteStart,
                                onTimeChanged = { h, m ->
                                    clockOutHourStart = h
                                    clockOutMinuteStart = m
                                }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            TimeUpDownSelector(
                                title = "WINDOW END",
                                hour = clockOutHourEnd,
                                minute = clockOutMinuteEnd,
                                onTimeChanged = { h, m ->
                                    clockOutHourEnd = h
                                    clockOutMinuteEnd = m
                                }
                            )
                        }
                    }

                    // Alert Interval picker
                    Column {
                        Text(
                            text = "Reminder Interval: $clockOutInterval minutes",
                            fontSize = 12.sp,
                            color = SlateTextDark,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(10, 15, 30, 60).forEach { mins ->
                                val isChosen = clockOutInterval == mins
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isChosen) MintPrimary else SlateBorder)
                                        .clickable { clockOutInterval = mins }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${mins}M",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isChosen) Color.Black else SlateTextLight
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Save entry button
        item {
            Button(
                onClick = {
                    val daysCsv = selectedDays.joinToString(",")
                    onSave(
                        daysCsv,
                        String.format("%02d:%02d", clockInHourStart, clockInMinuteStart),
                        String.format("%02d:%02d", clockInHourEnd, clockInMinuteEnd),
                        clockInInterval,
                        String.format("%02d:%02d", clockOutHourStart, clockOutMinuteStart),
                        String.format("%02d:%02d", clockOutHourEnd, clockOutMinuteEnd),
                        clockOutInterval
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("save_onboarding_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MintPrimary),
                shape = RoundedCornerShape(12.dp),
                enabled = selectedDays.isNotEmpty()
            ) {
                Text(
                    text = "SAVE & REGISTER SCHEDULE",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ==================== EDIT CONFIG DIALOG ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditorDialog(
    currentConfig: ScheduleConfig,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int, String, String, Int) -> Unit
) {
    var selectedDays by remember { mutableStateOf(currentConfig.activeDaysList.toSet()) }

    var clockInHourStart by remember { mutableStateOf(currentConfig.clockInStartHour) }
    var clockInMinuteStart by remember { mutableStateOf(currentConfig.clockInStartMinute) }
    var clockInHourEnd by remember { mutableStateOf(currentConfig.clockInEndHour) }
    var clockInMinuteEnd by remember { mutableStateOf(currentConfig.clockInEndMinute) }
    var clockInInterval by remember { mutableStateOf(currentConfig.clockInInterval) }

    var clockOutHourStart by remember { mutableStateOf(currentConfig.clockOutStartHour) }
    var clockOutMinuteStart by remember { mutableStateOf(currentConfig.clockOutStartMinute) }
    var clockOutHourEnd by remember { mutableStateOf(currentConfig.clockOutEndHour) }
    var clockOutMinuteEnd by remember { mutableStateOf(currentConfig.clockOutEndMinute) }
    var clockOutInterval by remember { mutableStateOf(currentConfig.clockOutInterval) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(SlateDark),
            color = SlateDark
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header tool row
                TopAppBar(
                    title = { Text("Edit Alerts Schedule", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SlateCard),
                    actions = {
                        TextButton(
                            onClick = {
                                val daysCsv = selectedDays.joinToString(",")
                                onSave(
                                    daysCsv,
                                    String.format("%02d:%02d", clockInHourStart, clockInMinuteStart),
                                    String.format("%02d:%02d", clockInHourEnd, clockInMinuteEnd),
                                    clockInInterval,
                                    String.format("%02d:%02d", clockOutHourStart, clockOutMinuteStart),
                                    String.format("%02d:%02d", clockOutHourEnd, clockOutMinuteEnd),
                                    clockOutInterval
                                )
                            },
                            enabled = selectedDays.isNotEmpty(),
                            modifier = Modifier.testTag("submit_schedule_edit")
                        ) {
                            Text("SAVE", color = if (selectedDays.isNotEmpty()) MintPrimary else SlateTextLight, fontWeight = FontWeight.Bold)
                        }
                    }
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(bottom = 32.dp)
                ) {
                    // Active work days
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            border = BorderStroke(1.dp, SlateBorder),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "SELECT WORKDAYS",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateTextLight,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                val allDays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

                                allDays.forEach { day ->
                                    val isSelected = selectedDays.contains(day)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedDays = if (isSelected) selectedDays - day else selectedDays + day
                                            }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = day, color = Color.White, fontSize = 14.sp)
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = {
                                                selectedDays = if (isSelected) selectedDays - day else selectedDays + day
                                            },
                                            colors = CheckboxDefaults.colors(checkedColor = MintPrimary, uncheckedColor = SlateBorder)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Clock In
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            border = BorderStroke(1.dp, SlateBorder),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "CLOCK-IN WINDOW SETUP",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateTextLight,
                                    letterSpacing = 1.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        TimeUpDownSelector(
                                            title = "WINDOW START",
                                            hour = clockInHourStart,
                                            minute = clockInMinuteStart,
                                            onTimeChanged = { h, m ->
                                                clockInHourStart = h
                                                clockInMinuteStart = m
                                            }
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        TimeUpDownSelector(
                                            title = "WINDOW END",
                                            hour = clockInHourEnd,
                                            minute = clockInMinuteEnd,
                                            onTimeChanged = { h, m ->
                                                clockInHourEnd = h
                                                clockInMinuteEnd = m
                                            }
                                        )
                                    }
                                }

                                // Alert Interval picker
                                Column {
                                    Text(
                                        text = "Reminder Interval: $clockInInterval minutes",
                                        fontSize = 12.sp,
                                        color = SlateTextDark,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(5, 10, 15, 30).forEach { mins ->
                                            val isChosen = clockInInterval == mins
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isChosen) MintPrimary else SlateBorder)
                                                    .clickable { clockInInterval = mins }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${mins}M",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isChosen) Color.Black else SlateTextLight
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Clock Out Setup
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SlateCard),
                            border = BorderStroke(1.dp, SlateBorder),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "CLOCK-OUT WINDOW SETUP",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SlateTextLight,
                                    letterSpacing = 1.sp
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        TimeUpDownSelector(
                                            title = "WINDOW START",
                                            hour = clockOutHourStart,
                                            minute = clockOutMinuteStart,
                                            onTimeChanged = { h, m ->
                                                clockOutHourStart = h
                                                clockOutMinuteStart = m
                                            }
                                        )
                                    }
                                    Box(modifier = Modifier.weight(1f)) {
                                        TimeUpDownSelector(
                                            title = "WINDOW END",
                                            hour = clockOutHourEnd,
                                            minute = clockOutMinuteEnd,
                                            onTimeChanged = { h, m ->
                                                clockOutHourEnd = h
                                                clockOutMinuteEnd = m
                                            }
                                        )
                                    }
                                }

                                // Alert Interval picker
                                Column {
                                    Text(
                                        text = "Reminder Interval: $clockOutInterval minutes",
                                        fontSize = 12.sp,
                                        color = SlateTextDark,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(10, 15, 30, 60).forEach { mins ->
                                            val isChosen = clockOutInterval == mins
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isChosen) MintPrimary else SlateBorder)
                                                    .clickable { clockOutInterval = mins }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${mins}M",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isChosen) Color.Black else SlateTextLight
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
    }
}

// ==================== FULL-SCREEN POPUP REMINDER OVERLAY ====================

@Composable
fun FullscreenReminderPopup(
    type: String,
    onDone: () -> Unit,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit
) {
    val liveTimeStr = rememberLiveSystemTime()
    val isClockIn = type == "CLOCK_IN"

    Dialog(
        onDismissRequest = { /* Prevent dismissal by clicking outside entirely, adhering to user's specification */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SlateDark.copy(alpha = 0.95f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(SlateCard)
                    .border(2.dp, if (isClockIn) MintPrimary else AmberAccent, RoundedCornerShape(24.dp))
                    .padding(32.dp)
                    .testTag("fullscreen_popup_reminder"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // High emphasis logo ticker
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(if (isClockIn) MintPrimary.copy(alpha = 0.15f) else AmberAccent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isClockIn) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isClockIn) MintPrimary else AmberAccent,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = if (isClockIn) "Reminder: Clock In" else "Reminder: Clock Out",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    letterSpacing = 0.5.sp
                )
                
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "CURRENT TIME",
                    fontSize = 11.sp,
                    color = SlateTextLight,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                Text(
                    text = liveTimeStr,
                    fontSize = 38.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (isClockIn) {
                        "Have you finished your clock-in protocol today?"
                    } else {
                        "Are you wrapping up? Remember to check out your daily logged shift!"
                    },
                    fontSize = 14.sp,
                    color = SlateTextLight,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Action controls block
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = MintPrimary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("popup_sudah_absen_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = Color.Black)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sudah Absen",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onSnooze,
                    border = BorderStroke(1.5.dp, SlateBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("popup_belum_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(imageVector = Icons.Default.Notifications, contentDescription = null, tint = SlateTextLight)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Belum",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = SlateTextLight
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("popup_ignore_button")
                ) {
                    Text(
                        text = "Ignore for now",
                        fontSize = 13.sp,
                        color = SlateTextLight.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// Real-time ticking time utility hook
@Composable
fun rememberLiveSystemTime(): String {
    var timeStr by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            timeStr = sdf.format(Date())
            delay(1000)
        }
    }
    return timeStr
}
