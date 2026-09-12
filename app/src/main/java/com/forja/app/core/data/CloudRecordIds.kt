package com.forja.app.core.data

/** Keep the copy's Room row IDs from overwriting the original app's cloud rows. */
internal class CloudRecordIds(private val installationId: String?) {
    init {
        require(installationId == null || installationId.matches(Regex("[a-f0-9]{16}")))
    }

    fun record(kind: String, localId: Long): String {
        require(kind in setOf("m", "s", "a") && localId >= 0)
        val prefix = installationId?.let { "r_${it}_" } ?: ""
        return "$prefix$kind$localId"
    }
}
