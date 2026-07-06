package com.clearcall.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.clearcall.core.Prefs
import com.clearcall.net.ApiClient
import com.clearcall.net.CallHistoryItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val apiClient = remember { ApiClient(prefs) }

    var calls by remember { mutableStateOf<List<CallHistoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        calls = runCatching { apiClient.listCalls() }.getOrDefault(emptyList())
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            calls.isEmpty() -> Box(Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                Text("No calls yet")
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(inner)) {
                items(calls, key = { it.id }) { call ->
                    ListItem(
                        headlineContent = { Text(call.peerName) },
                        supportingContent = { Text(statusLine(call)) },
                        leadingContent = {
                            Icon(
                                if (call.direction == "outgoing") Icons.Filled.CallMade else Icons.Filled.CallReceived,
                                contentDescription = call.direction,
                            )
                        },
                        trailingContent = { Text(call.createdAt.substringAfter(' ')) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private fun statusLine(call: CallHistoryItem): String {
    val direction = if (call.direction == "outgoing") "Outgoing" else "Incoming"
    return "$direction · ${call.status}"
}
