package com.clearcall.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearcall.auth.signOut
import com.clearcall.call.CallManager
import com.clearcall.core.Prefs
import com.clearcall.net.ApiClient
import com.clearcall.net.UserSummary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val apiClient = remember { ApiClient(prefs) }
    val scope = rememberCoroutineScope()

    var contacts by remember { mutableStateOf<List<UserSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var addCode by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            try {
                contacts = apiClient.listContacts()
            } catch (e: Exception) {
                error = e.message
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ClearCall") },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Filled.History, contentDescription = "Call history")
                    }
                    IconButton(onClick = { signOut(prefs) }) {
                        Icon(Icons.Filled.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(20.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Your code", fontWeight = FontWeight.SemiBold)
                    Text(
                        prefs.userCode ?: "—",
                        style = MaterialTheme.typography.headlineMedium,
                        letterSpacing = 4.sp,
                    )
                    prefs.userCode?.let { code ->
                        val bitmap = remember(code) { generateQrBitmap(code) }
                        bitmap?.let {
                            Image(it.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.size(160.dp))
                        }
                    }
                    Text(
                        "Share this so friends can add you",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add a friend", fontWeight = FontWeight.SemiBold)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = addCode,
                            onValueChange = { addCode = it.uppercase() },
                            label = { Text("Their code") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            enabled = addCode.isNotBlank() && !adding,
                            onClick = {
                                adding = true
                                error = null
                                scope.launch {
                                    try {
                                        apiClient.addContact(addCode.trim())
                                        addCode = ""
                                        refresh()
                                    } catch (e: Exception) {
                                        error = e.message
                                    } finally {
                                        adding = false
                                    }
                                }
                            },
                        ) { Text("Add") }
                    }
                }
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Text("Contacts", fontWeight = FontWeight.SemiBold)
            when {
                loading -> CircularProgressIndicator()
                contacts.isEmpty() -> Text(
                    "No contacts yet — add one by code above.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(contacts, key = { it.id }) { contact ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(contact.name, fontWeight = FontWeight.Medium)
                                    Text(contact.email, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { CallManager.placeOutgoingCall(contact.id, contact.name) }) {
                                    Icon(Icons.Filled.Call, contentDescription = "Call ${contact.name}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
