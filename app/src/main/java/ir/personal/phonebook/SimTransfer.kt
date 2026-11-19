package ir.personal.phonebook

import android.content.ContentValues
import android.content.Context
import android.provider.SimPhonebookContract
import android.database.Cursor

data class SimTarget(
    val subscriptionId: Int,
    val slotIndex: Int,
    val maxRecords: Int,
    val recordCount: Int
)

data class SimTransferResult(
    val successCount: Int,
    val failedCount: Int,
    val skippedDuplicateCount: Int = 0
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
        null, null, null
    ) ?: return emptyList()

    cursor.use {
        while (it.moveToNext()) {
            if (it.intValue(SimPhonebookContract.ElementaryFiles.EF_TYPE, -1) !=
                SimPhonebookContract.ElementaryFiles.EF_ADN
            ) continue
            val subId = it.intValue(SimPhonebookContract.ElementaryFiles.SUBSCRIPTION_ID, -1)
            if (subId < 0) continue
            result.add(
                SimTarget(
                    subscriptionId = subId,
                    slotIndex = it.intValue(SimPhonebookContract.ElementaryFiles.SLOT_INDEX, result.size),
                    maxRecords = it.intValue(SimPhonebookContract.ElementaryFiles.MAX_RECORDS),
                    recordCount = it.intValue(SimPhonebookContract.ElementaryFiles.RECORD_COUNT)
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
    var skippedDuplicates = 0
    val free = (target.maxRecords - target.recordCount).coerceAtLeast(0)
    val uri = SimPhonebookContract.SimRecords.getContentUri(
        target.subscriptionId,
        SimPhonebookContract.ElementaryFiles.EF_ADN
    )

    // Read-only duplicate check: this function never deletes SIM records.
    val existingKeys = runCatching {
        buildSet {
            context.contentResolver.query(
                uri,
                arrayOf(
                    SimPhonebookContract.SimRecords.NAME,
                    SimPhonebookContract.SimRecords.PHONE_NUMBER
                ),
                null, null, null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(SimPhonebookContract.SimRecords.NAME)
                val phoneIndex = cursor.getColumnIndex(SimPhonebookContract.SimRecords.PHONE_NUMBER)
                if (nameIndex >= 0 && phoneIndex >= 0) {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex).orEmpty()
                        val phone = cursor.getString(phoneIndex).orEmpty()
                        add(normalizeContactName(name) + "\u0000" + normalizePhoneForTransfer(phone))
                    }
                }
            }
        }.toMutableSet()
    }.getOrDefault(mutableSetOf())

    records.forEachIndexed { index, (name, rawPhone) ->
        if (success >= free) {
            failed++
        } else {
            try {
                val phone = normalizePhoneForTransfer(rawPhone)
                if (phone.isBlank()) throw IllegalArgumentException("شماره نامعتبر")
                val key = normalizeContactName(name) + "\u0000" + phone
                if (key in existingKeys) {
                    skippedDuplicates++
                } else {
                    existingKeys.add(key)
                    val values = ContentValues().apply {
                        put(SimPhonebookContract.SimRecords.PHONE_NUMBER, phone)
                        put(SimPhonebookContract.SimRecords.NAME, name)
                    }
                    val inserted = context.contentResolver.insert(uri, values)
                    if (inserted != null) success++ else {
                        existingKeys.remove(key)
                        failed++
                    }
                }
            } catch (_: Exception) {
                failed++
            }
        }
    }
    return SimTransferResult(success, failed, skippedDuplicates)
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
