package com.forja.app.feature.inventory

import android.net.Uri
import com.forja.app.core.inventory.BinTick
import com.forja.app.core.inventory.DeleteReason
import com.forja.app.core.inventory.InvDest
import com.forja.app.core.inventory.InvFolder
import com.forja.app.core.inventory.InvItem
import com.forja.app.core.inventory.InvKind
import com.forja.app.core.inventory.InvPlace
import com.forja.app.core.inventory.InvPlan
import com.forja.app.core.inventory.InvStage
import com.forja.app.core.inventory.Landing
import com.forja.app.core.inventory.MoveReason

/**
 * Date false pentru capturile de ecran (Roborazzi) și revizuire: exemplul din prototip —
 * 14 dosare (Dosare.dc.html), 3 214 poze, 212 în „De aruncat” (84 duplicate, 41 neclare, 57 capturi, 18 mici, 12 AI),
 * 1,2 GB la gunoi; documentele: 8 dosare, 486 de fișiere, 41 deoparte, 380 MB. Fără Android în afară de Uri.
 */
object InventorySamples {
    private const val GB = 1024L * 1024 * 1024
    private const val MB = 1024L * 1024
    private const val DAY = 86_400_000L
    /** 1 ian 2023, 10:00 UTC — datele sunt împrăștiate de aici încolo. */
    private const val T0 = 1_672_567_200_000L

    private fun photoUri(i: Int): Uri = Uri.parse("content://media/external/images/media/${200_000 + i}")
    private fun docUri(i: Int): Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocumente/document/primary%3ADocumente%2Fdoc$i")
    private val docTree: Uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocumente")

    /** Dosarele de poze din prototip: nume + câte poze. */
    val photoFolderNames = listOf(
        "Nuntă · aug 2023" to 512, "Munte · Bucegi" to 336, "Plajă · Vama Veche" to 304,
        "Mâncare" to 268, "Animale" to 227, "Brașov · iarnă" to 183,
        "Oraș noaptea" to 164, "Sală" to 152, "Concert · iulie" to 141,
        "Apusuri" to 118, "Capturi de ecran" to 96, "Acte foto" to 58,
        "Rețete salvate" to 47, "Diverse" to 396
    )

    /** Dosarele de documente din prototip: nume, câte fișiere, tipul dominant. */
    val docFolderNames = listOf(
        Triple("Facultate", 164, "pdf"), Triple("Facturi · 2024", 88, "pdf"), Triple("Manuale", 61, "pdf"),
        Triple("Bilete și rezervări", 44, "pdf"), Triple("Medical", 31, "jpg"), Triple("Contracte", 27, "docx"),
        Triple("CV și diplome", 18, "docx"), Triple("Acte identitate", 12, "jpg")
    )

    /**
     * Când s-au făcut pozele fiecărui dosar (în ordinea lui [photoFolderNames]): ziua de început, socotită de la 1 ian 2023,
     * și pasul dintre poze, în minute. Numele cu dată sau anotimp se potrivesc cu ele: nunta într-o zi din august,
     * concertul în iulie, Brașovul în decembrie; restul se întind pe lună sau pe ani.
     */
    private val photoFolderDates = listOf(
        230 to 1, 250 to 10, 205 to 20,          // Nuntă 19 aug 2023 · Bucegi sep · Vama Veche iul
        0 to 3 * 1440, 10 to 2160, 360 to 30,     // Mâncare 2023–2025 · Animale · Brașov 27 dec
        40 to 2880, 0 to 2880, 194 to 2,          // Oraș noaptea · Sală · Concert 14 iul
        100 to 2880, 0 to 4320, 20 to 7200,       // Apusuri · Capturi · Acte foto
        30 to 8640, 0 to 1296                      // Rețete · Diverse
    )

    /** Motivele din „De aruncat”, în proporțiile prototipului (84 / 41 / 57 / 18 / 12 = 212). */
    private val trashReasons: List<DeleteReason> = buildList {
        repeat(60) { add(DeleteReason.Duplicate) }
        repeat(24) { add(DeleteReason.Similar) }
        repeat(41) { add(DeleteReason.Blurry) }
        repeat(57) { add(DeleteReason.OldScreenshot) }
        repeat(18) { add(DeleteReason.Tiny) }
        repeat(9) { add(DeleteReason.AiSuggested) }
        repeat(3) { add(DeleteReason.Accidental) }
    }

