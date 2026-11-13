package ir.personal.phonebook

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Contact(val name: String, val phone: String)

class MainActivity : ComponentActivity() {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private lateinit var dataFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataFile = File(filesDir, "contacts.json")
        setContent { PhonebookScreen() }
    }

    private fun readContacts(): List<Contact> {
        if (!dataFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<Contact>>(dataFile.readText()) }
            .getOrElse { emptyList() }
    }

    private fun writeContacts(items: List<Contact>) {
        val temp = File(filesDir, "contacts.tmp")
        temp.writeText(json.encodeToString(items))
        if (dataFile.exists()) dataFile.delete()
        check(temp.renameTo(dataFile)) { "ذخیره اطلاعات انجام نشد." }
    }

    private fun normalize(value: String) =
        value.filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PhonebookScreen() {
        var contacts by remember { mutableStateOf(readContacts()) }
        var search by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var editingPhone by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf("") }
        var showEditor by remember { mutableStateOf(false) }

        fun persist(newList: List<Contact>) {
            runCatching { writeContacts(newList); contacts = newList }
                .onFailure { message = "خطا در ذخیره‌سازی: ${it.message}" }
        }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) runCatching {
                contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(json.encodeToString(contacts))
                } ?: error("امکان نوشتن فایل وجود ندارد")
                message = "خروجی با موفقیت ذخیره شد."
            }.onFailure { message = "خطا در خروجی گرفتن: ${it.message}" }
        }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) runCatching {
                val raw = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: error("فایل خوانده نشد")
                require(raw.toByteArray().size <= 2 * 1024 * 1024) { "حجم فایل بیشتر از ۲ مگابایت است." }
                val incoming = json.decodeFromString<List<Contact>>(raw)
                require(incoming.size <= 10000) { "تعداد مخاطبین بیش از حد مجاز است." }
                require(incoming.all { it.name.isNotBlank() && it.name.length <= 100 && it.phone.isNotBlank() && it.phone.length <= 30 }) {
                    "ساختار فایل معتبر نیست."
                }
                val merged = contacts.associateBy { normalize(it.phone) }.toMutableMap()
                incoming.forEach { item -> merged[normalize(item.phone)] = item.copy(phone = normalize(item.phone)) }
                persist(merged.values.toList())
                message = "ورود انجام شد؛ ${incoming.size} مخاطب بررسی شد."
            }.onFailure { message = "ورود ناموفق: ${it.message}" }
        }

        val visible = contacts.filter {
            it.name.contains(search, ignoreCase = true) || it.phone.contains(search, ignoreCase = true)
        }

        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("دفترچه تلفن شخصی") },
                        actions = {
                            IconButton(onClick = { exportLauncher.launch("contacts.json") }) {
                                Icon(Icons.Default.UploadFile, contentDescription = "خروجی")
                            }
                            IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) }) {
                                Icon(Icons.Default.Download, contentDescription = "ورود")
                            }
                        }
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(onClick = {
                        name = ""; phone = ""; editingPhone = null; showEditor = true
                    }) { Icon(Icons.Default.Add, contentDescription = "افزودن") }
                }
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("جستجوی نام یا شماره") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("تعداد مخاطبین: ${contacts.size}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(if (contacts.isEmpty()) "هنوز مخاطبی ثبت نشده است." else "موردی پیدا نشد.")
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visible, key = { it.phone }) { contact ->
                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                                            Text(contact.phone, style = MaterialTheme.typography.bodyMedium)
                                        }
                                        IconButton(onClick = {
                                            name = contact.name; phone = contact.phone
                                            editingPhone = contact.phone; showEditor = true
                                        }) { Icon(Icons.Default.Edit, contentDescription = "ویرایش") }
                                        IconButton(onClick = {
                                            persist(contacts.filterNot { it.phone == contact.phone })
                                            message = "مخاطب حذف شد."
                                        }) { Icon(Icons.Default.Delete, contentDescription = "حذف") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showEditor) {
                AlertDialog(
                    onDismissRequest = { showEditor = false },
                    title = { Text(if (editingPhone == null) "افزودن مخاطب" else "ویرایش مخاطب") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(name, { name = it }, label = { Text("نام") }, singleLine = true)
                            OutlinedTextField(
                                phone, { phone = it },
                                label = { Text("شماره تلفن") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val n = name.trim()
                            val p = normalize(phone)
                            if (n.isBlank() || p.isBlank()) {
                                message = "نام و شماره را وارد کنید."
                            } else if (contacts.any { normalize(it.phone) == p && it.phone != editingPhone }) {
                                message = "این شماره قبلاً ثبت شده است."
                            } else {
                                val updated = contacts.filterNot { it.phone == editingPhone } + Contact(n, p)
                                persist(updated)
                                showEditor = false
                                message = "مخاطب ذخیره شد."
                            }
                        }) { Text("ذخیره") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEditor = false }) { Text("انصراف") }
                    }
                )
            }

            if (message.isNotBlank()) {
                AlertDialog(
                    onDismissRequest = { message = "" },
                    confirmButton = { TextButton(onClick = { message = "" }) { Text("باشه") } },
                    text = { Text(message) }
                )
            }
        }
    }
}
