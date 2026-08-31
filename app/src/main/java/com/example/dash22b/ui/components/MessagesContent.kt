package com.example.dash22b.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.dash22b.BuildConfig
import com.example.dash22b.data.ChatMessage
import com.example.dash22b.data.LogArchiver
import com.example.dash22b.data.LogSharing
import com.example.dash22b.di.LocalDtcRepository
import com.example.dash22b.obd.SsmDtcCode
import kotlinx.coroutines.launch
import timber.log.Timber

@Composable
fun MessagesContent() {
    val dtcRepository = LocalDtcRepository.current
    val messages by dtcRepository.messages.collectAsState()
    val isLoading by dtcRepository.isLoading.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Auto-read codes when first opening (if no messages yet)
    LaunchedEffect(Unit) {
        if (messages.isEmpty()) {
            dtcRepository.addCarMessage(buildWelcomeMessage())
        }
    }

    /**
     * Lists what is on disk, zips it, and opens the share sheet.
     *
     * The listing is posted before the zip starts so the reply names the files even if
     * building the archive then fails -- that list is what tells you whether the drive you
     * care about was actually recorded.
     */
    fun shareLogs() {
        val archiver = LogSharing.archiver(context)
        val logs = archiver.list()
        if (logs.isEmpty()) {
            dtcRepository.addCarMessage("No logs on disk yet. Monitor CSVs appear once the dashboard has polled the ECU.")
            return
        }

        dtcRepository.addCarMessage(describeLogs(logs))
        dtcRepository.setLoading(true)
        scope.launch {
            try {
                val archive = LogSharing.buildArchive(context, logs)
                dtcRepository.setLoading(false)
                dtcRepository.addCarMessage(
                    "Packed ${logs.size} ${fileWord(logs.size)} into ${archive.name} " +
                        "(${LogArchiver.formatBytes(archive.length())}). Pick where to upload it."
                )
                LogSharing.share(context, archive)
            } catch (e: Exception) {
                Timber.e(e, "Could not share logs")
                dtcRepository.setLoading(false)
                dtcRepository.addCarMessage("Could not build the log archive: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    /** Deletes every log on disk and reports what went away. */
    fun clearLogs() {
        val archiver = LogSharing.archiver(context)
        val before = archiver.list()
        if (before.isEmpty()) {
            dtcRepository.addCarMessage("No logs to clear.")
            return
        }

        val result = archiver.deleteAll()
        val freed = LogArchiver.formatBytes(result.freedBytes)
        val message = buildString {
            append("Cleared ${result.deleted} ${fileWord(result.deleted)}, freed $freed.")
            if (result.failed > 0) {
                append(" ${result.failed} could not be deleted -- check storage permissions.")
            }
        }
        dtcRepository.addCarMessage(message)
    }

    fun sendMessage() {
        val text = inputText.trim()
        if (text.isEmpty()) return
        inputText = ""

        dtcRepository.addUserMessage(text)

        when {
            text.equals("clear codes", ignoreCase = true) -> {
                dtcRepository.requestClearCodes()
            }
            text.equals("read codes", ignoreCase = true) ||
            text.equals("scan", ignoreCase = true) ||
            text.equals("check", ignoreCase = true) -> {
                dtcRepository.requestDtcRead()
            }
            text.equals("debug", ignoreCase = true) ||
            text.equals("dump", ignoreCase = true) ||
            text.equals("obd dump", ignoreCase = true) -> {
                dtcRepository.requestDiagnosticDump()
            }
            text.equals("check readiness", ignoreCase = true) ||
            text.equals("readiness", ignoreCase = true) ||
            text.equals("im readiness", ignoreCase = true) ||
            text.equals("smog", ignoreCase = true) -> {
                dtcRepository.requestReadiness()
            }
            text.equals("share logs", ignoreCase = true) ||
            text.equals("send logs", ignoreCase = true) ||
            text.equals("upload logs", ignoreCase = true) -> {
                shareLogs()
            }
            text.equals("clear logs", ignoreCase = true) ||
            text.equals("delete logs", ignoreCase = true) -> {
                clearLogs()
            }
            text.equals("help", ignoreCase = true) ||
            text.equals("?", ignoreCase = true) ||
            text.equals("commands", ignoreCase = true) -> {
                dtcRepository.addCarMessage(buildHelpMessage())
            }
            else -> {
                dtcRepository.addCarMessage("I don't understand \"$text\". Type \"help\" for the command list.")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Chat messages
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { message ->
                if (message.isFromUser) {
                    UserBubble(message.text)
                } else {
                    CarBubble(message.text, message.dtcCodes)
                }
            }

            // Loading indicator
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 44.dp),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF2D2D2D), shape = RoundedCornerShape(12.dp))
                                .padding(16.dp)
                        ) {
                            CircularProgressIndicator(
                                color = Color.Gray,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }

        // Input bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...", color = Color.Gray) },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color(0xFF2D2D2D),
                    unfocusedContainerColor = Color(0xFF2D2D2D),
                    cursorColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { sendMessage() })
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = { sendMessage() },
                enabled = inputText.isNotBlank() && !isLoading
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = if (inputText.isNotBlank() && !isLoading) Color.White else Color.Gray
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        SelectionContainer {
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .background(Color(0xFF1565C0), shape = RoundedCornerShape(12.dp))
                    .padding(12.dp)
            )
        }
    }
}

@Composable
private fun CarBubble(text: String, dtcCodes: List<SsmDtcCode> = emptyList()) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Engine avatar
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color(0xFF2D2D2D)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = "Engine",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(Color(0xFF2D2D2D), shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // DTC code details
            if (dtcCodes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                dtcCodes.forEach { dtc ->
                    DtcCodeRow(dtc)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun DtcCodeRow(dtc: SsmDtcCode) {
    Column {
        SelectionContainer {
            Column {
                Text(
                    text = dtc.code,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = dtc.description,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dtc.isTemporary && dtc.isMemorized) {
                StatusChip("CURRENT", Color(0xFFE53935))
                StatusChip("STORED", Color(0xFFE53935))
            } else if (dtc.isTemporary) {
                StatusChip("CURRENT", Color(0xFFFF9800))
            } else if (dtc.isMemorized) {
                StatusChip("STORED", Color(0xFF757575))
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/**
 * Welcome banner for the Messages tab. Leads with the build identity so the version
 * running on the phone can be read off the screen without a cable: the version string
 * comes from `git describe` at build time, so a release build shows its tag
 * ("0.3.0-fuel-calibration") while anything built past it is visibly marked
 * ("0.3.0-fuel-calibration-1-g4798128-dirty").
 */
private fun buildWelcomeMessage(): String {
    val build = if (BuildConfig.IS_RELEASE_BUILD) "release" else "dev"
    return buildString {
        append("dash22b ")
        append(BuildConfig.VERSION_NAME)
        append(" (")
        append(build)
        append(", ")
        append(BuildConfig.GIT_BRANCH)
        append(", ")
        append(BuildConfig.GIT_DATE)
        append(")\n")
        append(BuildConfig.WHATS_NEW)
        append("\n\n")
        append("Connected to ECU. Type \"help\" to see what I understand.")
    }
}

/**
 * The command list. Lives here rather than in the welcome banner so the banner stays short
 * as commands are added -- the banner is also the build-version readout, and that is what
 * you want to see first when you open the tab.
 */
private fun buildHelpMessage(): String = """
    Commands:

    read codes -- scan the ECU for trouble codes (also "scan", "check")
    clear codes -- clear stored trouble codes
    check readiness -- read the emissions readiness monitors over generic OBD-II
      and say whether it would clear a CA smog check (also "readiness", "smog")

    share logs -- zip the monitor CSVs and debug logs, then open the share sheet to
      upload them (also "send logs", "upload logs")
    clear logs -- delete every log on disk (also "delete logs")

    debug -- run a raw generic OBD-II capture (Mode 01/06/09) and save it for
      analysis off the car; pair it with "share logs" (also "dump")

    help -- this list
""".trimIndent()

/** "1 file" / "3 files" -- keeps the chat replies from reading like a status bar. */
private fun fileWord(count: Int): String = if (count == 1) "file" else "files"

/**
 * The chat listing of what is on disk: CSVs and debug logs grouped, newest first, each
 * with its size, plus a total. This is what the user reads before deciding to upload.
 */
private fun describeLogs(logs: List<LogArchiver.LogFile>): String {
    val total = logs.sumOf { it.bytes }
    return buildString {
        append("${logs.size} ${fileWord(logs.size)} on disk (${LogArchiver.formatBytes(total)}):")
        listOf(
            "Monitor CSVs" to LogArchiver.Kind.MONITOR_CSV,
            "Debug logs" to LogArchiver.Kind.DEBUG_LOG,
            "OBD dumps" to LogArchiver.Kind.OBD_DUMP
        ).forEach { (heading, kind) ->
            val group = logs.filter { it.kind == kind }
            if (group.isEmpty()) return@forEach
            append("\n\n")
            append(heading)
            append(':')
            group.forEach { log ->
                append("\n  ")
                append(log.name)
                append("  ")
                append(LogArchiver.formatBytes(log.bytes))
            }
        }
    }
}
