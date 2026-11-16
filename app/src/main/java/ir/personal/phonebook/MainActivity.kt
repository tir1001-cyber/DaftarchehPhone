
package ir.personal.phonebook

import android.Manifest
import android.content.Intent
import android.provider.SimPhonebookContract
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ------------------------------------------------------------
// Data models
// ------------------------------------------------------------

@Serializable
data class Contact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phones: List<String>
)

@Serializable
data class PhonebookData(
    val contacts: List<Contact> = emptyList(),
    val trash: List<Contact> = emptyList()
)

private val jsonFormat = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ------------------------------------------------------------
// Activity
// ------------------------------------------------------------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhonebookTheme {
                PhonebookApp()
            }
        }
    }
}

// ------------------------------------------------------------
// Theme
// ------------------------------------------------------------

@Composable
private fun PhonebookTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF8055A6),
        onPrimary = Color.White,
        secondary = Color(0xFFD58AB5),
        background = Color(0xFFFFF8FC),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF2E8F6)
    )

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

// ------------------------------------------------------------
// Main screen
// ------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhonebookApp() {
    val context = LocalContext.current

    var data by remember { mutableStateOf(PhonebookData()) }
    var loaded by remember { mutableStateOf(false) }

    var searchText by remember { mutableStateOf("") }
    var showTrash by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    var editingContact by remember { mutableStateOf<Contact?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showMergeConfirmation by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    var showPermanentDeleteConfirmation by remember { mutableStateOf(false) }
    var showImportConfirmation by remember { mutableStateOf(false) }
    var pendingImportText by remember { mutableStateOf<String?>(null) }
    var pendingImportContacts by remember { mutableStateOf<List<Contact>?>(null) }
    var showSimConfirmation by remember { mutableStateOf(false) }
    var showSimChooser by remember { mutableStateOf(false) }
    var simTargets by remember { mutableStateOf<List<SimTarget>>(emptyList()) }

    var message by remember { mutableStateOf("") }
    var saveError by remember { mutableStateOf("") }

    // Load the saved phonebook.
    LaunchedEffect(Unit) {
        try {
            data = loadPhonebook(context.filesDir)
        } catch (e: Exception) {
            saveError = "خواندن اطلاعات ذخیره‌شده ناموفق بود: ${e.message ?: "خطای نامشخص"}"
        }
        loaded = true
    }

    // Save changes locally.
    LaunchedEffect(data, loaded) {
        if (loaded) {
            try {
                savePhonebook(context.filesDir, data)
                saveError = ""
            } catch (e: Exception) {
                saveError = "ذخیره اطلاعات انجام نشد: ${e.message ?: "خطای نامشخص"}"
            }
        }
    }

    val visibleContacts = remember(data, searchText, showTrash) {
        val source = if (showTrash) data.trash else data.contacts
        val query = searchText.trim()

        if (query.isBlank()) {
            source
        } else {
            source.filter { contact ->
                contact.name.contains(query, ignoreCase = true) ||
                    contact.phones.any { it.contains(query, ignoreCase = true) }
            }
        }
    }

    val simPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.READ_CONTACTS] == true &&
            grants[Manifest.permission.WRITE_CONTACTS] == true
        ) {
            try {
                simTargets = findAvailableSimTargets(context)
                if (simTargets.isEmpty()) {
                    message = "سیم‌کارت قابل نوشتن پیدا نشد یا گوشی انتقال مستقیم را پشتیبانی نمی‌کند. خروجی VCF هم در دسترس است."
                } else {
                    showSimChooser = true
                }
            } catch (e: Exception) {
                message = "خواندن اطلاعات سیم‌کارت ممکن نشد. خروجی VCF همچنان در دسترس است."
            }
        } else {
            message = "برای انتقال به سیم‌کارت، اجازه دسترسی مخاطبین لازم است."
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val mime = context.contentResolver.getType(uri).orEmpty()
                val isXlsx = mime.contains("spreadsheet", ignoreCase = true) ||
                    uri.toString().substringBefore("?").endsWith(".xlsx", ignoreCase = true)
                if (isXlsx) {
                    val bytes = context.contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: throw IllegalStateException("فایل قابل خواندن نیست")
                    pendingImportContacts = parseXlsx(bytes)
                    pendingImportText = null
                } else {
                    val text = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: throw IllegalStateException("فایل قابل خواندن نیست")
                    pendingImportText = text
                    pendingImportContacts = null
                }
                showImportConfirmation = true
            } catch (e: Exception) {
                message = "خطا در خواندن فایل: " + e.message
            }
        }
    }

    val jsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            exportFile(context, uri, "json", data) {
                message = it
            }
        }
    }

    val vcfExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/vcard")
    ) { uri ->
        if (uri != null) {
            exportFile(context, uri, "vcf", data) {
                message = it
            }
        }
    }

    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            exportFile(context, uri, "csv", data) {
                message = it
            }
        }
    }

    val xlsxExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri ->
        if (uri != null) {
            exportFile(context, uri, "xlsx", data) {
                message = it
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "دفترچه تلفن شخصی",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (showTrash) "سطل زباله" else "مخاطبین",
                            fontSize = 12.sp
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            showTrash = !showTrash
                            selectedIds = emptySet()
                            searchText = ""
                        }
                    ) {
                        Icon(
                            if (showTrash) Icons.Default.Close else Icons.Default.Delete,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (showTrash) "بازگشت" else "سطل زباله")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!showTrash) {
                FloatingActionButton(
                    onClick = {
                        editingContact = null
                        showEditor = true
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "مخاطب جدید")
                }
            }
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("جست‌وجوی نام یا شماره") },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (searchText.isNotEmpty()) {
                        IconButton(onClick = { searchText = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "پاک کردن")
                        }
                    }
                }
            )

            Spacer(Modifier.height(8.dp))

            if (!showTrash) {
                ExportImportPanel(
                    onImport = {
                        importLauncher.launch(
                            arrayOf(
                                "application/json",
                                "text/vcard",
                                "text/x-vcard",
                                "text/csv",
                                "text/plain",
                                "*/*"
                            )
                        )
                    },
                    onJson = { jsonExportLauncher.launch("phonebook-backup.json") },
                    onVcf = { vcfExportLauncher.launch("phonebook-contacts.vcf") },
                    onCsv = { csvExportLauncher.launch("phonebook-contacts.csv") },
                    onXlsx = { xlsxExportLauncher.launch("phonebook-contacts.xlsx") }
                )
            }

            if (saveError.isNotBlank()) {
                Text(
                    saveError,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp)
                )
            }

            if (message.isNotBlank()) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { message = "" }
                        .padding(8.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            SelectionPanel(
                count = visibleContacts.size,
                selectedCount = selectedIds.size,
                isTrash = showTrash,
                onSelectAll = {
                    selectedIds = if (selectedIds.size == visibleContacts.size) {
                        emptySet()
                    } else {
                        visibleContacts.map { it.id }.toSet()
                    }
                },
                onDelete = {
                    showDeleteConfirmation = true
                },
                onRestore = {
                    data = restoreSelected(data, selectedIds)
                    selectedIds = emptySet()
                    message = "مخاطبین انتخاب‌شده بازیابی شدند."
                },
                onPermanentDelete = {
                    showPermanentDeleteConfirmation = true
                },
                onMerge = {
                    showMergeConfirmation = true
                }
            )

            if (!showTrash && selectedIds.isNotEmpty()) {
                OutlinedButton(onClick = { showSimConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("انتقال مخاطبین انتخاب‌شده به سیم‌کارت")
                }
            }

            HorizontalDivider()

            if (visibleContacts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (showTrash) "سطل زباله خالی است." else "مخاطبی پیدا نشد.",
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleContacts, key = { it.id }) { contact ->
                        ContactCard(
                            contact = contact,
                            selected = contact.id in selectedIds,
                            showTrash = showTrash,
                            onSelect = { checked ->
                                selectedIds = if (checked) {
                                    selectedIds + contact.id
                                } else {
                                    selectedIds - contact.id
                                }
                            },
                            onEdit = {
                                editingContact = contact
                                showEditor = true
                            },
                            onDial = { number ->
                                try {
                                    val intent = Intent(
                                        Intent.ACTION_DIAL,
                                        Uri.fromParts("tel", number, null)
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    message = "باز کردن شماره‌گیر ممکن نشد."
                                }
                            }
                        )
                    }
                }
            }

            Text(
                "تعداد مخاطبین: ${data.contacts.size}    |    داخل سطل زباله: ${data.trash.size}",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }

    if (showEditor) {
        ContactEditorDialog(
            initial = editingContact,
            onDismiss = { showEditor = false },
            onSave = { name, phones ->
                val newContact = Contact(
                    id = editingContact?.id ?: UUID.randomUUID().toString(),
                    name = name.trim(),
                    phones = phones
                )

                data = if (editingContact == null) {
                    data.copy(contacts = data.contacts + newContact)
                } else {
                    data.copy(
                        contacts = data.contacts.map {
                            if (it.id == newContact.id) newContact else it
                        }
                    )
                }

                showEditor = false
                message = "اطلاعات مخاطب ذخیره شد."
            }
        )
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("انتقال به سطل زباله") },
            text = {
                Text("مخاطبین انتخاب‌شده به سطل زباله منتقل شوند؟")
            },
            confirmButton = {
                TextButton(onClick = {
                    data = moveToTrash(data, selectedIds)
                    selectedIds = emptySet()
                    showDeleteConfirmation = false
                    message = "مخاطبین به سطل زباله منتقل شدند."
                }) {
                    Text("تأیید")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("انصراف")
                }
            }
        )
    }

    if (showPermanentDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showPermanentDeleteConfirmation = false },
            title = { Text("حذف دائمی") },
            text = {
                Text("این مخاطبین برای همیشه حذف می‌شوند. ادامه می‌دهید؟")
            },
            confirmButton = {
                TextButton(onClick = {
                    data = data.copy(
                        trash = data.trash.filterNot { it.id in selectedIds }
                    )
                    selectedIds = emptySet()
                    showPermanentDeleteConfirmation = false
                    message = "حذف دائمی انجام شد."
                }) {
                    Text("حذف دائمی")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentDeleteConfirmation = false }) {
                    Text("انصراف")
                }
            }
        )
    }

    if (showMergeConfirmation) {
        val duplicateCount = findDuplicateGroups(data.contacts).size

        AlertDialog(
            onDismissRequest = { showMergeConfirmation = false },
            title = { Text("ادغام مخاطبین تکراری") },
            text = {
                Text(
                    if (duplicateCount == 0) {
                        "مخاطب تکراری بر اساس شماره تلفن مشترک پیدا نشد."
                    } else {
                        "$duplicateCount گروه مخاطب تکراری پیدا شده است. " +
                            "در هر گروه، نام اولین مخاطب حفظ و شماره‌ها با هم ترکیب می‌شوند."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (duplicateCount > 0) {
                        data = data.copy(
                            contacts = mergeDuplicateContacts(data.contacts)
                        )
                        message = "ادغام مخاطبین انجام شد."
                    }
                    showMergeConfirmation = false
                }) {
                    Text("ادغام")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirmation = false }) {
                    Text("انصراف")
                }
            }
        )
    }

    if (showImportConfirmation && (pendingImportText != null || pendingImportContacts != null)) {
        AlertDialog(
            onDismissRequest = {
                showImportConfirmation = false
                pendingImportText = null
                pendingImportContacts = null
            },
            title = { Text("ورود اطلاعات") },
            text = {
                Text(
                    "اطلاعات فایل انتخاب‌شده به مخاطبین فعلی اضافه می‌شود. " +
                        "مخاطبین فعلی حذف نخواهند شد."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        val imported = pendingImportContacts ?: importContacts(pendingImportText!!)
                        data = data.copy(
                            contacts = mergeImportedContacts(
                                data.contacts,
                                imported
                            )
                        )
                        message = "${imported.size} مخاطب از فایل خوانده شد."
                    } catch (e: Exception) {
                        message = "قالب فایل قابل شناسایی نیست: ${e.message}"
                    }

                    pendingImportText = null
                    pendingImportContacts = null
                    showImportConfirmation = false
                }) {
                    Text("ورود اطلاعات")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingImportText = null
                    showImportConfirmation = false
                }) {
                    Text("انصراف")
                }
            }
        )
    }

    if (showSimConfirmation) {
        val selectedContacts = data.contacts.filter { it.id in selectedIds }
        val selectedPhoneCount = selectedContacts.sumOf { it.phones.size }
        AlertDialog(
            onDismissRequest = { showSimConfirmation = false },
            title = { Text("انتقال به سیم‌کارت") },
            text = {
                Text("قرار است " + selectedContacts.size + " مخاطب و " + selectedPhoneCount + " شماره تلفن از موارد انتخاب‌شده به سیم‌کارت کپی شود. اطلاعات فعلی سیم‌کارت حذف نمی‌شود. ادامه می‌دهی؟")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSimConfirmation = false
                    simPermissionLauncher.launch(arrayOf(
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.WRITE_CONTACTS
                    ))
                }) { Text("ادامه") }
            },
            dismissButton = {
                TextButton(onClick = { showSimConfirmation = false }) { Text("انصراف") }
            }
        )
    }

    if (showSimChooser) {
        AlertDialog(
            onDismissRequest = { showSimChooser = false },
            title = { Text("انتخاب سیم‌کارت") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("سیم‌کارت مقصد را انتخاب کن. ظرفیت آزاد هر سیم‌کارت نمایش داده شده است.")
                    simTargets.forEach { target ->
                        val free = (target.maxRecords - target.recordCount).coerceAtLeast(0)
                        TextButton(
                            onClick = {
                                showSimChooser = false
                                try {
                                    val contactsToCopy = data.contacts.filter { it.id in selectedIds }
                                    val result = writeContactsToSim(context, contactsToCopy, target)
                                    message = "انتقال به سیم‌کارت " + (target.slotIndex + 1) + " انجام شد: " + result.successCount + " شماره موفق، " + result.failedCount + " ناموفق."
                                    if (result.failedCount > 0) {
                                        message += " ممکن است ظرفیت یا محدودیت نام/شماره مانع بعضی موارد شده باشد."
                                    }
                                    selectedIds = emptySet()
                                } catch (e: Exception) {
                                    message = "انتقال انجام نشد: " + (e.message ?: "خطای سیم‌کارت") + ". خروجی VCF راه جایگزین است."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("سیم‌کارت " + (target.slotIndex + 1) + " — ظرفیت آزاد: " + free + " از " + target.maxRecords)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSimChooser = false }) { Text("بستن") }
            }
        )
    }

}

// ------------------------------------------------------------
// Export and import panel
// ------------------------------------------------------------

@Composable
private fun ExportImportPanel(
    onImport: () -> Unit,
    onJson: () -> Unit,
    onVcf: () -> Unit,
    onCsv: () -> Unit,
    onXlsx: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF5EAF8)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SmallAction("ورود فایل", Icons.Default.FileUpload, onImport)
                SmallAction("JSON", Icons.Default.FileDownload, onJson)
                SmallAction("VCF", Icons.Default.FileDownload, onVcf)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SmallAction("CSV", Icons.Default.FileDownload, onCsv)
                SmallAction("Excel XLSX", Icons.Default.FileDownload, onXlsx)
                SmallAction(" ", Icons.Default.Close, {})
            }
        }
    }
}

