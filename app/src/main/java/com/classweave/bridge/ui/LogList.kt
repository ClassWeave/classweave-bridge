package com.classweave.bridge.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.classweave.bridge.model.AppLog
import com.classweave.bridge.model.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun LogLevel.color(): Color = when (this) {
    LogLevel.INFO -> Color(0xFF9E9E9E)
    LogLevel.WARN -> Color(0xFFFFA000)
    LogLevel.ERROR -> Color(0xFFD32F2F)
    LogLevel.SUCCESS -> Color(0xFF388E3C)
}

@Composable
fun LogList(logs: List<AppLog>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
    ) {
        items(logs, key = { "${it.timestamp}-${it.tag}-${it.message.hashCode()}" }) { log ->
            LogRow(log)
        }
    }
}

@Composable
private fun LogRow(log: AppLog) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = timeFormat.format(Date(log.timestamp)),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color.Gray
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = log.tag,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = log.level.color()
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = log.message,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = log.level.color(),
            modifier = Modifier.weight(1f)
        )
    }
}
