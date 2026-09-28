package com.forja.app.core.inventory

import android.net.Uri
import android.os.SystemClock
import com.forja.app.core.cleanup.DocItem
import com.forja.app.core.cleanup.DocumentOrganizer
import com.forja.app.core.cleanup.sha256Hex
import com.forja.app.core.network.OrganizeApi
import com.forja.app.core.network.OrganizeItemV2
import com.forja.app.core.network.PdfSource
import com.forja.app.core.network.providerLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ═══════════════ Inventar 4.3 — documentele dintr-un folder (SAF) ═══════════════
// Scanare: inventarul SAF existent (DocumentOrganizer: BFS, fără „Organizate”) + detecția de duplicate (SHA-256) și
// de fișiere temporare. AI: /v1/organize v2 în loturi (PDF ≤ 4 MB întreg, fragmente de text), 2 în paralel, o
// reîncercare. Plan: dosare după `dosar` normalizat (cele mici unite în `categorie`), gunoi = duplicate + temporare +
// `sterge.recomandat`. Aplicarea mută în „<folder>/Organizate/<dosar>” și „<folder>/De aruncat (FORJA)” — nimic nu se
// șterge definitiv.

/** Dosarul-gunoi al documentelor, direct sub folderul ales. */
internal const val DOC_TRASH_DIR = "De aruncat (FORJA)"

/**
 * Un fișier „liber” = unul pe care Inventarul l-ar pune în ordine acum: nu stă în „De aruncat (FORJA)” și nu e un
 * fișier de lucru al mutărilor („.forja-….part”). Același filtru în scanare, în estimare și pe placa DOCUMENTE.
 */
internal fun DocItem.isLoose(): Boolean =
    path != DOC_TRASH_DIR && !path.startsWith("$DOC_TRASH_DIR/") && !name.startsWith(".forja-")

internal fun ItemRec.toDocItem(): DocItem = DocItem(
    uri = Uri.parse(uri), documentId = docId, parentUri = Uri.parse(parent), path = path,
    name = name, mime = mime, sizeBytes = bytes, lastModified = takenAt, flags = flags
)

internal object DocPipeline {

    suspend fun run(ctl: RunCtl) {
        val ctx = ctl.ctx
        val zone = ZoneId.systemDefault()
        val organizer = DocumentOrganizer(ctx, ctl.app.prefs)
        val tree = ctl.meta.tree?.let { Uri.parse(it) } ?: organizer.persistedTree()
            ?: throw InvFailure("Alege folderul cu documente, apoi pornește din nou.")
        if (ctl.meta.tree == null) {
            ctl.meta = ctl.meta.copy(tree = tree.toString())
            ctl.saveMeta()
        }

        val items: List<ItemRec> = withContext(Dispatchers.IO) { InventoryStore.readItems(ctx, ctl.runId) } ?: run {
            ctl.enter(InvStage.Scanning, "Citesc folderul")
            val scanned = scan(ctl, organizer, tree)
            ctl.checkAlive()
            if (scanned.isNotEmpty()) withContext(Dispatchers.IO) { InventoryStore.writeItems(ctx, ctl.runId, scanned) }
            scanned
        }
        if (items.isEmpty()) throw InvFailure("Nimic nou în folder.")

        // Gruparea provizorie (după familie: PDF-uri, Tabele…) — doar „cutiile” de pe ecran; dosarele finale vin după AI.
        ctl.enter(InvStage.Grouping, "Grupez fișierele")
        val live = items.filter { it.localReason == null }
        ctl.bins = live.groupBy { InvAssemble.docType(it) }.entries
            .sortedWith(compareByDescending<Map.Entry<String, List<ItemRec>>> { it.value.size }.thenBy { it.key })
            .take(5).map { BinTick(null, it.value.size) }
        ctl.report(InvStage.Grouping, 1.0, ctl.meta.estAiSec, null, force = true)

        ctl.enter(InvStage.Naming, "Dau nume dosarelor")
        val names = analyze(ctl, organizer, items)
        ctl.checkAlive()

        val doc = InvAssemble.documents(items, names.docs, zone).copy(provider = names.provider)
        Inventory.ready(ctl, doc, items.associateBy { it.id })
    }