@Composable
private fun SmallAction(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------
// Selection controls
// ------------------------------------------------------------

@Composable
private fun SelectionPanel(
    count: Int,
    selectedCount: Int,
    isTrash: Boolean,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit,
    onMerge: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("تعداد نمایش: $count", fontSize = 12.sp)

            TextButton(onClick = onSelectAll) {
                Text(
                    if (selectedCount == count && count > 0) {
                        "لغو انتخاب همه"
                    } else {
                        "انتخاب همه"
                    }
                )
            }
        }

        if (selectedCount > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (isTrash) {
                    TextButton(onClick = onRestore) {
                        Icon(Icons.Default.Restore, contentDescription = null)
                        Text("بازیابی")
                    }
                    TextButton(onClick = onPermanentDelete) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Text("حذف دائمی")
                    }
                } else {
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text("انتقال به زباله")
                    }
                    TextButton(onClick = onMerge) {
                        Icon(Icons.Default.MergeType, contentDescription = null)
                        Text("ادغام تکراری‌ها")
                    }
                }
            }
        } else if (!isTrash) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onMerge) {
                    Icon(Icons.Default.MergeType, contentDescription = null)
                    Text("بررسی تکراری‌ها")
                }
            }
        }
    }
}

// ------------------------------------------------------------
// Contact card
// ------------------------------------------------------------

