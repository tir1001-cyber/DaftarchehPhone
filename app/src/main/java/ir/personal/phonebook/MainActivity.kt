
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
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.util.Locale
import java.text.Collator
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

internal fun normalizePhone(phone: String): String {
    val converted = phone
        .replace('۰', '0').replace('۱', '1').replace('۲', '2')
        .replace('۳', '3').replace('۴', '4').replace('۵', '5')
        .replace('۶', '6').replace('۷', '7').replace('۸', '8')
        .replace('۹', '9')
        .replace('٠', '0').replace('١', '1').replace('٢', '2')
        .replace('٣', '3').replace('٤', '4').replace('٥', '5')
        .replace('٦', '6').replace('٧', '7').replace('٨', '8')
        .replace('٩', '9')
    return converted.filter { it.isDigit() || it == '+' }
}

internal fun normalizeContactName(value: String): String =
    value.trim()
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
        .replace(Regex("\\s+"), " ")

private fun splitPhones(text: String): List<String> =
    text.split('\n', ',', ';', '|')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinctBy { normalizePhone(it) }

private fun mergeDuplicates(input: List<Contact>): List<Contact> {
    if (input.size < 2) return input

    // Union-find over contacts. A contact joins another only through a shared
    // normalized phone AND the same normalized name. This is near-linear in
    // the number of phone entries and avoids repeated full-list scans.
    val parent = IntArray(input.size) { it }
    val rank = ByteArray(input.size)

    fun find(value: Int): Int {
        var node = value
        while (parent[node] != node) {
            parent[node] = parent[parent[node]]
            node = parent[node]
        }
        return node
    }

    fun union(first: Int, second: Int) {
        var a = find(first)
        var b = find(second)
        if (a == b) return
        if (rank[a] < rank[b]) {
            val swap = a
            a = b
            b = swap
        }
        parent[b] = a
        if (rank[a] == rank[b]) rank[a] = (rank[a] + 1).toByte()
    }

    val ownerByNameAndPhone = HashMap<String, Int>()
    input.forEachIndexed { index, contact ->
        val normalizedName = normalizeContactName(contact.name)
        if (normalizedName.isBlank()) return@forEachIndexed
        contact.phones.asSequence()
            .map(::normalizePhone)
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { phone ->
                val key = normalizedName + "\u0000" + phone
                val previous = ownerByNameAndPhone.putIfAbsent(key, index)
                if (previous != null) union(index, previous)
            }
    }

    val groups = LinkedHashMap<Int, MutableList<Contact>>()
    input.indices.forEach { index ->
        groups.getOrPut(find(index)) { mutableListOf() }.add(input[index])
    }

    return groups.values.map { group ->
        if (group.size == 1) group.first()
        else {
            val mergedPhones = LinkedHashMap<String, String>()
            group.forEach { contact ->
                contact.phones.forEach { phone ->
                    val key = normalizePhone(phone)
                    if (key.isNotBlank()) mergedPhones.putIfAbsent(key, phone)
                }
            }
            group.first().copy(phones = mergedPhones.values.toList())
        }
    }
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

private fun decodeVcfValue(value: String, parameters: String): String {
    val quotedPrintable = parameters.contains("QUOTED-PRINTABLE", ignoreCase = true) ||
        parameters.contains("ENCODING=QP", ignoreCase = true)
    val charsetName = Regex("""CHARSET\s*=\s*"?([^;:"]+)""", RegexOption.IGNORE_CASE)
        .find(parameters)?.groupValues?.getOrNull(1) ?: "UTF-8"

    val decoded = if (quotedPrintable) decodeQuotedPrintable(value, charsetName) else value
    return decoded
        .replace("\\n", "\n", ignoreCase = true)
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
}

private fun decodeQuotedPrintable(value: String, charsetName: String): String {
    val charset = runCatching { Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
    val output = ByteArrayOutputStream()
    var index = 0

    while (index < value.length) {
        if (value[index] == '=' && index + 2 < value.length) {
            val high = value[index + 1].digitToIntOrNull(16)
            val low = value[index + 2].digitToIntOrNull(16)
            if (high != null && low != null) {
                output.write((high shl 4) or low)
                index += 3
                continue
            }
        }

        output.write(value[index].toString().toByteArray(charset))
        index++
    }

    return String(output.toByteArray(), charset)
}

private fun decodeImportBytes(bytes: ByteArray): String {
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }

    // Detect common BOM-less UTF-16 exports (many spreadsheet/contact apps use these).
    val sample = minOf(bytes.size, 200)
    var evenZeros = 0
    var oddZeros = 0
    for (i in 0 until sample) {
        if (bytes[i] == 0.toByte()) {
            if (i % 2 == 0) evenZeros++ else oddZeros++
        }
    }
    if (oddZeros > sample / 8 && oddZeros > evenZeros * 2) {
        return String(bytes, Charsets.UTF_16LE)
    }
    if (evenZeros > sample / 8 && evenZeros > oddZeros * 2) {
        return String(bytes, Charsets.UTF_16BE)
    }

    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }
    return utf8.getOrElse {
        // Legacy Persian Windows exports are commonly encoded as Windows-1256.
        String(bytes, Charset.forName("windows-1256"))
    }
}