    private suspend fun scan(ctl: RunCtl, organizer: DocumentOrganizer, tree: Uri): List<ItemRec> {
        ctl.report(InvStage.Scanning, 0.05, null, "Citesc folderul", force = true)
        // Destinația aleasă în Inventar, când e un dosar din folderul analizat, nu se re-scanează (e deja în ordine).
        val skips = DocDest.skips(tree, DocDest.savedTree(ctl.ctx, ctl.app.prefs))
        val (docs, warnings) = organizer.inventory(tree, skipDirIds = skips)
        ctl.checkAlive()
        if (docs.isEmpty() && warnings.isNotEmpty()) throw InvFailure("Nu mai am acces la folder. Alege-l din nou.")
        // Gunoiul propriu și fișierele de lucru ale mutărilor („.forja-….part”) nu intră în inventar.
        val kept = docs.filter { it.isLoose() }
        val ai = AiGate.likely(ctl.app)
        ctl.setEstimates(
            InvRules.scanSec(kept.size), 2,
            if (ai) InvRules.aiSec((kept.size + InvRules.DOC_BATCH - 1) / InvRules.DOC_BATCH) else 1
        )
        kept.takeLast(8).forEach { ctl.addRecent(it.uri) }
        ctl.report(InvStage.Scanning, 0.4, null, "Caut dublurile · ${InvText.count(kept.size, "fișier", "fișiere")}", force = true)
        val report = organizer.detect(kept)
        ctl.checkAlive()
        val copies = report.duplicates.flatMap { g -> g.copies.map { it.key } }.toHashSet()
        val recs = kept.map { d ->
            ItemRec(
                id = "d:" + sha256Hex(d.uri.toString()).take(16),
                uri = d.uri.toString(),
                kind = InvKind.Documents,
                takenAt = d.lastModified,
                bytes = d.sizeBytes,
                mime = d.mime,
                name = d.name,
                docId = d.documentId,
                parent = d.parentUri.toString(),
                path = d.path,
                flags = d.flags,
                localReason = when {
                    d.key in copies -> DeleteReason.Duplicate
                    d.name.startsWith("~$") -> DeleteReason.TempFile
                    else -> null
                }
            )
        }
        ctl.report(InvStage.Scanning, 1.0, ctl.meta.estAiSec, null, force = true)
        return recs
    }

    private class DocBatchResult(val verdicts: Map<String, DocVerdictRec>, val provider: String?)

    /** Verdictele AI pentru fișierele vii (duplicatele și temporarele nu mai pleacă), cu reluare după fiecare lot. */
    private suspend fun analyze(ctl: RunCtl, organizer: DocumentOrganizer, items: List<ItemRec>): NamesFile {
        val ctx = ctl.ctx
        var names = withContext(Dispatchers.IO) { InventoryStore.readNames(ctx, ctl.runId) } ?: NamesFile()
        val live = items.count { it.localReason == null }
        val todo = items.filter { it.localReason == null && it.id !in names.docs }.sortedWith(compareBy<ItemRec>({ it.path }, { it.id }))
        ctl.bins = docBins(names, live).ifEmpty { ctl.bins }
        val auth = if (todo.isEmpty()) null else AiGate.auth(ctl.app)
        if (auth == null) {
            ctl.report(InvStage.Naming, 1.0, 0, null, force = true)
            return names
        }
        val api = OrganizeApi(ctl.app.forjaApi)
        val batches = InvAssemble.docBatches(todo)
        val gate = Semaphore(InvRules.AI_PARALLEL)
        val mtx = Mutex()
        val stop = AtomicBoolean(false)
        val streak = AtomicInteger(0)
        val total = batches.size
        var finished = 0
        val t0 = SystemClock.elapsedRealtime()
        ctl.report(InvStage.Naming, 0.0, InvRules.aiSec(total), "Dau nume · 0 din $total", force = true)
        coroutineScope {
            batches.map { batch ->
                async {
                    gate.withPermit {
                        ctl.checkAlive()
                        val got = if (stop.get()) null else send(ctl, api, organizer, batch, stop, streak)
                        mtx.withLock {
                            // Un lot fără răspuns rămâne marcat (verdict gol): la reluare nu se mai trimite.
                            val fresh = batch.associate { it.id to (got?.verdicts?.get(it.id) ?: DocVerdictRec()) }
                            names = names.copy(docs = names.docs + fresh, provider = names.provider ?: got?.provider)
                            if (ctl.alive()) withContext(Dispatchers.IO) { try { InventoryStore.writeNames(ctx, ctl.runId, names) } catch (_: Exception) { } }
                            finished++
                            ctl.bins = docBins(names, live)
                            val elapsed = SystemClock.elapsedRealtime() - t0
                            val eta = ((total - finished) * elapsed / finished / 1000).toInt()
                            ctl.report(InvStage.Naming, finished.toDouble() / total, eta, "Dau nume · $finished din $total")
                        }
                    }
                }
            }.awaitAll()
        }
        return names
    }