    /** Planul de poze: 3 002 poze în 14 dosare + 212 la gunoi = 3 214; ~11,2 GB în dosare, 1,2 GB la gunoi. */
    val photoPlan: InvPlan by lazy {
        val items = LinkedHashMap<String, InvItem>()
        val folders = ArrayList<InvFolder>()
        var n = 0
        val folderBytes = (11.2 * GB).toLong() / 3002
        photoFolderNames.forEachIndexed { fi, (name, count) ->
            val ids = ArrayList<String>(count)
            val (day, stepMin) = photoFolderDates[fi]
            val start = T0 + day * DAY
            repeat(count) { k ->
                n++
                val id = "p$n"
                items[id] = InvItem(
                    id = id, uri = photoUri(n), kind = InvKind.Photos, takenAt = start + k * stepMin * 60_000L,
                    bytes = folderBytes, width = 4032, height = 3024, mime = "image/jpeg", name = "IMG_${20230000 + n}.jpg"
                )
                ids += id
            }
            folders += InvFolder("f${fi + 1}", name, name.substringBefore(" · "), ids, ids.take(4))
        }
        val trashIds = ArrayList<String>()
        val trashBytes = (1.2 * GB).toLong() / trashReasons.size
        trashReasons.forEachIndexed { k, r ->
            n++
            val id = "p$n"
            val screenshot = r == DeleteReason.OldScreenshot
            items[id] = InvItem(
                id = id, uri = photoUri(n), kind = InvKind.Photos, takenAt = T0 + k * DAY,
                bytes = trashBytes, width = if (r == DeleteReason.Tiny) 240 else 1080, height = if (r == DeleteReason.Tiny) 180 else 2340,
                mime = if (screenshot) "image/png" else "image/jpeg",
                name = if (screenshot) "Screenshot_$k.png" else "IMG_T$k.jpg", reason = r
            )
            trashIds += id
        }
        InvPlan(
            runId = "sample", kind = InvKind.Photos, createdAt = T0 + 700 * DAY, items = items, folders = folders,
            trash = InvFolder("trash", "De aruncat", "Gunoi", trashIds, trashIds.take(4), special = true), provider = "gemini"
        )
    }

    /** Planul de documente: 445 în 8 dosare + 41 deoparte = 486; 380 MB deoparte. */
    val docPlan: InvPlan by lazy {
        val items = LinkedHashMap<String, InvItem>()
        val folders = ArrayList<InvFolder>()
        var n = 0
        docFolderNames.forEachIndexed { fi, (name, count, ext) ->
            val ids = ArrayList<String>(count)
            repeat(count) { k ->
                n++
                val id = "d$n"
                val mime = when (ext) {
                    "pdf" -> "application/pdf"
                    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                    else -> "image/jpeg"
                }
                items[id] = InvItem(id, docUri(n), InvKind.Documents, T0 + (fi * 30L + k) * DAY, 900_000L, 0, 0, mime, "$name ${k + 1}.$ext")
                ids += id
            }
            folders += InvFolder("g${fi + 1}", name, name, ids, ids.take(4))
        }
        val trashIds = ArrayList<String>()
        repeat(41) { k ->
            n++
            val id = "d$n"
            val temp = k % 3 == 0
            items[id] = InvItem(
                id, docUri(n), InvKind.Documents, T0 + k * DAY, 380L * MB / 41, 0, 0,
                if (temp) "application/octet-stream" else "application/pdf",
                if (temp) "~\$raport$k.docx" else "copie $k.pdf",
                reason = if (temp) DeleteReason.TempFile else DeleteReason.Duplicate
            )
            trashIds += id
        }
        InvPlan(
            runId = "sample-docs", kind = InvKind.Documents, createdAt = T0 + 700 * DAY, items = items, folders = folders,
            trash = InvFolder("trash", "De aruncat", "Gunoi", trashIds, trashIds.take(4), special = true), provider = "gemini",
            source = docTree
        )
    }

    // ───────────────────────────── Stările ecranelor ─────────────────────────────

    /** S1 — nimic pornit: 3 214 poze · 12,4 GB, folderul de documente ales (486 · 2,1 GB), „~ 25 MIN”. */
    val start = StartUiState(
        kind = InvKind.Photos, photoAccess = true, photoCount = 3214, photoBytes = (12.4 * GB).toLong(),
        scope = ScopeChoice.All, docFolder = "Documente", docCount = 486, docBytes = (2.1 * GB).toLong(), estimateSec = 25 * 60
    )
    val startDocs = start.copy(kind = InvKind.Documents, estimateSec = 6 * 60)
    val startNoAccess = start.copy(photoAccess = false, photoCount = null, photoBytes = null, docFolder = null, docCount = null, docBytes = null, estimateSec = null)
    val startRunning = start.copy(run = RunSummary(InvKind.Photos, 34, ready = false, folders = 0, etaSec = 18 * 60))
    val startReady = start.copy(run = RunSummary(InvKind.Photos, 100, ready = true, folders = 14, etaSec = null))
    val startFailed = start.copy(error = "Nu a mers. Încearcă din nou.")
    /** S1 — „Ultimele 5 000 ▾” ales (chip-ul cel mai lat, încape și pe S23). */
    val startLastN = start.copy(scope = ScopeChoice.LastN, lastN = 5_000, estimateSec = 7 * 60)
    /** S1 — documente după o aplicare: nimic liber, 4 fișiere în dosare → „În ordine · 4 ÎN DOSARE”, „Alt folder”. */
    val startDocsOrdered = startDocs.copy(docCount = 0, docBytes = 0L, docOrganized = 4, estimateSec = null)

