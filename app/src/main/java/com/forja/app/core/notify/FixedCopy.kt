package com.forja.app.core.notify

/*
 * Textele fixe ale notificărilor de serviciu (notifications-design.md §D.1, §D.10, §D.17–19, §F) — fără Android, testate.
 * Siguranța și onestitatea sunt mai importante decât umorul: ce urcă, ce ascultă și cum oprești rămân mereu la vedere.
 */

/** Id-urile notificărilor: unul singur per fel, fără coliziuni (4.3 avea somnul și mesele pe 35). */
object NotifIds {
    const val SLEEP_FGS = 31          // SleepTrackService (Stingerea)
    const val GO_FGS = 32             // GoTrackService
    const val FOCUS_FGS = 33          // FocusMonitorService
    const val ALARM = 34              // SleepTrackService.fireAlarm
    const val SLEEP_REPORT = 35       // SleepUpload
    const val SYNC_FGS = 36           // AutomaticCollectionService
    const val SYNC_PAUSED = 37        // bugetul Android de 6 h
    const val MEALS_FOUND = 38        // GalleryScan (era 35, peste raportul nopții)
    const val BEDTIME = 39
    const val COACH = 40              // un singur mesaj „coach” vizibil odată: cel nou îl înlocuiește pe cel vechi
    const val FOCUS_DONE = 41
    const val MILESTONE = 42
    const val LAPTOP = 71             // OrganizerJobs
    const val LOST_PHONE = 627        // LostPhoneService (P6)
    const val INVENTORY_PROGRESS = 4301
    const val INVENTORY_RESULT = 4302
    /** Locuri noi: 5000 + id % 1000 (4.3: 4100 + id % 1000 se suprapunea cu agenda și cu Inventarul). */
    const val PLACE_BASE = 5000
    /** Camarazi noi din agendă: 6000 + hash % 1000. */
    const val NEW_FRIEND_BASE = 6000
    /** Prieten aproape: 7000 + hash % 1000. */
    const val FRIEND_NEAR_BASE = 7000

    fun place(id: Long): Int = PLACE_BASE + Math.floorMod(id, 1000L).toInt()
    fun newFriend(uid: String): Int = NEW_FRIEND_BASE + Math.floorMod(uid.hashCode(), 1000)
    fun friendNear(uid: String): Int = FRIEND_NEAR_BASE + Math.floorMod(uid.hashCode(), 1000)
}

/** Canalele (id-urile nu se schimbă niciodată: importanța unui canal nu mai poate crește după creare). */
object Channels {
    const val SYNC = "sync"
    const val COACH = "coach"
    const val SOCIAL = "social"
    const val EXPLORE = "explore"
    const val SLEEP = "sleep"
    const val INVENTORY = "inventory"
    const val CLEANUP = "cleanup"
    const val GO = "go"
    const val FOCUS = "focus"
    const val ALARM = "alarm"
}

/**
 * Grupuri separate: pe Samsung, fără grup, autogruparea pune notificarea permanentă în același teanc cu mesajele,
 * iar „Șterge tot” nu mai curăță teancul (corectura 8).
 */
object Groups {
    const val SYNC = "forja_sync"
    const val COACH = "forja_coach"
    const val SOCIAL = "forja_social"
    const val PLACES = "forja_places"
    const val SLEEP = "forja_sleep"
    const val INVENTORY = "forja_inventory"
    const val FOCUS = "forja_focus"
    const val GO = "forja_go"
    const val NIGHT = "forja_night"
    const val CLEANUP = "forja_cleanup"
}

/** Textul notificării permanente de sincronizare, cu invariantele de onestitate (blocantele B1, B5, corectura 9). */
object SyncCopy {
    /** Ordinea categoriilor în text (aceeași ca în contract). */
    val ORDER = listOf("location", "app_usage", "photos", "files", "audio")
    const val STOP = "Oprește"
    const val SUB_ACTIVE = "Sincronizare activă"
    const val SUB_MIC = "Microfon + sincronizare"
    const val MIC_TITLE = "Microfonul e pornit."
    const val PUBLIC_TITLE = "FORJA e în post."

    /** „locație, aplicații, fotografii” — NUMAI din ce rulează acum (fără listă implicită care ar ascunde microfonul). */
    fun categories(configured: Set<String>, label: (String) -> String): String =
        (ORDER.filter { it in configured } + configured.filter { it !in ORDER }.sorted()).joinToString(", ") { label(it) }

    /** Forma restrânsă: ce urcă, mereu la vedere. */
    fun collapsed(categories: String): String = "Urcă în cont: $categories"

