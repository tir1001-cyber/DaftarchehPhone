
package ir.personal.phonebook

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
data class Contact(
    val name: String,
    val phone: String,
    val additionalPhones: List<String> = emptyList(),
    val nationalId: String = "",
    val cardNumber: String = "",
    val iban: String = ""
)

class MainActivity : ComponentActivity() {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private lateinit var dataFile: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataFile = File(filesDir, "contacts.json")
        setContent {
            PhonebookScreen()
        }
    }

    private fun readContacts(): List<Contact> {
        if (!dataFile.exists()) return emptyList()

        return runCatching {
            json.decodeFromString<List<Contact>>(dataFile.readText())
        }.getOrElse {
            emptyList()
        }
    }

    private fun writeContacts(items: List<Contact>) {
        val temp = File(filesDir, "contacts.tmp")
        temp.writeText(json.encodeToString(items))

        if (dataFile.exists()) {
            dataFile.delete()
        }

        check(temp.renameTo(dataFile)) {
            "ذخیره اطلاعات انجام نشد."
        }
    }

    private fun normalize(value: String): String {
        return value.filterNot {
            it.isWhitespace() ||
                it == '-' ||
                it == '(' ||
                it == ')'
        }
    }

    private fun allPhones(contact: Contact): Set<String> {
        return (listOf(contact.phone) + contact.additionalPhones)
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun combineContacts(a: Contact, b: Contact): Contact {
        val phones = (listOf(a.phone) + a.additionalPhones +
            listOf(b.phone) + b.additionalPhones)
            .map { normalize(it) }
            .filter { it.isNotBlank() }
            .distinct()

        return Contact(
            name = a.name.ifBlank { b.name },
            phone = phones.firstOrNull() ?: "",
            additionalPhones = phones.drop(1),
            nationalId = a.nationalId.ifBlank { b.nationalId },
            cardNumber = a.cardNumber.ifBlank { b.cardNumber },
            iban = a.iban.ifBlank { b.iban }
        )
    }

    private fun mergeDuplicates(input: List<Contact>): List<Contact> {
        val result = input.toMutableList()

        var i = 0
        while (i < result.size) {
            var j = i + 1

            while (j < result.size) {
                val firstPhones = allPhones(result[i])
                val secondPhones = allPhones(result[j])

                if (firstPhones.intersect(secondPhones).isNotEmpty()) {
                    result[i] = combineContacts(result[i], result[j])
                    result.removeAt(j)
                } else {
                    j++
                }
            }

            i++
        }

        return result
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PhonebookScreen() {

        var contacts by remember {
            mutableStateOf(readContacts())
        }

        var search by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var phone by remember { mutableStateOf("") }
        var additionalPhonesText by remember { mutableStateOf("") }
        var nationalId by remember { mutableStateOf("") }
        var cardNumber by remember { mutableStateOf("") }
        var iban by remember { mutableStateOf("") }

        var editingPhone by remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf("") }
        var showEditor by remember { mutableStateOf(false) }

        fun persist(newList: List<Contact>) {
            runCatching {
                writeContacts(newList)
                contacts = newList
            }.onFailure {
                message = "خطا در ذخیره‌سازی: ${it.message}"
            }
        }

        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)
                        ?.bufferedWriter(Charsets.UTF_8)
                        ?.use {
                            it.write(json.encodeToString(contacts))
                        } ?: error("امکان نوشتن فایل وجود ندارد")

                    message = "خروجی با موفقیت ذخیره شد."
                }.onFailure {
                    message = "خطا در خروجی گرفتن: ${it.message}"
                }
            }
        }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                runCatching {
                    val raw = contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?: error("فایل خوانده نشد")

                    require(raw.toByteArray().size <= 2 * 1024 * 1024) {
                        "حجم فایل بیشتر از ۲ مگابایت است."
                    }

                    val incoming =
                        json.decodeFromString<List<Contact>>(raw)

                    require(incoming.size <= 10000) {
                        "تعداد مخاطبین بیش از حد مجاز است."
                    }

                    require(
                        incoming.all {
                            it.name.isNotBlank() &&
                                it.name.length <= 100 &&
                                it.phone.isNotBlank() &&
                                it.phone.length <= 30 &&
                                it.additionalPhones.size <= 20 &&
                                it.additionalPhones.all { p -> p.length <= 30 } &&
                                it.nationalId.length <= 20 &&
                                it.cardNumber.length <= 30 &&
                                it.iban.length <= 40
                        }
                    ) {
                        "ساختار فایل معتبر نیست."
                    }

                    val normalizedIncoming = incoming.map {
                        it.copy(
                            phone = normalize(it.phone),
                            additionalPhones = it.additionalPhones
                                .map { p -> normalize(p) }
                                .filter { p -> p.isNotBlank() }
                                .distinct()
                        )
                    }

                    val merged = mergeDuplicates(
                        contacts + normalizedIncoming
                    )

                    persist(merged)
                    message = "ورود انجام شد؛ ${incoming.size} مخاطب بررسی شد."
                }.onFailure {
                    message = "ورود ناموفق: ${it.message}"
                }
            }
        }

        val visible = contacts.filter {
            it.name.contains(search, ignoreCase = true) ||
                allPhones(it).any { p -> p.contains(normalize(search), ignoreCase = true) }
        }

        CompositionLocalProvider(
            androidx.compose.ui.platform.LocalLayoutDirection provides
                androidx.compose.ui.unit.LayoutDirection.Rtl
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text("دفترچه تلفن شخصی")
                        },
                        actions = {
                            TextButton(
                                onClick = {
                                    val merged = mergeDuplicates(contacts)
                                    val removed = contacts.size - merged.size

                                    if (removed > 0) {
                                        persist(merged)
                                        message = "$removed مخاطب تکراری ادغام شد."
                                    } else {
                                        message = "مخاطب تکراری بر اساس شماره پیدا نشد."
                                    }
                                }
                            ) {
                                Text("ادغام")
                            }

                            IconButton(
                                onClick = {
                                    exportLauncher.launch("contacts.json")
                                }
                            ) {
                                Icon(
                                    Icons.Default.UploadFile,
                                    contentDescription = "خروجی"
                                )
                            }

                            IconButton(
                                onClick = {
                                    importLauncher.launch(
                                        arrayOf(
                                            "application/json",
                                            "text/json",
                                            "text/plain"
                                        )
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = "ورود"
                                )
                            }
                        }
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = {
                            name = ""
                            phone = ""
                            additionalPhonesText = ""
                            nationalId = ""
                            cardNumber = ""
                            iban = ""
                            editingPhone = null
                            showEditor = true
                        }
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "افزودن"
                        )
                    }
                }
            ) { padding ->

                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                ) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("جستجوی نام یا شماره") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, null)
                        },
                        singleLine = true
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        "تعداد مخاطبین: ${contacts.size}",
                        style = MaterialTheme.typography.labelLarge
                    )

                    Spacer(Modifier.height(8.dp))

                    if (visible.isEmpty()) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                if (contacts.isEmpty())
                                    "هنوز مخاطبی ثبت نشده است."
                                else
                                    "موردی پیدا نشد."
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                visible,
                                key = { it.phone }
                            ) { contact ->

                                Card(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            Modifier.weight(1f)
                                        ) {
                                            Text(
                                                contact.name,
                                                style = MaterialTheme.typography.titleMedium
                                            )

                                            (listOf(contact.phone) +
                                                contact.additionalPhones)
                                                .filter { it.isNotBlank() }
                                                .forEach { number ->
                                                    Text(
                                                        number,
                                                        style = MaterialTheme.typography.bodyMedium
                                                    )
                                                }

                                            if (contact.nationalId.isNotBlank()) {
                                                Text("کد ملی: ${contact.nationalId}")
                                            }

                                            if (contact.cardNumber.isNotBlank()) {
                                                Text("کارت: ${contact.cardNumber}")
                                            }

                                            if (contact.iban.isNotBlank()) {
                                                Text("شبا: ${contact.iban}")
                                            }
                                        }

                                        IconButton(
                                            onClick = {
                                                name = contact.name
                                                phone = contact.phone
                                                additionalPhonesText =
                                                    contact.additionalPhones.joinToString("\n")
                                                nationalId = contact.nationalId
                                                cardNumber = contact.cardNumber
                                                iban = contact.iban
                                                editingPhone = contact.phone
                                                showEditor = true
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Edit,
                                                contentDescription = "ویرایش"
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                persist(
                                                    contacts.filterNot {
                                                        it.phone == contact.phone
                                                    }
                                                )
                                                message = "مخاطب حذف شد."
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "حذف"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showEditor) {
                AlertDialog(
                    onDismissRequest = {
                        showEditor = false
                    },
                    title = {
                        Text(
                            if (editingPhone == null)
                                "افزودن مخاطب"
                            else
                                "ویرایش مخاطب"
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 450.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("نام") },
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = phone,
                                onValueChange = { phone = it },
                                label = { Text("شماره اصلی تلفن") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Phone
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = additionalPhonesText,
                                onValueChange = {
                                    additionalPhonesText = it
                                },
                                label = {
                                    Text("شماره‌های دیگر")
                                },
                                placeholder = {
                                    Text("هر شماره را در یک خط وارد کنید")
                                },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Phone
                                ),
                                minLines = 2
                            )

                            Divider()

                            OutlinedTextField(
                                value = nationalId,
                                onValueChange = { nationalId = it },
                                label = { Text("کد ملی") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = cardNumber,
                                onValueChange = { cardNumber = it },
                                label = { Text("شماره کارت بانکی") },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = iban,
                                onValueChange = { iban = it },
                                label = { Text("شماره شبا") },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val n = name.trim()
                                val p = normalize(phone)

                                val extraPhones = additionalPhonesText
                                    .split(',', '\n', ';')
                                    .map { normalize(it) }
                                    .filter { it.isNotBlank() }
                                    .distinct()
                                    .filterNot { it == p }

                                val allNewPhones =
                                    (listOf(p) + extraPhones).toSet()

                                val otherContacts =
                                    contacts.filterNot {
                                        it.phone == editingPhone
                                    }

                                val duplicateExists =
                                    otherContacts.any { existing ->
                                        allPhones(existing).any {
                                            it in allNewPhones
                                        }
                                    }

                                if (n.isBlank() || p.isBlank()) {
                                    message = "نام و شماره اصلی را وارد کنید."
                                } else if (
                                    n.length > 100 ||
                                    p.length > 30 ||
                                    extraPhones.any { it.length > 30 } ||
                                    nationalId.length > 20 ||
                                    cardNumber.length > 30 ||
                                    iban.length > 40
                                ) {
                                    message = "طول یکی از اطلاعات بیش از حد مجاز است."
                                } else if (duplicateExists) {
                                    message = "یکی از شماره‌ها قبلاً برای مخاطب دیگری ثبت شده است."
                                } else {
                                    val updatedContact = Contact(
                                        name = n,
                                        phone = p,
                                        additionalPhones = extraPhones,
                                        nationalId = nationalId.trim(),
                                        cardNumber = cardNumber.trim(),
                                        iban = iban.trim()
                                    )

                                    val updated = otherContacts + updatedContact
                                    persist(updated)
                                    showEditor = false
                                    message = "مخاطب ذخیره شد."
                                }
                            }
                        ) {
                            Text("ذخیره")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showEditor = false
                            }
                        ) {
                            Text("انصراف")
                        }
                    }
                )
            }

            if (message.isNotBlank()) {
                AlertDialog(
                    onDismissRequest = {
                        message = ""
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                message = ""
                            }
                        ) {
                            Text("باشه")
                        }
                    },
                    text = {
                        Text(message)
                    }
                )
            }
        }
    }
}
