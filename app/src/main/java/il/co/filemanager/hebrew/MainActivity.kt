package il.co.filemanager.hebrew

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.documentfile.provider.DocumentFile
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FileManagerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerApp() {
    val context = LocalContext.current
    var rootUri by remember { mutableStateOf<Uri?>(null) }
    var currentUri by remember { mutableStateOf<Uri?>(null) }
    var entries by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("בחר תיקייה כדי להתחיל") }
    var folderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<DocumentFile?>(null) }

    fun refresh(uri: Uri?) {
        currentUri = uri
        val dir = uri?.let { DocumentFile.fromTreeUri(context, it) }
            ?: uri?.let { DocumentFile.fromSingleUri(context, it) }
        entries = dir?.listFiles()?.filter { showHidden || !it.name.orEmpty().startsWith(".") }
            ?.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() }) ?: emptyList()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            rootUri = uri
            refresh(uri)
            message = "התיקייה נפתחה בהצלחה"
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF315C9B))) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Column { Text("סייר הקבצים", fontWeight = FontWeight.Bold); Text("ניהול קבצים פרטי ואופליין", style = MaterialTheme.typography.labelSmall) } },
                        actions = {
                            IconButton(onClick = { showHidden = !showHidden; refresh(currentUri) }) {
                                Icon(Icons.Default.Visibility, contentDescription = "הצגת קבצים מוסתרים")
                            }
                            IconButton(onClick = { picker.launch(rootUri) }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "בחירת תיקייה")
                            }
                        }
                    )
                },
                floatingActionButton = {
                    if (currentUri != null) FloatingActionButton(onClick = { folderDialog = true }) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = "תיקייה חדשה")
                    }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { picker.launch(rootUri) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Storage, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("בחירת אחסון")
                        }
                        OutlinedButton(onClick = {
                            val parent = currentUri?.let { DocumentFile.fromTreeUri(context, it)?.parentFile }
                            if (parent != null) refresh(parent.uri)
                            else if (rootUri != null) refresh(rootUri)
                        }) { Icon(Icons.Default.ArrowUpward, contentDescription = "תיקיית אב") }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("חיפוש בתיקייה הנוכחית...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    Spacer(Modifier.height(8.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("האחסון שלי", fontWeight = FontWeight.Bold)
                            Text(if (currentUri == null) message else "פריטים: ${entries.size}", style = MaterialTheme.typography.bodySmall)
                            Text("הגישה לתיקיות ניתנת באישור מפורש של המשתמש", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("קבצים ותיקיות", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { refresh(currentUri) }) { Text("רענון") }
                    }
                    if (entries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (currentUri == null) "בחר תיקייה כדי להציג קבצים" else "התיקייה ריקה")
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(entries.filter { it.name.orEmpty().contains(query, ignoreCase = true) }, key = { it.uri.toString() }) { file ->
                                ListItem(
                                    headlineContent = { Text(file.name ?: "ללא שם", maxLines = 1) },
                                    supportingContent = { Text(if (file.isDirectory) "תיקייה" else "${formatSize(file.length())} • ${file.type ?: "קובץ"}", style = MaterialTheme.typography.bodySmall) },
                                    leadingContent = { Icon(if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile, contentDescription = null) },
                                    trailingContent = {
                                        IconButton(onClick = { selected = file }) { Icon(Icons.Default.MoreVert, contentDescription = "פעולות") }
                                    },
                                    modifier = Modifier.clickable {
                                        if (file.isDirectory) refresh(file.uri) else openFile(context, file)
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (folderDialog) AlertDialog(
        onDismissRequest = { folderDialog = false },
        title = { Text("יצירת תיקייה") },
        text = { OutlinedTextField(value = folderName, onValueChange = { folderName = it }, label = { Text("שם התיקייה") }) },
        confirmButton = {
            TextButton(onClick = {
                currentUri?.let { DocumentFile.fromTreeUri(context, it)?.createDirectory(folderName.trim()) }
                folderName = ""; folderDialog = false; refresh(currentUri)
            }) { Text("יצירה") }
        },
        dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("ביטול") } }
    )

    selected?.let { file ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(file.name ?: "פריט") },
            text = { Text("גודל: ${formatSize(file.length())}\nסוג: ${if (file.isDirectory) "תיקייה" else file.type ?: "לא ידוע"}") },
            confirmButton = {
                TextButton(onClick = {
                    file.delete(); selected = null; refresh(currentUri)
                }) { Text("מחיקה") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("סגירה") } }
        )
    }
}

private fun openFile(context: android.content.Context, file: DocumentFile) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(file.uri, file.type ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024L * 1024L * 1024L)} GB"
}