    private suspend fun send(
        ctl: RunCtl, api: OrganizeApi, organizer: DocumentOrganizer, batch: List<ItemRec>, stop: AtomicBoolean, streak: AtomicInteger
    ): DocBatchResult? {
        val list = batch.map { toOrganizeItem(organizer, it) }
        batch.takeLast(4).forEach { ctl.addRecent(Uri.parse(it.uri)) }
        var attempt = 0
        while (true) {
            ctl.checkAlive()
            if (stop.get()) return null
            when (val res = api.organize(list)) {
                is OrganizeApi.Result.Ok -> {
                    streak.set(0)
                    val r = res.response
                    val verdicts = r.items.associate { v ->
                        v.id to DocVerdictRec(v.targetFolder.orEmpty(), v.categorie, v.deleteRecommended, v.displayReason.take(160))
                    }
                    val label = if (r.provider.isBlank() && r.model.isBlank()) null else providerLabel(r.provider, r.model)
                    return DocBatchResult(verdicts, label)
                }
                is OrganizeApi.Result.Fail -> {
                    if (attempt >= 1) {
                        if (streak.incrementAndGet() >= 3) stop.set(true)
                        return null
                    }
                    attempt++
                    delay(3_000L)
                }
            }
        }
    }

    /** Elementul /v1/organize: PDF-ul întreg (≤ 4 MB, citit abia la trimitere) sau un fragment de text (≤ 2000 caractere). */
    private suspend fun toOrganizeItem(organizer: DocumentOrganizer, r: ItemRec): OrganizeItemV2 {
        val d = r.toDocItem()
        val pdf = organizer.isPdf(d) && r.bytes in 1..OrganizeApi.MAX_PDF_BYTES
        val text = if (!pdf) organizer.textSnippet(d) else null
        return OrganizeItemV2(
            id = r.id,
            kind = "document",
            name = r.name.take(200),
            size = r.bytes,
            mime = r.mime.ifBlank { "application/octet-stream" },
            bucket = r.path.substringBeforeLast('/', "").ifBlank { null },
            takenAt = r.takenAt.takeIf { it > 0 },
            text = text,
            pdfSource = if (pdf) PdfSource(r.bytes) { organizer.readPdf(d, OrganizeApi.MAX_PDF_BYTES) } else null
        )
    }

    /** „Cutiile” în timpul AI: cele mai dese 4 nume de dosar primite până acum + fișierele încă nebotezate. */
    private fun docBins(names: NamesFile, live: Int): List<BinTick> {
        val named = names.docs.values.mapNotNull { v -> InvText.cleanName(v.dosar.ifBlank { v.categorie }).takeIf { it.isNotBlank() } }
        val top = named.groupBy { InvText.normalize(it) }.values
            .sortedWith(compareByDescending<List<String>> { it.size }.thenBy { it.first() })
            .take(4).map { BinTick(InvText.mostCommon(it) ?: it.first(), it.size) }
        val rest = live - names.docs.size
        return if (rest > 0) top + BinTick(null, rest) else top
    }
}