private fun parseVcf(text: String): List<Contact> {
    // vCard 2.1 quoted-printable soft line breaks and standard folded lines.
    val unfolded = text
        .replace("=\r\n", "")
        .replace("=\n", "")
        .replace("\r\n ", "")
        .replace("\n ", "")
        .replace("\r\n\t", "")
        .replace("\n\t", "")

    val result = mutableListOf<Contact>()
    var name = ""
    var phones = mutableListOf<String>()
    var insideCard = false

    unfolded.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd('\r')
        when {
            line.equals("BEGIN:VCARD", ignoreCase = true) -> {
                insideCard = true
                name = ""
                phones = mutableListOf()
            }
            line.equals("END:VCARD", ignoreCase = true) -> {
                if (insideCard && name.isNotBlank() && phones.isNotEmpty()) {
                    result.add(Contact(name = name.trim(), phones = phones.distinctBy(::normalizePhone)))
                }
                insideCard = false
            }
            insideCard && line.contains(':') -> {
                val colon = line.indexOf(':')
                val property = line.substring(0, colon)
                val parameters = property.substringAfter(';', "")
                val propertyName = property.substringBefore(';')
                    .substringAfterLast('.')
                    .uppercase(Locale.ROOT)
                val rawValue = line.substring(colon + 1)
                val value = decodeVcfValue(rawValue, parameters)

                when (propertyName) {
                    "FN" -> name = value
                    "N" -> if (name.isBlank()) {
                        val parts = value.split(';')
                        name = listOf(
                            parts.getOrNull(1), parts.getOrNull(0),
                            parts.getOrNull(2), parts.getOrNull(3), parts.getOrNull(4)
                        ).filterNotNull().filter { it.isNotBlank() }.joinToString(" ")
                    }
                    "TEL" -> if (value.isNotBlank()) phones.add(value.trim())
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
    val coroutineScope = rememberCoroutineScope()

    var data by remember { mutableStateOf(PhonebookData()) }
    var storageError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val initial = withContext(Dispatchers.IO) {
            runCatching { readPhonebook(context) }
        }
        initial.onSuccess { data = it }
            .onFailure { storageError = it.message ?: "خطای خواندن فایل اطلاعات" }
        loading = false
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
        if (loading) {
            message = "لطفاً تا پایان بارگذاری اطلاعات صبر کنید."
            return
        }
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

    var importing by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                importing = true
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)
                            ?.use { readBytesLimited(it) }
                            ?: throw IllegalStateException("فایل قابل خواندن نیست")
                        val excelContacts = parseExcelContacts(bytes)
                        if (excelContacts != null) {
                            excelContacts
                        } else {
                            val text = decodeImportBytes(bytes)
                            when {
                                text.trimStart().startsWith("{") ->
                                    appJson.decodeFromString<PhonebookData>(text).contacts
                                text.contains("BEGIN:VCARD", true) -> parseVcf(text)
                                else -> parseCsv(text)
                            }
                        }
                    }
                }
                result.onSuccess { imported ->
                    if (imported.isEmpty()) {
                        message = "مخاطبی برای ورود پیدا نشد."
                    } else {
                        save(data.copy(contacts = data.contacts + imported))
                        message = "${imported.size} مخاطب به فهرست فعلی اضافه شد؛ اطلاعات قبلی حذف نشد."
                    }
                }.onFailure { error ->
                    message = "ورود فایل ناموفق بود: ${error.message ?: "قالب فایل پشتیبانی نمی‌شود."}"
                }
                importing = false
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

    var visible by remember { mutableStateOf<List<Contact>>(emptyList()) }

    LaunchedEffect(data.contacts, data.trash, search, showTrash) {
        val source = if (showTrash) data.trash else data.contacts
        val query = search.trim()
        visible = withContext(Dispatchers.Default) {
            val filtered = if (query.isBlank()) source else source.filter { contact ->
                contact.name.contains(query, ignoreCase = true) ||
                    contact.phones.any { phone -> phone.contains(query) }
            }
            sortContactsAlphabetically(filtered)
        }
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
                if (loading) {
                    Text("در حال بارگذاری اطلاعات...")
                }

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

                        OutlinedButton(
                            enabled = !importing && !loading,
                            onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "application/vnd.ms-excel",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "text/vcard",
                                    "text/x-vcard",
                                    "text/csv",
                                    "text/*",
                                    "application/octet-stream"
                                )
                            )
                        }) {
                            Text(if (importing) "در حال ورود..." else "ورود فایل")
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

    var mergedPreview by remember { mutableStateOf<List<Contact>?>(null) }

    LaunchedEffect(showMergeConfirm, data.contacts) {
        mergedPreview = if (showMergeConfirm) {
            withContext(Dispatchers.Default) { mergeDuplicates(data.contacts) }
        } else null
    }

    if (showMergeConfirm) {
        val merged = mergedPreview
        val removedCount = merged?.let { data.contacts.size - it.size } ?: 0

        AlertDialog(
            onDismissRequest = { showMergeConfirm = false },
            title = { Text("ادغام مخاطبین تکراری") },
            text = {
                Text(
                    when {
                        merged == null -> "در حال بررسی مخاطبین؛ لطفاً کمی صبر کنید..."
                        removedCount > 0 -> "$removedCount مخاطب با نام و شماره تلفن یکسان شناسایی شد. ادغام انجام شود؟"
                        else -> "مخاطب تکراری با نام و شماره تلفن یکسان پیدا نشد."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = merged != null && removedCount > 0,
                    onClick = {
                        merged?.let { save(data.copy(contacts = it)) }
                        showMergeConfirm = false
                    }
                ) {
                    Text("تأیید ادغام")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirm = false }) {
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
                                val contactsToCopy = data.contacts.filter { it.id in selected }
                                coroutineScope.launch {
                                    val transfer = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val result = writeContactsToSim(context, contactsToCopy, target)
                                            val refreshedTargets = findAvailableSimTargets(context)
                                            result to refreshedTargets
                                        }
                                    }
                                    transfer.onSuccess { (result, refreshedTargets) ->
                                        simTargets = refreshedTargets
                                        message = "انتقال به سیم‌کارت " + (target.slotIndex + 1) +
                                            " انجام شد: " + result.successCount + " شماره موفق، " +
                                            result.failedCount + " ناموفق."
                                        if (result.skippedDuplicateCount > 0) {
                                            message += " " + result.skippedDuplicateCount + " شمارهٔ تکراری از قبل روی سیم‌کارت بود و دوباره درج نشد."
                                        }
                                        if (result.failedCount > 0) {
                                            message += " ممکن است ظرفیت یا محدودیت نام/شماره مانع بعضی موارد شده باشد."
                                        }
                                        message += " اطلاعات قبلی حذف نشده است. اگر مخاطبین جدید در برنامه تلفن دیده نمی‌شوند، فهرست مخاطبین سیم‌کارت را دوباره باز یا تازه‌سازی کن؛ نمایش آن به گوشی و برنامه مخاطبین بستگی دارد."
                                        selected = emptySet()
                                    }.onFailure { error ->
                                        message = "انتقال انجام نشد: " +
                                            (error.message ?: "خطای سیم‌کارت") + ". خروجی VCF راه جایگزین است."
                                    }
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
