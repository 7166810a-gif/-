package il.co.filemanager.hebrew

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.media.MediaPlayer
import android.widget.ImageView
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink = Color(0xFF17243B)
private val Muted = Color(0xFF718096)
private val Brand = Color(0xFF315C9B)
private val Canvas = Color(0xFFF4F7FC)
private val Line = Color(0xFFE7ECF3)
private val Mint = Color(0xFFE5F5EF)
private val SoftBlue = Color(0xFFE9F0FC)

private data class FileEntry(
    val document: DocumentFile,
    val name: String,
    val isDirectory: Boolean,
    val mimeType: String?,
    val lengthBytes: Long,
    val modifiedAt: Long
)

private fun snapshotFile(file: DocumentFile): FileEntry {
    val directory = file.isDirectory
    return FileEntry(
        document = file,
        name = file.name ?: "ללא שם",
        isDirectory = directory,
        mimeType = file.type,
        lengthBytes = if (directory) 0L else file.length().coerceAtLeast(0L),
        modifiedAt = file.lastModified()
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(244, 247, 252)
        window.navigationBarColor = android.graphics.Color.rgb(244, 247, 252)
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        setContent { FileManagerApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerApp() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("file_manager", Context.MODE_PRIVATE) }
    val pathStack = remember { mutableStateListOf<DocumentFile>() }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("הכול") }
    var showHidden by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf("name") }
    val listRequestId = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var selectedFile by remember { mutableStateOf<DocumentFile?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var createFolderDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var folderText by remember { mutableStateOf("") }
    var transferSource by remember { mutableStateOf<DocumentFile?>(null) }
    var transferMode by remember { mutableStateOf("copy") }
    var progressMessage by remember { mutableStateOf<String?>(null) }
    var previewFile by remember { mutableStateOf<DocumentFile?>(null) }
    var zipArchive by remember { mutableStateOf<DocumentFile?>(null) }
    var zipEntries by remember { mutableStateOf<List<String>>(emptyList()) }
    var zipLoading by remember { mutableStateOf(false) }
    var zipError by remember { mutableStateOf<String?>(null) }
    var showZipDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        val directory = pathStack.lastOrNull()
        if (directory == null) {
            listRequestId.incrementAndGet()
            entries = emptyList()
            isLoading = false
            return
        }
        val requestId = listRequestId.incrementAndGet()
        val includeHidden = showHidden
        isLoading = true
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    directory.listFiles()
                        .asSequence()
                        .filter { includeHidden || !it.name.orEmpty().startsWith(".") }
                        .map(::snapshotFile)
                        .sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
                        .toList()
                }
                if (requestId == listRequestId.get() && pathStack.lastOrNull()?.uri == directory.uri) {
                    entries = loaded
                }
            } catch (error: Exception) {
                if (requestId == listRequestId.get()) {
                    Toast.makeText(context, error.message ?: "לא ניתן לטעון את התיקייה", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (requestId == listRequestId.get()) isLoading = false
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val root = DocumentFile.fromTreeUri(context, uri)
                if (root != null) {
                    pathStack.clear()
                    pathStack.add(root)
                    preferences.edit().putString("root_uri", uri.toString()).apply()
                    searchText = ""
                    selectedCategory = "הכול"
                    reload()
                }
            }.onFailure {
                Toast.makeText(context, "לא ניתן לפתוח את התיקייה שנבחרה", Toast.LENGTH_LONG).show()
            }
        }
    }

    val transferPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val source = transferSource
        if (uri != null && source != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }.onFailure {
                Toast.makeText(context, "לא ניתן לקבל הרשאת כתיבה ליעד", Toast.LENGTH_LONG).show()
            }
            scope.launch {
                progressMessage = if (transferMode == "move") "מעביר קבצים..." else "מעתיק קבצים..."
                try {
                    withContext(Dispatchers.IO) {
                        val destination = DocumentFile.fromTreeUri(context, uri)
                            ?: throw IllegalStateException("תיקיית היעד אינה נגישה")
                        copyDocumentTree(context, source, destination) { current ->
                            scope.launch { progressMessage = current }
                        }
                        if (transferMode == "move" && !source.delete()) {
                            throw IllegalStateException("הקובץ הועתק אך לא ניתן למחוק את המקור")
                        }
                    }
                    Toast.makeText(context, if (transferMode == "move") "ההעברה הושלמה" else "ההעתקה הושלמה", Toast.LENGTH_LONG).show()
                } catch (error: Exception) {
                    Toast.makeText(context, error.message ?: "הפעולה נכשלה", Toast.LENGTH_LONG).show()
                } finally {
                    progressMessage = null
                    transferSource = null
                    reload()
                }
            }
        } else {
            transferSource = null
        }
    }

    LaunchedEffect(Unit) {
        val saved = preferences.getString("root_uri", null)
        if (saved != null) {
            runCatching {
                val root = DocumentFile.fromTreeUri(context, Uri.parse(saved))
                if (root != null) {
                    pathStack.clear()
                    pathStack.add(root)
                    reload()
                }
            }
        }
    }

    val currentDirectory = pathStack.lastOrNull()
    var visibleEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var appliedSearchText by remember { mutableStateOf("") }

    LaunchedEffect(searchText) {
        delay(180)
        appliedSearchText = searchText
    }

    LaunchedEffect(entries, appliedSearchText, selectedCategory, sortMode) {
        val source = entries
        val query = appliedSearchText.trim()
        val category = selectedCategory
        val sorting = sortMode
        visibleEntries = withContext(Dispatchers.Default) {
            val filtered = source.filter { file ->
                val extension = file.name.substringAfterLast('.', "").lowercase()
                val nameMatches = query.isEmpty() || file.name.contains(query, ignoreCase = true)
                val categoryMatches = when (category) {
                    "תיקיות" -> file.isDirectory
                    "תמונות" -> extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")
                    "וידאו" -> extension in setOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
                    "שמע" -> extension in setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")
                    "מסמכים" -> extension in setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "md")
                    "ארכיונים" -> extension in setOf("zip", "rar", "7z", "tar", "gz")
                    else -> true
                }
                nameMatches && categoryMatches
            }
            when (sorting) {
                "size" -> filtered.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenByDescending { it.lengthBytes })
                "modified" -> filtered.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenByDescending { it.modifiedAt })
                else -> filtered.sortedWith(compareBy<FileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
            }
        }
    }

    fun openInside(file: DocumentFile) {
        val extension = file.name.orEmpty().substringAfterLast('.', "").lowercase()
        val images = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")
        val videos = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
        val audio = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")
        when {
            extension in images || extension in videos || extension in audio -> previewFile = file
            extension == "zip" -> {
                zipArchive = file
                showZipDialog = true
                zipLoading = true
                zipError = null
                scope.launch {
                    try {
                        zipEntries = withContext(Dispatchers.IO) { listZipEntries(context, file) }
                    } catch (error: Exception) {
                        zipError = error.message ?: "לא ניתן לקרוא את הארכיון"
                    } finally {
                        zipLoading = false
                    }
                }
            }
            else -> openFile(context, file)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = Brand,
                onPrimary = Color.White,
                secondary = Color(0xFF16856B),
                background = Canvas,
                surface = Color.White,
                onSurface = Ink,
                onSurfaceVariant = Muted
            ),
            typography = Typography(
                headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                titleMedium = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                bodyMedium = MaterialTheme.typography.bodyMedium.copy(color = Ink)
            )
        ) {
            Scaffold(
                containerColor = Canvas,
                topBar = {
                    TopAppBar(
                        title = {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text("סייר הקבצים", color = Ink, fontWeight = FontWeight.Bold)
                                Text(
                                    if (currentDirectory == null) "הקבצים שלך, מסודרים ונגישים"
                                    else currentDirectory.name ?: "התיקייה שלי",
                                    color = Muted,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        navigationIcon = {
                            if (currentDirectory != null) {
                                IconButton(onClick = {
                                    if (pathStack.size > 1) {
                                        pathStack.removeAt(pathStack.lastIndex)
                                        searchText = ""
                                        reload()
                                    } else {
                                        pathStack.clear()
                                        entries = emptyList()
                                    }
                                }) {
                                    Icon(Icons.Rounded.ArrowForward, contentDescription = "חזרה", tint = Ink)
                                }
                            } else {
                                Box(
                                    Modifier.padding(start = 12.dp).size(42.dp)
                                        .background(SoftBlue, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.Folder, contentDescription = null, tint = Brand)
                                }
                            }
                        },
                        actions = {
                            if (currentDirectory != null) {
                                IconButton(onClick = { showHidden = !showHidden; reload() }) {
                                    Icon(
                                        if (showHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                        contentDescription = "קבצים מוסתרים",
                                        tint = if (showHidden) Brand else Muted
                                    )
                                }
                                IconButton(onClick = {
                                    sortMode = when (sortMode) {
                                        "name" -> "size"
                                        "size" -> "modified"
                                        else -> "name"
                                    }
                                    val label = when (sortMode) {
                                        "size" -> "מיון לפי גודל"
                                        "modified" -> "מיון לפי תאריך"
                                        else -> "מיון לפי שם"
                                    }
                                    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(
                                        Icons.Rounded.Sort,
                                        contentDescription = when (sortMode) {
                                            "size" -> "מיון לפי גודל"
                                            "modified" -> "מיון לפי תאריך"
                                            else -> "מיון לפי שם"
                                        },
                                        tint = Ink
                                    )
                                }
                            } else {
                                IconButton(onClick = { folderPicker.launch(null) }) {
                                    Icon(Icons.Rounded.FolderOpen, contentDescription = "בחירת אחסון", tint = Brand)
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Canvas)
                    )
                },
                floatingActionButton = {
                    if (currentDirectory != null) {
                        ExtendedFloatingActionButton(
                            onClick = { folderText = ""; createFolderDialog = true },
                            containerColor = Brand,
                            contentColor = Color.White,
                            icon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) },
                            text = { Text("תיקייה חדשה", fontWeight = FontWeight.SemiBold) }
                        )
                    }
                }
            ) { padding ->
                if (currentDirectory == null) {
                    HomeScreen(
                        modifier = Modifier.padding(padding),
                        hasPreviousFolder = preferences.getString("root_uri", null) != null,
                        onChooseFolder = { folderPicker.launch(null) },
                        onRestoreFolder = {
                            val saved = preferences.getString("root_uri", null)
                            if (saved != null) {
                                runCatching {
                                    val root = DocumentFile.fromTreeUri(context, Uri.parse(saved))
                                    if (root != null) {
                                        pathStack.clear()
                                        pathStack.add(root)
                                        reload()
                                    }
                                }.onFailure {
                                    Toast.makeText(context, "צריך לבחור מחדש את תיקיית האחסון", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                } else {
                    BrowserScreen(
                        modifier = Modifier.padding(padding),
                        entries = visibleEntries,
                        isLoading = isLoading,
                        totalCount = entries.size,
                        directoryName = currentDirectory.name.orEmpty(),
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        selectedCategory = selectedCategory,
                        onCategoryChange = { selectedCategory = it },
                        onRefresh = { reload() },
                        onOpen = { entry ->
                            val file = entry.document
                            if (entry.isDirectory) {
                                pathStack.add(file)
                                searchText = ""
                                selectedCategory = "הכול"
                                reload()
                            } else {
                                openInside(file)
                            }
                        },
                        onMore = { selectedFile = it }
                    )
                }
            }
        }
    }

    if (createFolderDialog) {
        AlertDialog(
            onDismissRequest = { createFolderDialog = false },
            icon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, tint = Brand) },
            title = { Text("תיקייה חדשה") },
            text = {
                OutlinedTextField(
                    value = folderText,
                    onValueChange = { folderText = it },
                    label = { Text("שם התיקייה") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = folderText.trim()
                    if (name.isNotEmpty()) {
                        val created = currentDirectory?.createDirectory(name)
                        Toast.makeText(context, if (created != null) "התיקייה נוצרה" else "לא ניתן ליצור תיקייה", Toast.LENGTH_SHORT).show()
                        reload()
                    }
                    createFolderDialog = false
                }) { Text("יצירה") }
            },
            dismissButton = { TextButton(onClick = { createFolderDialog = false }) { Text("ביטול") } }
        )
    }

    if (selectedFile != null && !renameDialog && !deleteDialog) {
        val file = selectedFile!!
        AlertDialog(
            onDismissRequest = { selectedFile = null },
            icon = { Icon(if (file.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, contentDescription = null, tint = Brand) },
            title = { Text(file.name ?: "פרטי קובץ", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (file.isDirectory) "תיקייה" else (file.type ?: "קובץ"))
                    Text("גודל: " + formatSize(file.length()), color = Muted)
                    Text("עודכן: " + formatDate(file.lastModified()), color = Muted)
                }
            },
            confirmButton = {
                Column {
                    Row {
                        TextButton(onClick = {
                            transferSource = file
                            transferMode = "copy"
                            selectedFile = null
                            transferPicker.launch(null)
                        }) { Text("העתקה") }
                        TextButton(onClick = {
                            transferSource = file
                            transferMode = "move"
                            selectedFile = null
                            transferPicker.launch(null)
                        }) { Text("העברה") }
                        if (!file.isDirectory) {
                            TextButton(onClick = {
                                shareFile(context, file)
                                selectedFile = null
                            }) { Text("שיתוף") }
                        }
                    }
                    Row {
                        if (file.name.orEmpty().endsWith(".zip", ignoreCase = true)) {
                            TextButton(onClick = {
                                selectedFile = null
                                openInside(file)
                            }) { Text("עיון ב-ZIP") }
                        } else {
                            TextButton(onClick = {
                                selectedFile = null
                                scope.launch {
                                    progressMessage = "דוחס לארכיון ZIP..."
                                    try {
                                        withContext(Dispatchers.IO) {
                                            createZipFromDocument(context, file, currentDirectory!!) { current ->
                                                scope.launch { progressMessage = current }
                                            }
                                        }
                                        Toast.makeText(context, "קובץ ZIP נוצר בהצלחה", Toast.LENGTH_LONG).show()
                                    } catch (error: Exception) {
                                        Toast.makeText(context, error.message ?: "הדחיסה נכשלה", Toast.LENGTH_LONG).show()
                                    } finally {
                                        progressMessage = null
                                        reload()
                                    }
                                }
                            }) { Text("דחיסה ל-ZIP") }
                        }
                        TextButton(onClick = {
                            renameText = file.name.orEmpty()
                            renameDialog = true
                        }) { Text("שינוי שם") }
                        TextButton(onClick = { deleteDialog = true }) {
                            Text("מחיקה", color = Color(0xFFB42318))
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { selectedFile = null }) { Text("סגירה") } }
        )
    }

    if (renameDialog && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { renameDialog = false; selectedFile = null },
            title = { Text("שינוי שם") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("שם חדש") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val newName = renameText.trim()
                    val ok = newName.isNotEmpty() && selectedFile?.renameTo(newName) == true
                    Toast.makeText(context, if (ok) "השם עודכן" else "שינוי השם נכשל", Toast.LENGTH_SHORT).show()
                    renameDialog = false
                    selectedFile = null
                    reload()
                }) { Text("שמירה") }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("ביטול") } }
        )
    }

    if (deleteDialog && selectedFile != null) {
        AlertDialog(
            onDismissRequest = { deleteDialog = false; selectedFile = null },
            title = { Text("למחוק את הפריט?") },
            text = { Text("הפעולה תמחק את " + (selectedFile?.name ?: "הפריט") + ". לא ניתן לבטל אותה.") },
            confirmButton = {
                TextButton(onClick = {
                    val ok = selectedFile?.delete() == true
                    Toast.makeText(context, if (ok) "הפריט נמחק" else "המחיקה נכשלה", Toast.LENGTH_SHORT).show()
                    deleteDialog = false
                    selectedFile = null
                    reload()
                }) { Text("מחיקה", color = Color(0xFFB42318)) }
            },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("ביטול") } }
        )
    }
    if (showZipDialog && zipArchive != null) {
        AlertDialog(
            onDismissRequest = { showZipDialog = false; zipArchive = null },
            icon = { Icon(Icons.Rounded.Archive, contentDescription = null, tint = Brand) },
            title = { Text(zipArchive?.name ?: "תוכן הארכיון") },
            text = {
                Column(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        zipLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        zipError != null -> Text(zipError ?: "שגיאה", color = Color(0xFFB42318))
                        zipEntries.isEmpty() -> Text("הארכיון ריק")
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            items(zipEntries) { entry ->
                                Text(entry, style = MaterialTheme.typography.bodySmall, color = Ink)
                                HorizontalDivider(color = Line)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !zipLoading && zipError == null && currentDirectory != null,
                    onClick = {
                        val archive = zipArchive
                        val destination = currentDirectory
                        if (archive != null && destination != null) {
                            showZipDialog = false
                            scope.launch {
                                progressMessage = "מחלץ קבצים..."
                                try {
                                    withContext(Dispatchers.IO) {
                                        extractZipSafely(context, archive, destination) { current ->
                                            scope.launch { progressMessage = current }
                                        }
                                    }
                                    Toast.makeText(context, "החילוץ הושלם בתיקייה חדשה", Toast.LENGTH_LONG).show()
                                } catch (error: Exception) {
                                    Toast.makeText(context, error.message ?: "החילוץ נכשל", Toast.LENGTH_LONG).show()
                                } finally {
                                    progressMessage = null
                                    zipArchive = null
                                    reload()
                                }
                            }
                        }
                    }
                ) { Text("חלץ לכאן") }
            },
            dismissButton = {
                TextButton(onClick = { showZipDialog = false; zipArchive = null }) { Text("סגירה") }
            }
        )
    }

    if (progressMessage != null) {
        AlertDialog(
            onDismissRequest = {},
            icon = { CircularProgressIndicator() },
            title = { Text("הפעולה מתבצעת") },
            text = { Text(progressMessage ?: "מעבד...") },
            confirmButton = {}
        )
    }

    previewFile?.let { file ->
        MediaPreviewDialog(file = file, onClose = { previewFile = null })
    }

}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    hasPreviousFolder: Boolean,
    onChooseFolder: () -> Unit,
    onRestoreFolder: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.linearGradient(listOf(Color(0xFF244A80), Color(0xFF557FCA))),
                        RoundedCornerShape(28.dp)
                    )
                    .padding(22.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(48.dp).background(Color.White.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    Text("הכול במקום אחד.", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "סייר קבצים פשוט, מהיר ופרטי. פותחים תיקייה ומתחילים לעבוד.",
                        color = Color.White.copy(alpha = 0.88f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = onChooseFolder,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Brand),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("בחירת תיקיית אחסון", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.WifiOff,
                    title = "ללא אינטרנט",
                    subtitle = "עבודה מקומית בלבד",
                    tint = Color(0xFF16856B),
                    tileColor = Mint
                )
                InfoTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Shield,
                    title = "פרטיות",
                    subtitle = "בלי חשבון משתמש",
                    tint = Brand,
                    tileColor = SoftBlue
                )
            }
        }
        item {
            Text("התחלה מהירה", style = MaterialTheme.typography.titleLarge, color = Ink)
        }
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                shadowElevation = 1.dp
            ) {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = if (hasPreviousFolder) onRestoreFolder else onChooseFolder).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(46.dp).background(SoftBlue, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Folder, contentDescription = null, tint = Brand)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (hasPreviousFolder) "המשך לתיקייה האחרונה" else "בחירת תיקייה ראשונה", fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("גישה לאחסון באישור שלך", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = null, tint = Muted)
                }
            }
        }
        item {
            Text("נבנה בשבילך", style = MaterialTheme.typography.titleLarge, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text(
                "ממשק בעברית, ניווט נוח, חיפוש מהיר ופעולות בסיסיות לניהול הקבצים. אפשר לבחור גם כרטיס זיכרון או כונן USB דרך בורר התיקיות של Android.",
                color = Muted,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun InfoTile(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    tileColor: Color
) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(38.dp).background(tileColor, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint)
            }
            Text(title, color = Ink, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowserScreen(
    modifier: Modifier = Modifier,
    entries: List<FileEntry>,
    isLoading: Boolean,
    totalCount: Int,
    directoryName: String,
    searchText: String,
    onSearchChange: (String) -> Unit,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onOpen: (DocumentFile) -> Unit,
    onMore: (DocumentFile) -> Unit
) {
    val categories = listOf("הכול", "תיקיות", "תמונות", "וידאו", "שמע", "מסמכים", "ארכיונים")
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(directoryName, style = MaterialTheme.typography.titleLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(totalCount.toString() + " פריטים בתיקייה", color = Muted, style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "רענון", tint = Brand)
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = searchText,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("חיפוש לפי שם קובץ...") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Muted) },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "ניקוי חיפוש")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White,
                    focusedContainerColor = Color.White,
                    unfocusedBorderColor = Line,
                    focusedBorderColor = Brand
                )
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { onCategoryChange(category) },
                        label = { Text(category) },
                        shape = RoundedCornerShape(50.dp)
                    )
                }
            }
        }
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Brand,
                trackColor = SoftBlue
            )
        }
        if (entries.isEmpty() && isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand)
            }
        } else if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(76.dp).background(SoftBlue, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = Brand, modifier = Modifier.size(36.dp))
                    }
                    Text("אין פריטים להצגה", color = Ink, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text("נסה לשנות את החיפוש או לבחור קטגוריה אחרת.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 100.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.document.uri.toString() }) { entry ->
                    FileRow(
                        file = entry,
                        onClick = { onOpen(entry) },
                        onMore = { onMore(entry.document) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: FileEntry, onClick: () -> Unit, onMore: () -> Unit) {
    val extension = file.name.substringAfterLast('.', "").lowercase()
    val folder = file.isDirectory
    val tint = if (folder) Brand else when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "heic" -> Color(0xFF16856B)
        "mp4", "mkv", "mov", "avi" -> Color(0xFF8B5CF6)
        "mp3", "wav", "m4a", "ogg", "flac" -> Color(0xFFCA7A20)
        "pdf", "doc", "docx", "txt", "md" -> Color(0xFFCE5361)
        "zip", "rar", "7z", "tar", "gz" -> Color(0xFF64748B)
        else -> Muted
    }
    val icon = if (folder) Icons.Rounded.Folder else when (extension) {
        "jpg", "jpeg", "png", "gif", "webp", "heic" -> Icons.Rounded.Image
        "mp4", "mkv", "mov", "avi" -> Icons.Rounded.Movie
        "mp3", "wav", "m4a", "ogg", "flac" -> Icons.Rounded.Audiotrack
        "pdf" -> Icons.Rounded.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Rounded.Archive
        else -> Icons.Rounded.InsertDriveFile
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        shadowElevation = 1.dp
    ) {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMore).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(44.dp).background(tint.copy(alpha = 0.11f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(file.name, color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (folder) "תיקייה" else formatSize(file.lengthBytes) + "  •  " + (file.mimeType ?: extension.uppercase().ifEmpty { "קובץ" }),
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "פעולות נוספות", tint = Muted)
            }
        }
    }
}

private fun openFile(context: Context, file: DocumentFile) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(file.uri, file.type ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, file.name, file.uri)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "לא נמצאה אפליקציה לפתיחת הקובץ", Toast.LENGTH_LONG).show() }
}