@Composable
private fun ContactCard(
    contact: Contact,
    selected: Boolean,
    showTrash: Boolean,
    onSelect: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDial: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                Color(0xFFF0E2F7)
            } else {
                Color.White
            }
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelect
                )

                Text(
                    contact.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onSelect(!selected) }
                )

                if (!showTrash) {
                    TextButton(onClick = onEdit) {
                        Text("ویرایش")
                    }
                }
            }

            contact.phones.forEach { phone ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        phone,
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp
                    )

                    if (!showTrash) {
                        IconButton(onClick = { onDial(phone) }) {
                            Icon(
                                Icons.Default.Call,
                                contentDescription = "باز کردن شماره‌گیر",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------
// Contact editor
// ------------------------------------------------------------

@Composable
private fun ContactEditorDialog(
    initial: Contact?,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit
) {
    var name by remember(initial) {
        mutableStateOf(initial?.name ?: "")
    }

    var phonesText by remember(initial) {
        mutableStateOf(initial?.phones?.joinToString("\n") ?: "")
    }

    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (initial == null) "افزودن مخاطب" else "ویرایش مخاطب")
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("نام مخاطب") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = phonesText,
                    onValueChange = { phonesText = it },
                    label = { Text("شماره تلفن‌ها (هر شماره در یک خط)") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone
                    )
                )

                Text(
                    "برای هر شماره یک خط جداگانه بنویسید.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )

                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cleanName = name.trim()
                val phones = phonesText
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy { normalizePhone(it) }

                if (cleanName.isBlank()) {
                    error = "نام مخاطب را وارد کنید."
                } else if (phones.isEmpty()) {
                    error = "حداقل یک شماره تلفن وارد کنید."
                } else {
                    onSave(cleanName, phones)
                }
            }) {
                Text("ذخیره")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("انصراف")
            }
        }
    )
}

