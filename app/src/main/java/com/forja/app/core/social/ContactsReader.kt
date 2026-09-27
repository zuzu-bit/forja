package com.forja.app.core.social

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** O intrare din agendă: numele așa cum îl are utilizatorul și numărul normalizat E.164. Rămâne pe telefon. */
data class ContactEntry(val name: String, val number: String)

/**
 * Citește agenda (ContactsContract, tabela Phone): nume + numere, deduplicate după numărul normalizat.
 * Fără READ_CONTACTS acordat nu citește nimic și întoarce lista goală. Numele nu pleacă niciodată de pe telefon.
 */
object ContactsReader {
    /** Cel mult atâtea numere într-o sincronizare (serverul păstrează amprentele a maximum 5000 din agenda mea). */
    const val MAX_NUMBERS = 5000

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun read(context: Context, cc: String = PhoneNumbers.defaultCountryCode(context)): List<ContactEntry> {
        if (!granted(context)) return emptyList()
        val out = LinkedHashMap<String, ContactEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER
        )
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
            )?.use { c ->
                val iName = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val iNumber = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val iNorm = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER)
                while (c.moveToNext() && out.size < MAX_NUMBERS) {
                    val name = (if (iName >= 0) c.getString(iName) else null)?.trim().orEmpty()
                    val normalizedBySystem = if (iNorm >= 0) c.getString(iNorm) else null
                    val number = PhoneNumbers.normalize(normalizedBySystem, cc)
                        ?: PhoneNumbers.normalize(if (iNumber >= 0) c.getString(iNumber) else null, cc)
                        ?: continue
                    if (!out.containsKey(number)) out[number] = ContactEntry(name.ifBlank { number }, number)
                }
            }
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: Exception) {
        }
        return out.values.toList()
    }
}
