package ir.personal.phonebook

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import jxl.Workbook

/**
 * Reads legacy BIFF .xls with JExcelApi and modern OOXML .xlsx with Android's
 * XML pull parser. The caller must run this function on Dispatchers.IO.
 */
internal fun parseExcelContacts(bytes: ByteArray): List<Contact>? {
    if (bytes.size >= 4 &&
        bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
        bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
    ) return parseXlsxContacts(bytes)

    val oleSignature = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11.toByte(), 0xE0.toByte(),
        0xA1.toByte(), 0xB1.toByte(), 0x1A.toByte(), 0xE1.toByte()
    )
    if (bytes.size >= oleSignature.size &&
        oleSignature.indices.all { bytes[it] == oleSignature[it] }
    ) return parseXlsContacts(bytes)

    return null
}

private fun parseXlsContacts(bytes: ByteArray): List<Contact> {
    val workbook = Workbook.getWorkbook(ByteArrayInputStream(bytes))
    try {
        if (workbook.numberOfSheets == 0) return emptyList()
        val sheet = workbook.getSheet(0)
        val rows = ArrayList<List<String>>(sheet.rows)
        val maxColumns = minOf(sheet.columns, 128)
        val maxRows = minOf(sheet.rows, 50001)
        for (rowIndex in 0 until maxRows) {
            val row = ArrayList<String>(maxColumns)
            for (columnIndex in 0 until maxColumns) {
                row.add(sheet.getCell(columnIndex, rowIndex).contents.orEmpty())
            }
            rows.add(row)
        }
        return contactsFromSpreadsheetRows(rows)
    } finally {
        workbook.close()
    }
}

private fun parseXlsxContacts(bytes: ByteArray): List<Contact> {
    val entries = HashMap<String, ByteArray>()
    var totalUncompressed = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        var count = 0
        while (entry != null) {
            count++
            require(count <= 256) { "تعداد اجزای فایل Excel غیرعادی است." }
            if (!entry.isDirectory) {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    totalUncompressed += read
                    require(totalUncompressed <= 32L * 1024L * 1024L) {
                        "حجم بازشده فایل Excel بیش از حد مجاز است."
                    }
                    output.write(buffer, 0, read)
                }
                entries[entry.name] = output.toByteArray()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    val workbookXml = entries["xl/workbook.xml"]
        ?: throw IllegalArgumentException("ساختار فایل XLSX معتبر نیست.")
    val relationshipId = firstSheetRelationshipId(workbookXml)
        ?: throw IllegalArgumentException("هیچ برگه‌ای در فایل Excel پیدا نشد.")
    val relationships = entries["xl/_rels/workbook.xml.rels"]
        ?: throw IllegalArgumentException("پیوند برگه‌های فایل XLSX پیدا نشد.")
    val target = relationshipTarget(relationships, relationshipId)
        ?: throw IllegalArgumentException("مسیر برگه Excel قابل تشخیص نیست.")
    val sheetPath = normalizeZipPath(target)
    val sheetXml = entries[sheetPath]
        ?: throw IllegalArgumentException("محتوای برگه Excel پیدا نشد.")

    val sharedStrings = entries["xl/sharedStrings.xml"]
        ?.let(::readSharedStrings)
        .orEmpty()
    val rows = readWorksheetRows(sheetXml, sharedStrings)
    return contactsFromSpreadsheetRows(rows)
}

private fun newParser(bytes: ByteArray): XmlPullParser {
    return Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        setInput(ByteArrayInputStream(bytes), "UTF-8")
    }
}

private fun firstSheetRelationshipId(bytes: ByteArray): String? {
    val parser = newParser(bytes)
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "sheet") {
            for (index in 0 until parser.attributeCount) {
                val name = parser.getAttributeName(index)
                if (name == "id" || name.endsWith(":id")) {
                    return parser.getAttributeValue(index)
                }
            }
            return parser.getAttributeValue(
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
                "id"
            )
        }
        parser.next()
    }
    return null
}

private fun relationshipTarget(bytes: ByteArray, relationshipId: String): String? {
    val parser = newParser(bytes)
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Relationship") {
            val id = parser.getAttributeValue(null, "Id")
            if (id == relationshipId) return parser.getAttributeValue(null, "Target")
        }
        parser.next()
    }
    return null
}

private fun normalizeZipPath(target: String): String {
    val raw = if (target.startsWith("/")) target.drop(1)
    else if (target.startsWith("xl/")) target
    else "xl/$target"
    val parts = ArrayDeque<String>()
    raw.split('/').forEach { part ->
        when (part) {
            "", "." -> Unit
            ".." -> if (parts.isNotEmpty()) parts.removeLast()
            else -> parts.addLast(part)
        }
    }
    return parts.joinToString("/")
}