    val recent: List<Uri> = (1..8).map { photoUri(it * 37) }
    val bins = listOf(BinTick("Munte · Bucegi", 128), BinTick("Mâncare", 214), BinTick("Capturi", 96), BinTick(null, 37))

    /** S2 — analiza la 34 % (etapa AI), 4 cutii (una încă nebotezată), „~ 18 MIN”. */
    val run = RunUiState(
        kind = InvKind.Photos, stage = InvStage.Naming, done = 340, total = 1000, etaSec = 18 * 60,
        recent = recent, bins = bins
    )
    val runScanning = run.copy(stage = InvStage.Scanning, done = 60, bins = emptyList(), etaSec = 25 * 60)
    val runReady = run.copy(stage = InvStage.Ready, done = 1000, folders = 14, bins = photoFolderNames.take(4).map { BinTick(it.first, it.second) })

    /** S4 — 14 dosare, „De aruncat” 212 · 1,2 GB, „Aplică · ~ 2 MIN”, butonul „Pe site”. */
    val folders: FoldersUiState get() = photoPlan.foldersUi(showSite = true)
    val docFolders: FoldersUiState get() = docPlan.foldersUi(showSite = false)

    /** S5 — „De aruncat” cu chip-urile de motiv; varianta cu 3 selectate (bara „Păstrează [3]” / „Mută în…”). */
    val trash: FolderUiState get() = photoPlan.folderUi("trash", ReasonFilter.All, emptySet(), editing = false)!!
    val trashSelected: FolderUiState get() = trash.let { t -> t.copy(selected = t.cells.take(3).map { it.id }.toSet()) }
    val trashBlurry: FolderUiState get() = photoPlan.folderUi("trash", ReasonFilter.Blur, emptySet(), editing = false)!!
    /** S5 — redenumirea pe loc a unui dosar obișnuit („De aruncat” nu se redenumește). */
    val folderEditing: FolderUiState get() = photoPlan.folderUi("f1", ReasonFilter.All, emptySet(), editing = true)!!
    /** S5 — un dosar mare, întins pe ani (Mâncare, 268 de poze, câte una la 3 zile), cu antete de lună. */
    val folder: FolderUiState get() = photoPlan.folderUi("f4", ReasonFilter.All, emptySet(), editing = false)!!
    val moveTargets: List<MoveTarget> get() = photoPlan.moveTargets(exclude = "trash").take(5)

    /** S6 — confirmarea (cu rândul destinației), foaia „Locație”, aplicarea la 34 % și finalul „Gata”. */
    val confirm: ApplyConfirmUi get() = photoPlan.confirmUi()
    val confirmDocs: ApplyConfirmUi get() = docPlan.confirmUi()
    val location: LocationUi get() = photoPlan.locationUi()
    val locationDocs: LocationUi get() = docPlan.locationUi()
    /** Poze într-un dosar ales cu „Alt dosar…” (Pictures/Vacanțe): rândul lui apare ales, sub cele trei. */
    val locationCustom: LocationUi get() = photoPlan.copy(dest = InvDest.Media("Pictures/Vacanțe/")).locationUi()

    private fun doc(id: String): Uri = Uri.parse("content://com.android.externalstorage.documents/document/" + Uri.encode(id))
    /** Unde au ajuns pozele: 14 dosare în Pictures/FORJA; prima poză mutată. */
    val landing = Landing(
        kind = InvKind.Photos,
        root = InvPlace(doc("primary:Pictures/FORJA"), "Pictures/FORJA", "/storage/emulated/0/Pictures/FORJA"),
        segments = photoFolderNames.map { it.first }.toSet(),
        first = photoUri(1), firstMime = "image/jpeg"
    )
    /** Unde au ajuns documentele: 8 dosare în Documente/Organizate. */
    val landingDocs = Landing(
        kind = InvKind.Documents,
        root = InvPlace(doc("primary:Documente/Organizate"), "Documente/Organizate", "/storage/emulated/0/Documente/Organizate"),
        segments = docFolderNames.map { it.first }.toSet()
    )
    val apply = ApplyUiState(kind = InvKind.Photos, done = 1092, total = 3214, recent = recent, bins = photoFolderNames.take(4).map { BinTick(it.first, it.second) })
    val done = DoneUiState(
        kind = InvKind.Photos, folders = 14, items = 3214, freedBytes = (1.2 * GB).toLong(), failed = 0, musicStopped = true,
        place = landing, runId = "sample", showSite = true
    )
    val doneDocs = DoneUiState(
        kind = InvKind.Documents, folders = 8, items = 486, freedBytes = 0L, failed = 0, musicStopped = false,
        place = landingDocs, runId = "sample-docs", showSite = true
    )
    /** Finalul cu elemente nemutate (șterse între timp) și fără contract (fără „Pe site”); mutate + nemutate = total. */
    val doneFailed = done.copy(items = 3211, failed = 3, showSite = false)
    val doneDocsFailed = doneDocs.copy(items = 484, failed = 2)

