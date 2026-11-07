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
    var entries by remember { mutableStateOf<List<DocumentFile>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("הכול") }
    var showHidden by remember { mutableStateOf(false) }
    var sortBySize by remember { mutableStateOf(false) }
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
        entries = directory?.listFiles()
            ?.filter { showHidden || !it.name.orEmpty().startsWith(".") }
            ?.toList()
            ?.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name.orEmpty().lowercase() })
            ?: emptyList()
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
    val visibleEntries = remember(entries, searchText, selectedCategory, sortBySize) {
        val filtered = entries.filter { file ->
            val nameMatches = file.name.orEmpty().contains(searchText.trim(), ignoreCase = true)
            val extension = file.name.orEmpty().substringAfterLast('.', "").lowercase()
            val categoryMatches = when (selectedCategory) {
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
        if (sortBySize) filtered.sortedByDescending { it.length() } else filtered
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
                                IconButton(onClick = { sortBySize = !sortBySize }) {
                                    Icon(Icons.Rounded.Sort, contentDescription = "מיון", tint = Ink)
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
                        totalCount = entries.size,
                        directoryName = currentDirectory.name.orEmpty(),
                        searchText = searchText,
                        onSearchChange = { searchText = it },
                        selectedCategory = selectedCategory,
                        onCategoryChange = { selectedCategory = it },
                        onRefresh = { reload() },
                        onOpen = { file ->
                            if (file.isDirectory) {
                                pathStack.add(file)
                                searchText = ""
                                selectedCategory = "הכול"
                                reload()
                            } else {
                                openFile(context, file)
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
                Row {
                    if (!file.isDirectory) {
                        TextButton(onClick = {
                            shareFile(context, file)
                            selectedFile = null
                        }) { Text("שיתוף") }
                    }
                    TextButton(onClick = {
                        renameText = file.name.orEmpty()
                        renameDialog = true
                    }) { Text("שינוי שם") }
                    TextButton(onClick = { deleteDialog = true }) {
                        Text("מחיקה", color = Color(0xFFB42318))
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
    entries: List<DocumentFile>,
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
        if (entries.isEmpty()) {
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
                items(entries, key = { it.uri.toString() }) { file ->
                    FileRow(
                        file = file,
                        onClick = { onOpen(file) },
                        onMore = { onMore(file) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(file: DocumentFile, onClick: () -> Unit, onMore: () -> Unit) {
    val extension = file.name.orEmpty().substringAfterLast('.', "").lowercase()
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
                Text(file.name ?: "ללא שם", color = Ink, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (folder) "תיקייה" else formatSize(file.length()) + "  •  " + (file.type ?: extension.uppercase().ifEmpty { "קובץ" }),
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