// ------------------------------------------------------------
// Local persistence
// ------------------------------------------------------------

private fun phonebookFile(filesDir: File): File {
    return File(filesDir, "phonebook-final.json")
}

private fun loadPhonebook(filesDir: File): PhonebookData {
    val file = phonebookFile(filesDir)
    if (!file.exists()) return PhonebookData()

    return jsonFormat.decodeFromString<PhonebookData>(
        file.readText(Charsets.UTF_8)
    )
}

private fun savePhonebook(
    filesDir: File,
    data: PhonebookData
) {
    val destination = phonebookFile(filesDir)
    val temporary = File(filesDir, "phonebook-final.tmp")
    val backup = File(filesDir, "phonebook-final.bak")

    val content = jsonFormat.encodeToString(data)

    temporary.outputStream().use { stream ->
        stream.write(content.toByteArray(Charsets.UTF_8))
        stream.flush()
        (stream as java.io.FileOutputStream).fd.sync()
    }

    if (destination.exists()) {
        if (backup.exists()) backup.delete()

        if (!destination.renameTo(backup)) {
            temporary.delete()
            throw IllegalStateException("ساخت نسخه پشتیبان موقت ناموفق بود.")
        }
    }

    if (!temporary.renameTo(destination)) {
        if (backup.exists()) backup.renameTo(destination)
        temporary.delete()
        throw IllegalStateException("ذخیره فایل اطلاعات ناموفق بود.")
    }

    if (backup.exists()) backup.delete()
}