private fun shareFile(context: Context, file: DocumentFile) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = file.type ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, file.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newUri(context.contentResolver, file.name, file.uri)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "שיתוף קובץ")) }
        .onFailure { Toast.makeText(context, "לא ניתן לשתף את הקובץ", Toast.LENGTH_SHORT).show() }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KB"
        bytes < 1024L * 1024L * 1024L -> (bytes / (1024 * 1024)).toString() + " MB"
        else -> (bytes / (1024L * 1024L * 1024L)).toString() + " GB"
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "לא ידוע"
    return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
        .format(java.util.Date(timestamp))
}


@Composable
private fun MediaPreviewDialog(file: DocumentFile, onClose: () -> Unit) {
    val context = LocalContext.current
    val extension = file.name.orEmpty().substringAfterLast('.', "").lowercase()
    val imageTypes = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")
    val videoTypes = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp")
    when {
        extension in imageTypes -> Dialog(
            onDismissRequest = onClose,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(12.dp),
                color = Color(0xFF101827),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(file.name ?: "תמונה", color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = onClose) {
                            Icon(Icons.Rounded.Close, contentDescription = "סגירה", tint = Color.White)
                        }
                    }
                    AndroidView(
                        factory = { viewContext ->
                            ImageView(viewContext).apply {
                                scaleType = ImageView.ScaleType.FIT_CENTER
                                adjustViewBounds = true
                                setBackgroundColor(android.graphics.Color.rgb(16, 24, 39))
                                setImageURI(file.uri)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
            }
        }
        extension in videoTypes -> VideoPreviewDialog(file = file, onClose = onClose)
        else -> AudioPreviewDialog(file = file, onClose = onClose)
    }
}

@Composable
private fun VideoPreviewDialog(file: DocumentFile, onClose: () -> Unit) {
    val context = LocalContext.current
    val videoView = remember(file.uri) { VideoView(context) }
    DisposableEffect(videoView, file.uri) {
        videoView.setVideoURI(file.uri)
        val controller = android.widget.MediaController(context)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { player ->
            player.isLooping = false
            videoView.start()
        }
        onDispose {
            runCatching { videoView.stopPlayback() }
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            color = Color(0xFF101827),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name ?: "וידאו", color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "סגירה", tint = Color.White)
                    }
                }
                AndroidView(
                    factory = { videoView },
                    modifier = Modifier.fillMaxWidth().height(260.dp)
                )
                Text("הנגן משתמש במפענחי המדיה המובנים של Android.", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AudioPreviewDialog(file: DocumentFile, onClose: () -> Unit) {
    val context = LocalContext.current
    var player by remember(file.uri) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(file.uri) { mutableStateOf(false) }
    var playing by remember(file.uri) { mutableStateOf(false) }
    var duration by remember(file.uri) { mutableIntStateOf(0) }
    var position by remember(file.uri) { mutableIntStateOf(0) }
    var playerError by remember(file.uri) { mutableStateOf<String?>(null) }

    DisposableEffect(file.uri) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.setOnPreparedListener {
            duration = it.duration.coerceAtLeast(0)
            prepared = true
            it.start()
            playing = true
        }
        mediaPlayer.setOnCompletionListener {
            playing = false
            position = 0
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            playerError = "לא ניתן לנגן את קובץ השמע הזה"
            true
        }
        try {
            mediaPlayer.setDataSource(context, file.uri)
            mediaPlayer.prepareAsync()
        } catch (error: Exception) {
            playerError = error.message ?: "לא ניתן לפתוח את קובץ השמע"
        }
        onDispose {
            runCatching { mediaPlayer.stop() }
            mediaPlayer.release()
            player = null
        }
    }

    LaunchedEffect(prepared, player) {
        while (prepared && player != null) {
            position = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)
            delay(400)
        }
    }

    Dialog(onDismissRequest = onClose) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = RoundedCornerShape(26.dp)
        ) {
            Column(
                Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier.size(76.dp).background(SoftBlue, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Audiotrack, contentDescription = null, tint = Brand, modifier = Modifier.size(36.dp))
                }
                Text(file.name ?: "נגן שמע", style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 2)
                if (playerError != null) {
                    Text(playerError ?: "", color = Color(0xFFB42318))
                } else if (!prepared) {
                    CircularProgressIndicator()
                    Text("טוען שמע...", color = Muted, style = MaterialTheme.typography.labelMedium)
                } else {
                    Slider(
                        value = position.toFloat().coerceIn(0f, duration.coerceAtLeast(1).toFloat()),
                        onValueChange = { value ->
                            position = value.toInt()
                            player?.seekTo(position)
                        },
                        valueRange = 0f..duration.coerceAtLeast(1).toFloat()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(position), color = Muted, style = MaterialTheme.typography.labelSmall)
                        Text(formatTime(duration), color = Muted, style = MaterialTheme.typography.labelSmall)
                    }
                    FilledIconButton(
                        onClick = {
                            val active = player ?: return@FilledIconButton
                            if (active.isPlaying) {
                                active.pause()
                                playing = false
                            } else {
                                if (position >= duration && duration > 0) active.seekTo(0)
                                active.start()
                                playing = true
                            }
                        },
                        enabled = prepared,
                        modifier = Modifier.size(58.dp)
                    ) {
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playing) "השהיה" else "ניגון",
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                TextButton(onClick = onClose) { Text("סגירה") }
            }
        }
    }
}

private fun formatTime(milliseconds: Int): String {
    val seconds = (milliseconds.coerceAtLeast(0) / 1000)
    val minutesPart = seconds / 60
    val secondsPart = seconds % 60
    return String.format(java.util.Locale.ROOT, "%d:%02d", minutesPart, secondsPart)
}
