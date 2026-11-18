
package ir.personal.phonebook

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.Locale
import java.text.Collator
import java.io.OutputStream
import java.util.UUID

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

private val appJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun storageFile(context: android.content.Context) =
    AtomicFile(File(context.filesDir, "phonebook-final.json"))

private fun readPhonebook(context: android.content.Context): PhonebookData {
    val file = storageFile(context)

    if (!file.baseFile.exists()) return PhonebookData()

    return file.openRead().bufferedReader(Charsets.UTF_8).use {
        appJson.decodeFromString<PhonebookData>(it.readText())
    }
}

private fun writePhonebook(
    context: android.content.Context,
    data: PhonebookData
) {
    val file = storageFile(context)
    var stream: java.io.FileOutputStream? = null

    try {
        stream = file.startWrite()
        stream.write(
            appJson.encodeToString(data).toByteArray(Charsets.UTF_8)
        )
        stream.fd.sync()
        file.finishWrite(stream)
    } catch (e: Exception) {
        if (stream != null) file.failWrite(stream)
        throw e
    }
}

private fun normalizePhone(phone: String): String =
    phone.filter { it.isDigit() || it == '+' }

private fun splitPhones(text: String): List<String> =
    text.split('\n', ',', ';', '|')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { normalizePhone(it) }

private fun mergeDuplicates(input: List<Contact>): List<Contact> {
    val remaining = input.toMutableList()
    val result = mutableListOf<Contact>()

    while (remaining.isNotEmpty()) {
        val group = mutableListOf(remaining.removeAt(0))
        var changed: Boolean

        do {
            changed = false

            val numbers = group.flatMap { it.phones }
                .map { normalizePhone(it) }
                .filter { it.isNotBlank() }
                .toSet()

            val iterator = remaining.iterator()

            while (iterator.hasNext()) {
                val candidate = iterator.next()

                val candidateNumbers = candidate.phones
                    .map { normalizePhone(it) }
                    .filter { it.isNotBlank() }
                    .toSet()

                if (numbers.intersect(candidateNumbers).isNotEmpty()) {
                    group.add(candidate)
                    iterator.remove()
                    changed = true
                }
            }
        } while (changed)

        val first = group.first()

        val phones = group.flatMap { it.phones }
            .distinctBy { normalizePhone(it) }

        result.add(first.copy(phones = phones))
    }

    return result
}

private fun csvEscape(value: String): String =
    "\"" + value.replace("\"", "\"\"") + "\""

private fun exportCsv(contacts: List<Contact>): String {
    val lines = mutableListOf("نام,شماره‌ها")

    contacts.forEach {
        lines.add(
            csvEscape(it.name) + "," +
                csvEscape(it.phones.joinToString(" | "))
        )
    }

    return "\uFEFF" + lines.joinToString("\r\n")
}

private fun parseCsv(text: String): List<Contact> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0

    while (i < text.length) {
        val c = text[i]

        when {
            c == '"' && quoted &&
                i + 1 < text.length &&
                text[i + 1] == '"' -> {
                cell.append('"')
                i++
            }

            c == '"' -> quoted = !quoted

            (c == ',' || c == '\n' || c == '\r') && !quoted -> {
                row.add(cell.toString())
                cell.clear()

                if (c != ',') {
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()

                    if (c == '\r' &&
                        i + 1 < text.length &&
                        text[i + 1] == '\n'
                    ) {
                        i++
                    }
                }
            }

            else -> cell.append(c)
        }

        i++
    }

    row.add(cell.toString())

    if (row.any { it.isNotBlank() }) rows.add(row)

    return rows.drop(1).mapNotNull { columns ->
        if (columns.size < 2) return@mapNotNull null

        val name = columns[0].trim()
        val phones = splitPhones(columns[1])

        if (name.isBlank() || phones.isEmpty()) null
        else Contact(name = name, phones = phones)
    }
}

private fun vcfEscape(value: String): String =
    value.replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(",", "\\,")
        .replace(";", "\\;")