// ------------------------------------------------------------
// Phone normalization and contact operations
// ------------------------------------------------------------

private fun normalizePhone(value: String): String {
    val converted = value
        .replace('۰', '0')
        .replace('۱', '1')
        .replace('۲', '2')
        .replace('۳', '3')
        .replace('۴', '4')
        .replace('۵', '5')
        .replace('۶', '6')
        .replace('۷', '7')
        .replace('۸', '8')
        .replace('۹', '9')
        .replace('٠', '0')
        .replace('١', '1')
        .replace('٢', '2')
        .replace('٣', '3')
        .replace('٤', '4')
        .replace('٥', '5')
        .replace('٦', '6')
        .replace('٧', '7')
        .replace('٨', '8')
        .replace('٩', '9')

    return converted.filter { it.isDigit() || it == '+' }
}

private fun moveToTrash(
    data: PhonebookData,
    ids: Set<String>
): PhonebookData {
    val moving = data.contacts.filter { it.id in ids }
    return data.copy(
        contacts = data.contacts.filterNot { it.id in ids },
        trash = data.trash + moving
    )
}

private fun restoreSelected(
    data: PhonebookData,
    ids: Set<String>
): PhonebookData {
    val restoring = data.trash.filter { it.id in ids }
    return data.copy(
        trash = data.trash.filterNot { it.id in ids },
        contacts = data.contacts + restoring
    )
}