private fun readSharedStrings(bytes: ByteArray): List<String> {
    val result = ArrayList<String>()
    val parser = newParser(bytes)
    var insideItem = false
    val current = StringBuilder()
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                if (parser.name == "si") {
                    insideItem = true
                    current.setLength(0)
                } else if (insideItem && parser.name == "t") {
                    current.append(parser.nextText())
                }
            }
            XmlPullParser.END_TAG -> if (parser.name == "si" && insideItem) {
                result.add(current.toString())
                insideItem = false
            }
        }
        parser.next()
    }
    return result
}

private fun readWorksheetRows(
    bytes: ByteArray,
    sharedStrings: List<String>
): List<List<String>> {
    val rows = ArrayList<List<String>>()
    val parser = newParser(bytes)
    var currentRow: MutableMap<Int, String>? = null
    var currentColumn = -1
    var cellType = ""
    val cellText = StringBuilder()

    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "row" -> currentRow = sortedMapOf()
                "c" -> {
                    currentColumn = columnIndex(parser.getAttributeValue(null, "r").orEmpty())
                    cellType = parser.getAttributeValue(null, "t").orEmpty()
                    cellText.setLength(0)
                }
                "v", "t" -> if (currentColumn >= 0) {
                    cellText.append(parser.nextText())
                }
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "c" -> {
                    val raw = cellText.toString()
                    val value = when (cellType) {
                        "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }.orEmpty()
                        "inlineStr", "str", "b", "e" -> raw
                        else -> normalizeNumericCell(raw)
                    }
                    currentRow?.set(currentColumn, value)
                    currentColumn = -1
                    cellType = ""
                    cellText.setLength(0)
                }
                "row" -> {
                    val row = currentRow
                    if (row != null) {
                        val maxColumn = row.keys.maxOrNull() ?: -1
                        val values = MutableList(maxColumn + 1) { "" }
                        row.forEach { (column, value) ->
                            if (column in values.indices) values[column] = value
                        }
                        rows.add(values)
                    }
                    currentRow = null
                }
            }
        }
        parser.next()
        if (rows.size > 50000) {
            throw IllegalArgumentException("تعداد ردیف‌های فایل Excel بیش از حد مجاز است.")
        }
    }
    return rows
}

private fun columnIndex(reference: String): Int {
    var result = 0
    var found = false
    for (char in reference) {
        if (char !in 'A'..'Z' && char !in 'a'..'z') break
        found = true
        result = result * 26 + (char.uppercaseChar() - 'A' + 1)
    }
    return if (found) result - 1 else -1
}

private fun normalizeNumericCell(value: String): String {
    val number = value.toBigDecimalOrNull() ?: return value
    return number.stripTrailingZeros().toPlainString()
}

private fun contactsFromSpreadsheetRows(rows: List<List<String>>): List<Contact> {
    val nonEmptyRows = rows.filter { row -> row.any { it.isNotBlank() } }
    if (nonEmptyRows.isEmpty()) return emptyList()

    val header = nonEmptyRows.first().map(::normalizeHeader)
    val nameColumn = header.indexOfFirst {
        it == "نام" || it.contains("نام مخاطب") || it == "name" ||
            it == "contact name" || it == "fullname"
    }.takeIf { it >= 0 } ?: 0

    val phoneColumns = header.indices.filter { index ->
        val value = header[index]
        value.contains("شماره") || value.contains("تلفن") ||
            value.contains("موبایل") || value.contains("تماس") ||
            value.contains("phone") || value.contains("mobile") ||
            value == "tel" || value.contains("telephone")
    }
    val hasHeader = phoneColumns.isNotEmpty() ||
        header.any { it == "نام" || it == "name" || it == "contact name" || it == "fullname" }
    val selectedPhoneColumns = if (phoneColumns.isNotEmpty()) phoneColumns else listOf(1)

    val dataRows = if (hasHeader) nonEmptyRows.drop(1) else nonEmptyRows
    return dataRows.mapNotNull { row ->
        val name = row.getOrNull(nameColumn).orEmpty().trim()
        if (name.isBlank()) return@mapNotNull null
        val phones = selectedPhoneColumns
            .mapNotNull { row.getOrNull(it) }
            .flatMap { it.split(Regex("[\\r\\n,;|]+")) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizePhone(it) }
        if (phones.isEmpty()) null else Contact(name = name, phones = phones)
    }
}

private fun normalizeHeader(value: String): String =
    value.trim().lowercase()
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace(Regex("\\s+"), " ")

internal fun readBytesLimited(
    input: java.io.InputStream,
    maxBytes: Int = 20 * 1024 * 1024
): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "حجم فایل بیشتر از ۲۰ مگابایت است." }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
