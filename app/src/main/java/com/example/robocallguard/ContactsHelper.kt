package com.example.robocallguard

import android.content.Context
import android.provider.ContactsContract

/** Best-effort check whether a number belongs to any stored contact. */
class ContactsHelper(private val context: Context) {

    private var contactSet: Set<String>? = null

    fun refresh() {
        val set = mutableSetOf<String>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                val idx = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val digits = c.getString(idx)?.filter { it.isDigit() } ?: continue
                    if (digits.length >= 10) set.add(digits.takeLast(10))
                }
            }
        } catch (_: Exception) {
            // No permission or provider issue — treat as having no contacts.
        }
        contactSet = set
    }

    fun isInContacts(number: String): Boolean {
        val digits = number.filter { it.isDigit() }.takeLast(10)
        if (digits.length < 10) return false
        val set = contactSet ?: run {
            refresh()
            contactSet ?: emptySet()
        }
        return digits in set
    }
}
