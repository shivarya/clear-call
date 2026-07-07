package com.clearcall.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
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
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clearcall.auth.signOut
import com.clearcall.call.CallManager
import com.clearcall.call.CallState
import com.clearcall.core.Prefs
import com.clearcall.net.ApiClient
import com.clearcall.net.UserSummary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenScan: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    prefillCode: String? = null,
    onPrefillConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val apiClient = remember { ApiClient(prefs) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var contacts by remember { mutableStateOf<List<UserSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var addCode by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    // Gallery decode: pick an image containing a QR, decode it, prefill the code field.
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val decoded = QrDecoder.decodeUri(context, uri)
                if (decoded != null) addCode = decoded.uppercase()
                else error = "No QR code found in that image"
            }
        }
    }

    // A code scanned via the camera screen arrives here as prefillCode; load it once.
    LaunchedEffect(prefillCode) {
        prefillCode?.let { addCode = it.uppercase(); onPrefillConsumed() }
    }

    // Placing a call from here can fail before any CallScreen shows (e.g. the callee has no
    // registered device / isn't signed in). That error lands on CallState.errorMessage, which
    // only CallScreen renders — so surface it here as a toast, otherwise the tap looks like a
    // no-op. Clear it after showing so it doesn't re-fire.
    val callError by CallState.errorMessage.collectAsState()
    LaunchedEffect(callError) {
        callError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            CallState.setError(null)
        }
    }

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
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            prefs.userCode ?: "—",
                            style = MaterialTheme.typography.headlineMedium,
                            letterSpacing = 4.sp,
                        )
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(prefs.userCode.orEmpty())) },
                            enabled = prefs.userCode != null,
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy code")
                        }
                    }
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
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        OutlinedTextField(
                            value = addCode,
                            onValueChange = { addCode = it.uppercase() },
                            label = { Text("Their code") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onOpenScan) {
                            Icon(Icons.Filled.QrCodeScanner, contentDescription = "Scan QR code")
                        }
                        IconButton(
                            onClick = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                        ) {
                            Icon(Icons.Filled.PhotoLibrary, contentDescription = "Scan QR from gallery")
                        }
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
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