private fun findDuplicateGroups(
    contacts: List<Contact>
): List<List<Contact>> {
    val groups = mutableListOf<MutableList<Contact>>()

    contacts.forEach { contact ->
        val numbers = contact.phones
            .map { normalizePhone(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val matching = groups.filter { group ->
            val groupNumbers = group
                .flatMap { it.phones }
                .map { normalizePhone(it) }
                .filter { it.isNotBlank() }
                .toSet()

            numbers.intersect(groupNumbers).isNotEmpty()
        }

        if (matching.isEmpty()) {
            groups.add(mutableListOf(contact))
        } else {
            val target = matching.first()
            target.add(contact)

            matching.drop(1).forEach { other ->
                target.addAll(other)
                groups.remove(other)
            }
        }
    }

    return groups.filter { it.size > 1 }
}

private fun mergeDuplicateContacts(
    contacts: List<Contact>
): List<Contact> {
    val groups = findDuplicateGroups(contacts)
    val duplicateIds = groups.flatten().map { it.id }.toSet()

    val merged = groups.map { group ->
        val first = group.first()
        val allPhones = group
            .flatMap { it.phones }
            .distinctBy { normalizePhone(it) }

        first.copy(phones = allPhones)
    }

    val untouched = contacts.filterNot { it.id in duplicateIds }
    return untouched + merged
}

private fun mergeImportedContacts(
    existing: List<Contact>,
    imported: List<Contact>
): List<Contact> {
    var result = existing

    imported.forEach { incoming ->
        val incomingNumbers = incoming.phones
            .map { normalizePhone(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val index = result.indexOfFirst { current ->
            val currentNumbers = current.phones
                .map { normalizePhone(it) }
                .filter { it.isNotBlank() }
                .toSet()

            incomingNumbers.intersect(currentNumbers).isNotEmpty()
        }

        if (index >= 0) {
            val old = result[index]
            val combinedPhones = (old.phones + incoming.phones)
                .distinctBy { normalizePhone(it) }

            result = result.toMutableList().also {
                it[index] = old.copy(phones = combinedPhones)
            }
        } else {
            result = result + incoming.copy(
                id = UUID.randomUUID().toString()
            )
        }
    }

    return result
}

// ------------------------------------------------------------
// Export dispatcher
// ------------------------------------------------------------

private fun exportFile(
    context: android.content.Context,
    uri: Uri,
    format: String,
    data: PhonebookData,
    onResult: (String) -> Unit
) {
    try {
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("فایل خروجی باز نشد.")

        output.use {
            when (format) {
                "json" -> writeJson(it, data)
                "vcf" -> writeVcf(it, data.contacts)
                "csv" -> writeCsv(it, data.contacts)
                "xlsx" -> writeXlsx(it, data.contacts)
                else -> throw IllegalArgumentException("قالب ناشناخته")
            }
        }

        onResult("فایل با موفقیت ذخیره شد.")
    } catch (e: Exception) {
        onResult("خطا در ساخت فایل: ${e.message ?: "نامشخص"}")
    }
}

private fun writeJson(
    output: OutputStream,
    data: PhonebookData
) {
    output.write(
        jsonFormat.encodeToString(data).toByteArray(Charsets.UTF_8)
    )
}

// ------------------------------------------------------------
// CSV export
// ------------------------------------------------------------

private fun csvEscape(value: String): String {
    return "\"" + value.replace("\"", "\"\"") + "\""
}

private fun writeCsv(
    output: OutputStream,
    contacts: List<Contact>
) {
    val builder = StringBuilder()
    builder.append("Name,Phone\n")

    contacts.forEach { contact ->
        if (contact.phones.isEmpty()) {
            builder.append(csvEscape(contact.name))
                .append(",\n")
        } else {
            contact.phones.forEach { phone ->
                builder.append(csvEscape(contact.name))
                    .append(",")
                    .append(csvEscape(phone))
                    .append("\n")
            }
        }
    }

    output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
    output.write(builder.toString().toByteArray(Charsets.UTF_8))
}

// ------------------------------------------------------------
// VCF export
// ------------------------------------------------------------

private fun vcfEscape(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")
}

private fun writeVcf(
    output: OutputStream,
    contacts: List<Contact>
) {
    val builder = StringBuilder()

    contacts.forEach { contact ->
        builder.append("BEGIN:VCARD\r\n")
        builder.append("VERSION:3.0\r\n")
        builder.append("FN:")
            .append(vcfEscape(contact.name))
            .append("\r\n")

        contact.phones.forEach { phone ->
            builder.append("TEL;TYPE=CELL:")
                .append(vcfEscape(phone))
                .append("\r\n")
        }

        builder.append("END:VCARD\r\n")
    }

    output.write(builder.toString().toByteArray(Charsets.UTF_8))
}

// ------------------------------------------------------------
// Import dispatcher
// ------------------------------------------------------------

private fun importContacts(text: String): List<Contact> {
    val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')

    if (trimmed.startsWith("{")) {
        val backup = jsonFormat.decodeFromString<PhonebookData>(trimmed)
        return backup.contacts
    }

    if (trimmed.startsWith("[")) {
        return jsonFormat.decodeFromString<List<Contact>>(trimmed)
    }

    if (trimmed.contains("BEGIN:VCARD", ignoreCase = true)) {
        return parseVcf(trimmed)
    }

    return parseCsv(trimmed)
}

private fun parseVcf(text: String): List<Contact> {
    val unfolded = text
        .replace("\r\n ", "")
        .replace("\n ", "")

    val result = mutableListOf<Contact>()

    var name = ""
    var phones = mutableListOf<String>()
    var inside = false

    unfolded.lines().forEach { rawLine ->
        val line = rawLine.trim()

        when {
            line.equals("BEGIN:VCARD", ignoreCase = true) -> {
                inside = true
                name = ""
                phones = mutableListOf()
            }

            line.equals("END:VCARD", ignoreCase = true) -> {
                if (inside && name.isNotBlank() && phones.isNotEmpty()) {
                    result.add(
                        Contact(
                            name = name,
                            phones = phones.distinctBy { normalizePhone(it) }
                        )
                    )
                }
                inside = false
            }

            inside && (
                line.startsWith("FN:", ignoreCase = true) ||
                    line.startsWith("FN;")
                ) -> {
                name = unescapeVcf(line.substringAfter(":"))
            }

            inside && line.startsWith("TEL", ignoreCase = true) -> {
                val phone = line.substringAfter(":", "")
                if (phone.isNotBlank()) {
                    phones.add(unescapeVcf(phone))
                }
            }
        }
    }

    return result
}

private fun unescapeVcf(value: String): String {
    return value
        .replace("\\n", "\n", ignoreCase = true)
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
}

// ------------------------------------------------------------
// CSV import
// ------------------------------------------------------------

private fun parseCsv(text: String): List<Contact> {
    val rows = parseCsvRows(text)
    if (rows.isEmpty()) return emptyList()

    val header = rows.first().map { it.trim().lowercase() }

    val nameIndex = header.indexOfFirst {
        it == "name" || it == "نام"
    }

    val phoneIndex = header.indexOfFirst {
        it == "phone" || it == "telephone" || it == "شماره"
    }

    val startIndex = if (nameIndex >= 0 || phoneIndex >= 0) 1 else 0
    val actualNameIndex = if (nameIndex >= 0) nameIndex else 0
    val actualPhoneIndex = if (phoneIndex >= 0) phoneIndex else 1

    val grouped = linkedMapOf<String, MutableList<String>>()

    rows.drop(startIndex).forEach { row ->
        val name = row.getOrNull(actualNameIndex)?.trim().orEmpty()
        val phone = row.getOrNull(actualPhoneIndex)?.trim().orEmpty()

        if (name.isNotBlank() && phone.isNotBlank()) {
            grouped.getOrPut(name) { mutableListOf() }.add(phone)
        }
    }

    return grouped.map { (name, phones) ->
        Contact(
            name = name,
            phones = phones.distinctBy { normalizePhone(it) }
        )
    }
}

private fun parseCsvRows(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var i = 0

    while (i < text.length) {
        val char = text[i]

        when {
            char == '"' && quoted && i + 1 < text.length &&
                text[i + 1] == '"' -> {
                field.append('"')
                i++
            }

            char == '"' -> {
                quoted = !quoted
            }

            char == ',' && !quoted -> {
                row.add(field.toString())
                field.clear()
            }

            (char == '\n' || char == '\r') && !quoted -> {
                row.add(field.toString())
                field.clear()

                if (row.any { it.isNotBlank() }) {
                    rows.add(row)
                }

                row = mutableListOf()

                if (char == '\r' && i + 1 < text.length &&
                    text[i + 1] == '\n'
                ) {
                    i++
                }
            }

            else -> field.append(char)
        }

        i++
    }

    row.add(field.toString())
    if (row.any { it.isNotBlank() }) {
        rows.add(row)
    }

    return rows
}

// ------------------------------------------------------------
// XLSX export - minimal real Excel workbook
// ------------------------------------------------------------

private fun xmlEscape(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}

private fun excelColumn(index: Int): String {
    var number = index + 1
    val result = StringBuilder()

    while (number > 0) {
        val remainder = (number - 1) % 26
        result.insert(0, ('A'.code + remainder).toChar())
        number = (number - 1) / 26
    }

    return result.toString()
}

private fun excelCell(
    reference: String,
    value: String
): String {
    val safe = xmlEscape(value)
    return """
        <c r="$reference" t="inlineStr">
          <is><t xml:space="preserve">$safe</t></is>
        </c>
    """.trimIndent()
}

private fun writeZipEntry(
    zip: ZipOutputStream,
    name: String,
    content: String
) {
    zip.putNextEntry(ZipEntry(name))
    zip.write(content.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}

private fun writeXlsx(
    output: OutputStream,
    contacts: List<Contact>
) {
    ZipOutputStream(output).use { zip ->

        val contentTypes = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
              <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
            </Types>
        """.trimIndent()

        val rootRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
                Target="xl/workbook.xml"/>
            </Relationships>
        """.trimIndent()

        val workbook = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
                <sheet name="Contacts" sheetId="1" r:id="rId1"/>
              </sheets>
            </workbook>
        """.trimIndent()

        val workbookRels = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
                Target="worksheets/sheet1.xml"/>
            </Relationships>
        """.trimIndent()

        val rows = StringBuilder()
        rows.append("<row r=\"1\">")
        rows.append(excelCell("A1", "Name"))
        rows.append(excelCell("B1", "Phone"))
        rows.append("</row>")

        var rowNumber = 2

        contacts.forEach { contact ->
            if (contact.phones.isEmpty()) {
                rows.append("<row r=\"$rowNumber\">")
                rows.append(excelCell("A$rowNumber", contact.name))
                rows.append(excelCell("B$rowNumber", ""))
                rows.append("</row>")
                rowNumber++
            } else {
                contact.phones.forEach { phone ->
                    rows.append("<row r=\"$rowNumber\">")
                    rows.append(excelCell("A$rowNumber", contact.name))
                    rows.append(excelCell("B$rowNumber", phone))
                    rows.append("</row>")
                    rowNumber++
                }
            }
        }

        val lastRow = (rowNumber - 1).coerceAtLeast(1)
        val worksheet = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <dimension ref="A1:B$lastRow"/>
              <sheetViews>
                <sheetView workbookViewId="0"/>
              </sheetViews>
              <sheetFormatPr defaultRowHeight="18"/>
              <cols>
                <col min="1" max="1" width="28" customWidth="1"/>
                <col min="2" max="2" width="22" customWidth="1"/>
              </cols>
              <sheetData>$rows</sheetData>
            </worksheet>
        """.trimIndent()

        writeZipEntry(zip, "[Content_Types].xml", contentTypes)
        writeZipEntry(zip, "_rels/.rels", rootRels)
        writeZipEntry(zip, "xl/workbook.xml", workbook)
        writeZipEntry(zip, "xl/_rels/workbook.xml.rels", workbookRels)
        writeZipEntry(zip, "xl/worksheets/sheet1.xml", worksheet)
    }
}
