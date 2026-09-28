// TEMP STUB (package F) — deleted at merge; real API = package C
package com.forja.app.core.media

import android.content.Context

enum class ShortKind { Front, Recruti }

data class ShortCredit(val name: String, val url: String? = null)

data class Short(
    val id: String,
    val kind: ShortKind,
    val url: String,
    val poster: String?,
    val caption: String,
    val credit: ShortCredit?,
    val dur: Float,
    val burned: Boolean = false,
    val capTop: Int? = null,
    val capLeft: Int? = null,
    val w: Int = 720,
    val h: Int = 1280
)

object Shorts {
    fun peek(context: Context): List<Short> = emptyList()
}