    // ───────────── 4.4.2: „Acces complet” și pagina de rezultat ─────────────

    /** Rândul „Acces complet”, varianta simplă (nicio poză a altei aplicații). */
    val access = AccessUi()
    /** Planul Lanei de pe S23: 6 din 9 poze sunt din WhatsApp (Android/media/com.whatsapp/…). */
    val accessWhatsApp = AccessUi(owned = 6, app = "WhatsApp", apps = 1)
    /** Dosarul ales în Documents: fără acces complet nu se poate, „Aplică” îl cere (și rândul n-are „Fără”). */
    val accessDest = AccessUi(forDest = true)
    /** Confirmarea Lanei: 9 mutări într-un dosar („Capturi de ecran”) și o poză la gunoi. */
    val confirmLana = ApplyConfirmUi(
        kind = InvKind.Photos, folders = 1, moves = 9, trashCount = 1, trashBytes = 3 * MB, destLabel = "FORJA", destPath = "PICTURES/FORJA"
    )
    /** Aceeași confirmare, cu destinația aleasă în Documents/Poze. */
    val confirmLanaDocuments = confirmLana.copy(destLabel = "Poze", destPath = "DOCUMENTS/POZE")
    /** „Locație” cu un dosar din Documents ales (acum se poate, cu acces complet). */
    val locationDocuments: LocationUi get() = photoPlan.copy(dest = InvDest.Media("Documents/Poze/")).locationUi()

    private val landingCapturi = Landing(
        kind = InvKind.Photos,
        root = InvPlace(doc("primary:Pictures/FORJA"), "Pictures/FORJA", "/storage/emulated/0/Pictures/FORJA"),
        single = InvPlace(doc("primary:Pictures/FORJA/Capturi de ecran"), "Pictures/FORJA/Capturi de ecran", "/storage/emulated/0/Pictures/FORJA/Capturi de ecran"),
        segments = setOf("Capturi de ecran"), first = photoUri(1), firstMime = "image/jpeg", bucketId = 7L
    )
    /** Rezultatul Lanei: 3 mutate + 1 la gunoi, cele 6 din WhatsApp au rămas → „Permite accesul”. */
    val doneAccess = DoneUiState(
        kind = InvKind.Photos, folders = 1, items = 4, freedBytes = 3 * MB, failed = 6, musicStopped = true, place = landingCapturi,
        runId = "sample", showSite = true, trashed = 1, reason = MoveReason.Owned, ownerApp = "WhatsApp", ownerApps = 1, fix = DoneFix.Access
    )
    /** Un motiv pe care accesul nu-l schimbă (Android n-a mutat 234 din 3 214): „Încearcă din nou” aplică restul. */
    val doneRetry = DoneUiState(
        kind = InvKind.Photos, folders = 12, items = 2980, freedBytes = (1.1 * GB).toLong(), failed = 234, musicStopped = false,
        place = landing, runId = "sample", showSite = false, trashed = 200, reason = MoveReason.Error, fix = DoneFix.Retry
    )
    /** Nimic aplicat: dosarul ales (Documents) cere acces complet. */
    val doneNothing = DoneUiState(
        kind = InvKind.Photos, folders = 0, items = 0, freedBytes = 0L, failed = 9, musicStopped = true, place = null,
        runId = "sample", showSite = true, trashed = 0, reason = MoveReason.Dir, fix = DoneFix.Access
    )

    /** Pastila: la 34 % și „Gata”. */
    val pill = PillState(34, ready = false)
    val pillReady = PillState(100, ready = true)

    /** S3c — „Marș de dimineață”, top 1 în 7 zile, 1:12 din 3:40, ambele comutatoare pornite. */
    val music = MusicUiState(
        pill = pill, ring = 0.34f, access = true, title = "Marș de dimineață", artist = "Fanfara FORJA", app = "Spotify",
        playing = true, positionMs = 72_000, durationMs = 220_000, top = true, stopAtEnd = true, onMap = true
    )
    val musicNoAccess = MusicUiState(pill = pill, ring = 0.34f, access = false)
}