private fun exportVcf(contacts: List<Contact>): String {
    val output = StringBuilder()

    contacts.forEach { contact ->
        output.append("BEGIN:VCARD\r\n")
        output.append("VERSION:3.0\r\n")
        output.append("FN:${vcfEscape(contact.name)}\r\n")

        contact.phones.forEach { phone ->
            output.append("TEL;TYPE=CELL:${vcfEscape(phone)}\r\n")
        }

        output.append("END:VCARD\r\n")
    }

    return output.toString()
}

private fun parseVcf(text: String): List<Contact> {
    val unfolded = text.replace("\r\n ", "")
        .replace("\n ", "")

    val result = mutableListOf<Contact>()

    var name = ""
    var phones = mutableListOf<String>()

    unfolded.lines().forEach { line ->
        when {
            line.startsWith("BEGIN:VCARD", true) -> {
                name = ""
                phones = mutableListOf()
            }

            line.startsWith("FN:", true) -> {
                name = line.substringAfter(":")
                    .replace("\\n", "\n")
                    .replace("\\,", ",")
            }

            line.startsWith("N:", true) && name.isBlank() -> {
                val parts = line.substringAfter(":").split(";")

                name = parts.reversed()
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }

            line.startsWith("TEL", true) && line.contains(":") -> {
                val phone = line.substringAfter(":").trim()

                if (phone.isNotBlank()) phones.add(phone)
            }

            line.startsWith("END:VCARD", true) -> {
                if (name.isNotBlank() && phones.isNotEmpty()) {
                    result.add(
                        Contact(
                            name = name,
                            phones = phones.distinct()
                        )
                    )
                }
            }
        }
    }

    return result
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            PhonebookApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhonebookApp() {
    val context = LocalContext.current

    val initial = remember {
        runCatching { readPhonebook(context) }
    }

    var data by remember {
        mutableStateOf(initial.getOrElse { PhonebookData() })
    }

    var storageError by remember {
        mutableStateOf(initial.exceptionOrNull()?.message)
    }

    var message by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var showTrash by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    var showEditor by remember { mutableStateOf(false) }
    var editingContact by remember { mutableStateOf<Contact?>(null) }
    var showMergeConfirm by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showSimConfirmation by remember { mutableStateOf(false) }
    var showSimChooser by remember { mutableStateOf(false) }
    var simTargets by remember { mutableStateOf<List<SimTarget>>(emptyList()) }

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

    fun save(next: PhonebookData) {
        if (storageError != null) {
            message = "خطای خواندن اطلاعات وجود دارد؛ ذخیره‌سازی متوقف شده است."
            return
        }

        try {
            writePhonebook(context, next)
            data = next
            selected = emptySet()
            message = "اطلاعات با موفقیت ذخیره شد."
        } catch (e: Exception) {
            message = "خطا در ذخیره اطلاعات: ${e.localizedMessage ?: "نامشخص"}"
        }
    }

    fun importText(text: String) {
        try {
            val imported = when {
                text.trimStart().startsWith("{") ->
                    appJson.decodeFromString<PhonebookData>(text).contacts

                text.contains("BEGIN:VCARD", true) ->
                    parseVcf(text)

                else -> parseCsv(text)
            }

            if (imported.isEmpty()) {
                message = "مخاطبی برای ورود پیدا نشد."
                return
            }

            save(data.copy(contacts = data.contacts + imported))
            message = "${imported.size} مخاطب وارد شد."

        } catch (e: Exception) {
            message = "فایل قابل خواندن نیست: ${e.localizedMessage ?: "خطای قالب"}"
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }

                if (text == null) {
                    message = "خواندن فایل ناموفق بود."
                } else {
                    importText(text)
                }
            } catch (e: Exception) {
                message = "خطا در خواندن فایل."
            }
        }
    }

    val jsonExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use {
                    it.write(
                        appJson.encodeToString(data)
                            .toByteArray(Charsets.UTF_8)
                    )
                }
            }.onSuccess {
                message = "فایل JSON ذخیره شد."
            }.onFailure {
                message = "ذخیره فایل ناموفق بود."
            }
        }
    }

    val vcfExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/vcard")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use {
                    it.write(
                        exportVcf(data.contacts)
                            .toByteArray(Charsets.UTF_8)
                    )
                }
            }.onSuccess {
                message = "فایل VCF ذخیره شد."
            }.onFailure {
                message = "ذخیره فایل ناموفق بود."
            }
        }
    }

    val csvExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)!!.use {
                    it.write(
                        exportCsv(data.contacts)
                            .toByteArray(Charsets.UTF_8)
                    )
                }
            }.onSuccess {
                message = "فایل CSV سازگار با Excel ذخیره شد."
            }.onFailure {
                message = "ذخیره فایل ناموفق بود."
            }
        }
    }

    val xlsxExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    writeXlsx(output, sortContactsAlphabetically(data.contacts))
                } ?: error("فایل خروجی باز نشد.")
            }.onSuccess {
                message = "فایل Excel با فرمت XLSX ذخیره شد."
            }.onFailure {
                message = "خطا در ساخت فایل XLSX: " + (it.message ?: "نامشخص")
            }
        }
    }

    val allItems = if (showTrash) data.trash else data.contacts

    val visible = sortContactsAlphabetically(allItems.filter {
        it.name.contains(search, ignoreCase = true) ||
            it.phones.any { phone -> phone.contains(search) }
    })

    val colors = lightColorScheme(
        primary = Color(0xFF8863B5),
        secondary = Color(0xFFE18CB4),
        tertiary = Color(0xFFB49AD8),
        background = Color(0xFFFFF8FC),
        surface = Color.White
    )

    MaterialTheme(colorScheme = colors) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                Text(
                    "دفترچه تلفن شخصی",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    if (showTrash) "سطل زباله" else "مخاطبین",
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !showTrash,
                        onClick = {
                            showTrash = false
                            selected = emptySet()
                        },
                        label = { Text("مخاطبین") }
                    )

                    FilterChip(
                        selected = showTrash,
                        onClick = {
                            showTrash = true
                            selected = emptySet()
                        },
                        label = { Text("سطل زباله") }
                    )
                }

                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("جست‌وجوی نام یا شماره") },
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!showTrash) {
                        Button(onClick = {
                            editingContact = null
                            showEditor = true
                        }) {
                            Text("مخاطب جدید")
                        }

                        OutlinedButton(onClick = {
                            selected =
                                if (visible.isNotEmpty() &&
                                    visible.all { it.id in selected }
                                ) {
                                    selected - visible.map { it.id }.toSet()
                                } else {
                                    selected + visible.map { it.id }
                                }
                        }) {
                            Text("انتخاب همه")
                        }

                        OutlinedButton(onClick = {
                            showMergeConfirm = true
                        }) {
                            Text("ادغام تکراری‌ها")
                        }

                        OutlinedButton(onClick = {
                            showExport = true
                        }) {
                            Text("خروجی")
                        }

                        OutlinedButton(onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "text/vcard",
                                    "text/x-vcard",
                                    "text/csv",
                                    "text/*",
                                    "application/octet-stream"
                                )
                            )
                        }) {
                            Text("ورود فایل")
                        }

                        OutlinedButton(onClick = {
                            if (selected.isNotEmpty()) showSimConfirmation = true
                            else message = "ابتدا مخاطبین موردنظر را انتخاب کنید."
                        }) {
                            Text("انتقال به سیم‌کارت")
                        }
                    } else {
                        OutlinedButton(onClick = {
                            selected =
                                if (visible.isNotEmpty() &&
                                    visible.all { it.id in selected }
                                ) {
                                    selected - visible.map { it.id }.toSet()
                                } else {
                                    selected + visible.map { it.id }
                                }
                        }) {
                            Text("انتخاب همه")
                        }
                    }
                }

                if (selected.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))

                    Text("تعداد انتخاب‌شده: ${selected.size}")

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (showTrash) {
                            Button(onClick = {
                                val restored =
                                    data.trash.filter { it.id in selected }

                                save(
                                    data.copy(
                                        contacts = data.contacts + restored,
                                        trash = data.trash.filterNot {
                                            it.id in selected
                                        }
                                    )
                                )
                            }) {
                                Text("بازیابی")
                            }

                            Button(
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFB94D65)
                                ),
                                onClick = {
                                    save(
                                        data.copy(
                                            trash = data.trash.filterNot {
                                                it.id in selected
                                            }
                                        )
                                    )
                                }
                            ) {
                                Text("حذف دائمی")
                            }
                        } else {
                            Button(onClick = {
                                val removed =
                                    data.contacts.filter { it.id in selected }

                                save(
                                    data.copy(
                                        contacts = data.contacts.filterNot {
                                            it.id in selected
                                        },
                                        trash = data.trash + removed
                                    )
                                )
                            }) {
                                Text("انتقال به زباله")
                            }
                        }
                    }
                }

                if (storageError != null) {
                    Text(
                        "هشدار: فایل اطلاعات خوانده نشد. ذخیره‌سازی متوقف شده است.",
                        color = Color.Red
                    )
                }

                if (message.isNotBlank()) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visible, key = { it.id }) { contact ->
                        ContactCard(
                            contact = contact,
                            checked = contact.id in selected,
                            onChecked = { checked ->
                                selected =
                                    if (checked) selected + contact.id
                                    else selected - contact.id
                            },
                            inTrash = showTrash,
                            onEdit = {
                                editingContact = contact
                                showEditor = true
                            },
                            onDial = { phone ->
                                try {
                                    val intent = Intent(
                                        Intent.ACTION_DIAL,
                                        Uri.parse("tel:${Uri.encode(phone)}")
                                    )
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    message = "باز کردن شماره‌گیر امکان‌پذیر نشد."
                                }
                            }
                        )
                    }
                }

                Text(
                    "تعداد مخاطبین: ${data.contacts.size} | زباله: ${data.trash.size}",
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }

    if (showEditor) {
        ContactEditorDialog(
            contact = editingContact,
            onDismiss = { showEditor = false },
            onSave = { name, phones ->
                val old = editingContact

                val updated = if (old == null) {
                    Contact(name = name, phones = phones)
                } else {
                    old.copy(name = name, phones = phones)
                }

                val list = if (old == null) {
                    data.contacts + updated
                } else {
                    data.contacts.map {
                        if (it.id == old.id) updated else it
                    }
                }

                save(data.copy(contacts = list))
                showEditor = false
            }
        )
    }

    if (showMergeConfirm) {
        val merged = remember(data.contacts) {
            mergeDuplicates(data.contacts)
        }

        val removedCount = data.contacts.size - merged.size

        AlertDialog(
            onDismissRequest = { showMergeConfirm = false },
            title = { Text("ادغام مخاطبین تکراری") },
            text = {
                Text(
                    if (removedCount > 0)
                        "$removedCount مخاطب تکراری شناسایی شد. ادغام انجام شود؟"
                    else
                        "مخاطب تکراری بر اساس شماره تلفن مشترک پیدا نشد."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = removedCount > 0,
                    onClick = {
                        save(data.copy(contacts = merged))
                        showMergeConfirm = false
                    }
                ) {
                    Text("تأیید ادغام")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showMergeConfirm = false
                }) {
                    Text("انصراف")
                }
            }
        )
    }

    if (showExport) {
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("خروجی گرفتن از مخاطبین") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showExport = false
                            jsonExport.launch("phonebook.json")
                        }
                    ) {
                        Text("خروجی JSON")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showExport = false
                            vcfExport.launch("phonebook.vcf")
                        }
                    ) {
                        Text("خروجی VCF")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showExport = false
                            csvExport.launch("phonebook.csv")
                        }
                    ) {
                        Text("CSV سازگار با Excel")
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showExport = false
                            xlsxExport.launch("phonebook-contacts.xlsx")
                        }
                    ) {
                        Text("خروجی Excel (XLSX)")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showExport = false
                }) {
                    Text("بستن")
                }
            }
        )
    }

    if (showSimConfirmation) {
        val selectedContacts = data.contacts.filter { it.id in selected }
        val selectedPhoneCount = selectedContacts.sumOf { it.phones.size }
        AlertDialog(
            onDismissRequest = { showSimConfirmation = false },
            title = { Text("انتقال به سیم‌کارت") },
            text = { Text("قرار است " + selectedContacts.size + " مخاطب و " + selectedPhoneCount + " شماره تلفن از موارد انتخاب‌شده به سیم‌کارت کپی شود. اطلاعات فعلی سیم‌کارت حذف نمی‌شود. ادامه می‌دهی؟") },
            confirmButton = {
                TextButton(onClick = {
                    showSimConfirmation = false
                    simPermissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS))
                }) { Text("ادامه") }
            },
            dismissButton = { TextButton(onClick = { showSimConfirmation = false }) { Text("انصراف") } }
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
                                    val contactsToCopy = data.contacts.filter { it.id in selected }
                                    val result = writeContactsToSim(context, contactsToCopy, target)
                                    message = "انتقال به سیم‌کارت " + (target.slotIndex + 1) + " انجام شد: " + result.successCount + " شماره موفق، " + result.failedCount + " ناموفق."
                                    if (result.failedCount > 0) message += " ممکن است ظرفیت یا محدودیت نام/شماره مانع بعضی موارد شده باشد."
                                    selected = emptySet()
                                } catch (e: Exception) {
                                    message = "انتقال انجام نشد: " + (e.message ?: "خطای سیم‌کارت") + ". خروجی VCF راه جایگزین است."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("سیم‌کارت " + (target.slotIndex + 1) + " — ظرفیت آزاد: " + free + " از " + target.maxRecords) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSimChooser = false }) { Text("بستن") } }
        )
    }
}

