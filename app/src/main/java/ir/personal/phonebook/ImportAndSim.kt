package ir.personal.phonebook

import android.content.ContentValues
import android.content.Context
import android.provider.SimPhonebookContract
import android.database.Cursor
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

data class SimTarget(
    val subscriptionId: Int,
    val slotIndex: Int,
    val maxRecords: Int,
    val recordCount: Int
)

data class SimTransferResult(
    val successCount: Int,
    val failedCount: Int
)

private fun Cursor.intValue(column: String, fallback: Int = 0): Int {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getInt(index) else fallback
}

fun findAvailableSimTargets(context: Context): List<SimTarget> {
    val result = mutableListOf<SimTarget>()
    val cursor = context.contentResolver.query(
        SimPhonebookContract.ElementaryFiles.CONTENT_URI,
        arrayOf(
            SimPhonebookContract.ElementaryFiles.SUBSCRIPTION_ID,
            SimPhonebookContract.ElementaryFiles.EF_TYPE,
            SimPhonebookContract.ElementaryFiles.SLOT_INDEX,
            SimPhonebookContract.ElementaryFiles.MAX_RECORDS,
            SimPhonebookContract.ElementaryFiles.RECORD_COUNT
        ),
        null,
        null,
        null
    ) ?: return emptyList()

    cursor.use {
        while (it.moveToNext()) {
            if (it.intValue(ef.EF_TYPE, -1) != SimPhonebookContract.ElementaryFiles.EF_ADN) continue
            val subId = it.intValue(ef.SUBSCRIPTION_ID, -1)
            if (subId < 0) continue
            result.add(
                SimTarget(
                    subscriptionId = subId,
                    slotIndex = it.intValue(ef.SLOT_INDEX, result.size),
                    maxRecords = it.intValue(ef.MAX_RECORDS),
                    recordCount = it.intValue(ef.RECORD_COUNT)
                )
            )
        }
    }
    return result.distinctBy { it.subscriptionId }.sortedBy { it.slotIndex }
}

fun writeContactsToSim(
    context: Context,
    contacts: List<Contact>,
    target: SimTarget
): SimTransferResult {
    val records = contacts.flatMap { contact ->
        contact.phones.map { phone -> contact.name to phone }
    }
    var success = 0
    var failed = 0
    val free = (target.maxRecords - target.recordCount).coerceAtLeast(0)
    val uri = SimPhonebookContract.SimRecords.getContentUri(
        target.subscriptionId,
        SimPhonebookContract.ElementaryFiles.EF_ADN
    )

    records.forEachIndexed { index, (name, rawPhone) ->
        if (index >= free) {
            failed++
        } else {
            try {
                val phone = normalizePhoneForTransfer(rawPhone)
                if (phone.isBlank()) throw IllegalArgumentException("شماره نامعتبر")
                val values = ContentValues().apply {
                    put(SimPhonebookContract.SimRecords.PHONE_NUMBER, phone)
                    put(SimPhonebookContract.SimRecords.NAME, name)
                }
                val inserted = context.contentResolver.insert(uri, values)
                if (inserted != null) success++ else failed++
            } catch (_: Exception) {
                failed++
            }
        }
    }
    return SimTransferResult(success, failed)
}

/**
 * Reads ordinary XLSX workbooks (Office Open XML). Values are kept as strings
 * so phone numbers, including leading zeroes, are not converted to numbers.
 */
fun parseXlsx(bytes: ByteArray): List<Contact> {
    val entries = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory) {
                val name = entry.name.replace('\\', '/')
                if (name == "xl/sharedStrings.xml" ||
                    name == "xl/workbook.xml" ||
                    name == "xl/_rels/workbook.xml.rels" ||
                    name.matches(Regex("xl/worksheets/[^/]+\\.xml"))
                ) {
                    entries[name] = zip.readBytes()
                }
            }
            zip.closeEntry()
        }
    }

    val sheetPath = resolveFirstSheet(entries)
        ?: entries.keys.firstOrNull { it.startsWith("xl/worksheets/") }
        ?: throw IllegalArgumentException("برگه‌ای در فایل اکسل پیدا نشد")
    val sheetBytes = entries[sheetPath]
        ?: throw IllegalArgumentException("محتوای برگه اکسل موجود نیست")
    val shared = entries["xl/sharedStrings.xml"]?.let(::readSharedStrings).orEmpty()
    val rows = readWorksheet(sheetBytes, shared)
    if (rows.isEmpty()) return emptyList()

    val first = rows.first()
    val normalized = first.map { it.trim().lowercase() }
    val nameIndex = normalized.indexOfFirst {
        it == "name" || it == "نام" || it == "نام مخاطب"
    }
    val phoneIndex = normalized.indexOfFirst {
        it == "phone" || it == "telephone" || it == "mobile" ||
            it == "شماره" || it == "شماره تلفن" || it == "موبایل"
    }
    val hasHeader = nameIndex >= 0 || phoneIndex >= 0
    val actualNameIndex = if (nameIndex >= 0) nameIndex else 0
    val actualPhoneIndex = if (phoneIndex >= 0) phoneIndex else 1
    val grouped = linkedMapOf<String, MutableList<String>>()

    rows.drop(if (hasHeader) 1 else 0).forEach { row ->
        val name = row.getOrNull(actualNameIndex)?.trim().orEmpty()
        val phone = row.getOrNull(actualPhoneIndex)?.trim().orEmpty()
        if (name.isNotBlank() && phone.isNotBlank()) {
            grouped.getOrPut(name) { mutableListOf() }.add(phone)
        }
    }
    return grouped.map { (name, phones) ->
        Contact(
            name = name,
            phones = phones.distinctBy { normalizePhoneForTransfer(it) }
        )
    }
}

