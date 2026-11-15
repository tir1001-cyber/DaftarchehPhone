
package ir.personal.phonebook

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
    var showSimInfo by remember { mutableStateOf(false) }

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

    val allItems = if (showTrash) data.trash else data.contacts

    val visible = allItems.filter {
        it.name.contains(search, ignoreCase = true) ||
            it.phones.any { phone -> phone.contains(search) }
    }

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
                            showSimInfo = true
                        }) {
                            Text("سیم‌کارت")
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

    if (showSimInfo) {
        AlertDialog(
            onDismissRequest = { showSimInfo = false },
            title = { Text("انتقال مخاطبین به سیم‌کارت") },
            text = {
                Text(
                    "دسترسی مستقیم به سیم‌کارت در نسخه‌های جدید اندروید محدود است. " +
                        "برای انتقال، ابتدا از مخاطبین خروجی VCF بگیر و سپس فایل را " +
                        "با برنامه مخاطبین گوشی باز کن. اگر گوشی امکان ذخیره روی سیم‌کارت " +
                        "را ارائه دهد، می‌توانی آن گزینه را انتخاب کنی."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSimInfo = false
                }) {
                    Text("متوجه شدم")
                }
            }
        )
    }
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