private fun sortContactsAlphabetically(contacts: List<Contact>): List<Contact> {
    val collator = Collator.getInstance(Locale("fa")).apply {
        strength = Collator.PRIMARY
        decomposition = Collator.CANONICAL_DECOMPOSITION
    }
    fun normalized(name: String): String = name.trim().replace('ي', 'ی').replace('ك', 'ک')
    return contacts.sortedWith(Comparator { first, second ->
        val left = normalized(first.name)
        val right = normalized(second.name)
        val result = collator.compare(left, right)
        if (result != 0) result
        else left.lowercase(Locale.ROOT).compareTo(right.lowercase(Locale.ROOT))
    })
}
@Composable
private fun ContactCard(
    contact: Contact,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    inTrash: Boolean,
    onEdit: () -> Unit,
    onDial: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF6EFFB)
        )
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onChecked
                )

                Text(
                    contact.name,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF65468D)
                )

                if (!inTrash) {
                    TextButton(onClick = onEdit) {
                        Text("ویرایش")
                    }
                }
            }

            contact.phones.forEach { phone ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            phone,
                            modifier = Modifier.weight(1f),
                            color = Color.DarkGray
                        )

                        if (!inTrash) {
                            TextButton(
                                onClick = { onDial(phone) }
                            ) {
                                Text("☎ شماره‌گیر")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactEditorDialog(
    contact: Contact?,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit
) {
    var name by remember(contact?.id) {
        mutableStateOf(contact?.name.orEmpty())
    }

    var phonesText by remember(contact?.id) {
        mutableStateOf(contact?.phones?.joinToString("\n").orEmpty())
    }

    val phones = splitPhones(phonesText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (contact == null) "مخاطب جدید" else "ویرایش مخاطب")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("نام مخاطب") },
                    singleLine = true
                )

                OutlinedTextField(
                    value = phonesText,
                    onValueChange = { phonesText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("شماره‌ها؛ هر شماره در یک خط") },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone
                    )
                )

                Text(
                    "برای افزودن شماره جدید، آن را در خط بعدی بنویس.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && phones.isNotEmpty(),
                onClick = {
                    onSave(name.trim(), phones)
                }
            ) {
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