    /** Al doilea paragraf din forma extinsă: ce urcă, cum oprești, unde revoci (oprirea nu e revocarea). */
    fun honest(categories: String): String =
        "Urcă în contul tău FORJA: $categories. Oprești de aici. Revoci din Profil → Contract."

    fun subText(configured: Set<String>): String = if ("audio" in configured) SUB_MIC else SUB_ACTIVE

    /** Textul complet (fără Android): titlu, rândul restrâns, antet, forma extinsă, versiunea publică. */
    data class Text(
        val title: String,
        val collapsed: String,
        val subText: String,
        val big: String,
        val publicTitle: String,
        val publicText: String,
        val action: String
    )

    fun compose(configured: Set<String>, line: Rendered?, label: (String) -> String): Text {
        val cats = categories(configured, label)
        val mic = "audio" in configured
        val warmTitle = line?.title ?: PUBLIC_TITLE
        val warmBody = line?.body
        return Text(
            // Microfonul nu se ascunde niciodată în spatele unei glume: titlul rămâne fix, rotația doar în text.
            title = if (mic) MIC_TITLE else warmTitle,
            collapsed = collapsed(cats),
            subText = subText(configured),
            big = listOfNotNull(warmBody, honest(cats)).joinToString("\n\n"),
            publicTitle = if (mic) MIC_TITLE else PUBLIC_TITLE,
            publicText = collapsed(cats),
            action = STOP
        )
    }

    /** Pauza impusă de Android (bugetul dataSync) — mesaj unic, nu după un „Oprește” dat de tine. */
    val paused: Template get() = NudgeBank.syncStopped.first()
}

/** Notificările de serviciu pornite de tine: ajustare minimă a textului, informația rămâne. */
object ServiceCopy {
    fun goTitle(sport: String): String = when (sport) {
        "walk" -> "Tură în curs · Mers"
        "ride" -> "Tură în curs · Ciclism"
        else -> "Tură în curs · Alergare"
    }
    const val GO_TEXT = "Traseul se înregistrează. Eu număr, tu respiră. Oprești din hartă."

    const val SLEEP_TITLE = "Stingerea · FORJA veghează"
    /** Rândul despre urcare rămâne în fiecare variantă (contractul: înregistrările nopții). */
    const val SLEEP_TEXT = "Sunet și mișcare. Înregistrarea urcă dimineața pe server, pe Wi-Fi. Somn ușor."

    const val FOCUS_TITLE = "Focus · paznicul e în post"
    const val FOCUS_TEXT = "Tu faci treaba, eu țin ușa închisă."
    /** Forma extinsă păstrează linia onestă: ce vede FORJA. */
    const val FOCUS_BIG = "Tu faci treaba, eu țin ușa închisă. Aplicațiile care te distrag stau în pauză.\n\n" +
        "FORJA vede doar ce aplicație e deschisă."

    fun laptopTitle(n: Int): String = "Laptopul a aprobat ${Ro.count(n, "poză", "poze")}."
    const val LAPTOP_TEXT = "Mutarea cere o atingere aici. Android vrea acordul tău, nu al nostru."
}

/** Inventarul: titlul cu procent e informație; se rotește doar rândul de sub el, după etapă. */
object InventoryCopy {
    const val TITLE = "Inventar"
    fun progressTitle(percent: Int): String = "Inventar · $percent %"

    /** Etapa: scanning · grouping · naming · applying (numele din InvStage, cu litere mici). */
    fun stageLine(stage: String, photos: Boolean): String = when (stage) {
        "grouping" -> if (photos) "Grupez pe evenimente. Capturile de ecran stau separat." else "Grupez fișierele după ce conțin."
        "naming" -> "Dau nume dosarelor. Mai bune decât „IMG_4032”, promit."
        "applying" -> if (photos) "Mut pozele în dosare. Originalele rămân pe telefon." else "Mut fișierele în dosarele alese."
        else -> if (photos) "Răsfoiesc pozele, una câte una. Tu faci altceva, eu țin socoteala." else "Citesc folderul. Nimic nu se mută încă."
    }

    fun readyTitle(folders: Int): String = "Dosarele sunt gata · ${Ro.count(folders, "dosar", "dosare")}"

    fun readyText(photos: Boolean, items: Int, trash: Int): String {
        val what = if (photos) Ro.count(items, "poză", "poze") else Ro.count(items, "fișier", "fișiere")
        return "$what · ${Ro.thousands(trash)} la gunoi. Tu decizi ce pleacă."
    }

    const val FAILED_TITLE = "Inventarul s-a oprit."
    const val OPEN = "Deschide"
}