private fun resolveFirstSheet(entries: Map<String, ByteArray>): String? {
    val workbook = entries["xl/workbook.xml"] ?: return null
    val rels = entries["xl/_rels/workbook.xml.rels"] ?: return null
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(workbook), "UTF-8")
    var relationId: String? = null
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && parser.name == "sheet") {
            relationId = parser.getAttributeValue(
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                "id"
            ) ?: parser.getAttributeValue(null, "r:id")
            if (relationId != null) break
        }
        event = parser.next()
    }
    if (relationId == null) return null

    val relParser = Xml.newPullParser()
    relParser.setInput(ByteArrayInputStream(rels), "UTF-8")
    event = relParser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG && relParser.name == "Relationship" &&
            relParser.getAttributeValue(null, "Id") == relationId
        ) {
            val target = relParser.getAttributeValue(null, "Target") ?: return null
            return if (target.startsWith("/")) target.removePrefix("/")
            else "xl/" + target.removePrefix("./")
        }
        event = relParser.next()
    }
    return null
}

private fun readSharedStrings(bytes: ByteArray): List<String> {
    val values = mutableListOf<String>()
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
    var inItem = false
    val current = StringBuilder()
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "si") {
                    inItem = true
                    current.clear()
                } else if (inItem && parser.name == "t") {
                    current.append(parser.nextText())
                }
            }
            XmlPullParser.END_TAG -> if (parser.name == "si" && inItem) {
                values.add(current.toString())
                inItem = false
            }
        }
        event = parser.next()
    }
    return values
}

private fun readWorksheet(
    bytes: ByteArray,
    sharedStrings: List<String>
): List<List<String>> {
    val parser = Xml.newPullParser()
    parser.setInput(ByteArrayInputStream(bytes), "UTF-8")
    val rows = mutableListOf<List<String>>()
    var row: MutableMap<Int, String>? = null
    var currentColumn = -1
    var currentType = ""
    var inlineText = StringBuilder()
    var event = parser.eventType

    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> row = mutableMapOf()
                "c" -> {
                    currentColumn = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                    currentType = parser.getAttributeValue(null, "t").orEmpty()
                    inlineText = StringBuilder()
                }
                "v", "t" -> {
                    if (row != null && currentColumn >= 0) {
                        val value = parser.nextText()
                        if (parser.name == "v" || currentType == "inlineStr") {
                            inlineText.append(value)
                        }
                    }
                }
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "c" -> {
                    if (row != null && currentColumn >= 0) {
                        val raw = inlineText.toString()
                        val value = when (currentType) {
                            "s" -> sharedStrings.getOrNull(raw.toIntOrNull() ?: -1).orEmpty()
                            else -> raw
                        }
                        row[currentColumn] = value
                    }
                    currentColumn = -1
                    currentType = ""
                }
                "row" -> {
                    val current = row
                    if (current != null) {
                        val max = current.keys.maxOrNull() ?: -1
                        rows.add((0..max).map { current[it].orEmpty() })
                    }
                    row = null
                }
            }
        }
        event = parser.next()
    }
    return rows
}

private fun columnIndex(reference: String): Int {
    val letters = reference.takeWhile { it.isLetter() }
    if (letters.isEmpty()) return -1
    var result = 0
    letters.uppercase().forEach { char ->
        result = result * 26 + (char - 'A' + 1)
    }
    return result - 1
}

private fun normalizePhoneForTransfer(value: String): String {
    val digits = value
        .replace('۰', '0').replace('۱', '1').replace('۲', '2')
        .replace('۳', '3').replace('۴', '4').replace('۵', '5')
        .replace('۶', '6').replace('۷', '7').replace('۸', '8')
        .replace('۹', '9')
        .replace('٠', '0').replace('١', '1').replace('٢', '2')
        .replace('٣', '3').replace('٤', '4').replace('٥', '5')
        .replace('٦', '6').replace('٧', '7').replace('٨', '8')
        .replace('٩', '9')
    return digits.filter { it.isDigit() || it == '+' }
}
