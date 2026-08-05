package com.forja.app.feature.cleanup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.forja.app.core.designsystem.*
import com.forja.app.core.designsystem.components.*
import com.forja.app.core.util.Fmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotoItem(
    val uri: Uri, val sizeBytes: Long, val dateAddedMs: Long,
    val name: String, val bucket: String,
    val suggested: Boolean, val reason: String
)

data class DocItem(
    val uri: Uri, val name: String, val sizeBytes: Long, val lastModified: Long,
    val suggested: Boolean, val reason: String
)

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0).replace('.', ',')
    bytes >= 1_000 -> "${bytes / 1000} KB"
    else -> "$bytes B"
}

/** Curățenie de azi — detox digital: tu decizi, app-ul sugerează, nimic nu urcă nicăieri. */
@Composable
fun CleanupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { com.forja.app.ForjaApp.from(context) }
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    var tab by remember { mutableStateOf(0) }

    // ── Poze ──
    fun photoPerm() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var hasPhotos by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, photoPerm()) == PackageManager.PERMISSION_GRANTED)
    }
    var photos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var selectedPhotos by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var loadingPhotos by remember { mutableStateOf(false) }

    suspend fun loadPhotos() {
        loadingPhotos = true
        photos = queryRecentPhotos(context)
        selectedPhotos = photos.filter { it.suggested }.map { it.uri }.toSet()
        loadingPhotos = false
    }

    val photoPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasPhotos = ok
        if (ok) scope.launch { loadPhotos() }
    }
    LaunchedEffect(hasPhotos) { if (hasPhotos && photos.isEmpty()) loadPhotos() }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val freed = photos.filter { it.uri in selectedPhotos }.sumOf { it.sizeBytes }
            photos = photos.filter { it.uri !in selectedPhotos }
            selectedPhotos = emptySet()
            toast.show("Curat! Ai eliberat ${fmtSize(freed)}. Telefon mai ușor.")
        }
    }

    fun deleteSelectedPhotos() {
        val uris = photos.filter { it.uri in selectedPhotos }.map { it.uri }
        if (uris.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
            scope.launch {
                var freed = 0L
                withContext(Dispatchers.IO) {
                    uris.forEach { u ->
                        try {
                            freed += photos.first { it.uri == u }.sizeBytes
                            context.contentResolver.delete(u, null, null)
                        } catch (_: Exception) { }
                    }
                }
                photos = photos.filter { it.uri !in selectedPhotos }
                selectedPhotos = emptySet()
                toast.show("Curat! Ai eliberat ${fmtSize(freed)}.")
            }
        }
    }

    // ── Documente ──
    var docs by remember { mutableStateOf<List<DocItem>>(emptyList()) }
    var selectedDocs by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var docFolder by remember { mutableStateOf<String?>(null) }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) { }
            scope.launch {
                val (name, list) = withContext(Dispatchers.IO) { queryDocs(context, treeUri) }
                docFolder = name
                docs = list
                selectedDocs = list.filter { it.suggested }.map { it.uri }.toSet()
            }
        }
    }

    fun deleteSelectedDocs() {
        val toDelete = docs.filter { it.uri in selectedDocs }
        if (toDelete.isEmpty()) return
        scope.launch {
            var freed = 0L
            withContext(Dispatchers.IO) {
                toDelete.forEach { d ->
                    try {
                        val df = DocumentFile.fromSingleUri(context, d.uri)
                        if (df != null && df.delete()) freed += d.sizeBytes
                    } catch (_: Exception) { }
                }
            }
            docs = docs.filter { it.uri !in selectedDocs }
            selectedDocs = emptySet()
            toast.show("Curat! Ai eliberat ${fmtSize(freed)}.")
        }
    }

    Column(
        Modifier.fillMaxSize().topoBackground(decor = false).statusBarsPadding().navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    "Curățenie de azi",
                    style = TitleModule.copy(fontSize = 24.sp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    "TELEFON MAI UȘOR, MINTE MAI LIMPEDE",
                    style = monoLabel(9, 0.14f).copy(color = Accent2),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            SecondaryButton("Înapoi", onClick = onBack, padV = 8.dp)
        }

        // Taburi
        Row(Modifier.padding(horizontal = 20.dp)) {
            listOf("Poze", "Documente").forEachIndexed { i, label ->
                Box(
                    Modifier
                        .padding(end = 10.dp)
                        .clip(ChipShape)
                        .background(if (i == tab) TabPillActive else Surface2)
                        .pressable({ tab = i })
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(label, style = BodyStrong.copy(fontSize = 13.sp, color = if (i == tab) Accent2 else TextSecondary))
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (tab == 0) {
            // POZE
            if (!hasPhotos) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ca să facem curat, FORJA are nevoie de acces la galerie.", style = Body, modifier = Modifier.padding(bottom = 12.dp))
                    Text("Pozele nu pleacă nicăieri — le vezi doar tu, aici, ca să decizi.", style = BodyTiny.copy(color = TextDim), modifier = Modifier.padding(bottom = 12.dp))
                    SecondaryButton("Dă accesul", onClick = { photoPermLauncher.launch(photoPerm()) })
                }
            } else {
                val selCount = selectedPhotos.size
                val selBytes = photos.filter { it.uri in selectedPhotos }.sumOf { it.sizeBytes }
                Text(
                    if (loadingPhotos) "se încarcă pozele…"
                    else "${photos.size} poze recente · ${photos.count { it.suggested }} sugerate de aruncat (tu confirmi)",
                    style = BodyTiny.copy(color = TextSecondary),
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 100.dp)
                ) {
                    items(photos, key = { it.uri }) { p ->
                        val sel = p.uri in selectedPhotos
                        Box(
                            Modifier
                                .padding(4.dp)
                                .aspectRatio(1f)
                                .clip(ThumbShape)
                                .pressable({
                                    selectedPhotos = if (sel) selectedPhotos - p.uri else selectedPhotos + p.uri
                                })
                        ) {
                            AsyncImage(model = p.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (p.suggested) {
                                Box(
                                    Modifier.align(Alignment.TopStart).padding(4.dp)
                                        .clip(ChipShape).background(Color(0xCC0A0A0B)).padding(horizontal = 5.dp, vertical = 2.dp)
                                ) { Text(p.reason, style = monoLabel(7, 0.06f).copy(color = Accent2)) }
                            }
                            Box(
                                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape)
                                    .background(if (sel) Error else Color(0x99000000))
                                    .border(1.5.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center
                            ) { if (sel) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                            Text(fmtSize(p.sizeBytes), style = monoLabel(7, 0.02f).copy(color = Color.White),
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
                        }
                    }
                }
                if (selCount > 0) {
                    PrimaryButton(
                        "Șterge $selCount ${if (selCount == 1) "poză" else "poze"} · ${fmtSize(selBytes)}",
                        onClick = { deleteSelectedPhotos() },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)
                    )
                }
            }
        } else {
            // DOCUMENTE
            Column(Modifier.fillMaxSize()) {
                if (docFolder == null) {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Alege un folder cu documente (ex: Download).", style = Body, modifier = Modifier.padding(bottom = 6.dp))
                        Text("Android nu ne lasă la toate fișierele fără riscuri — deci alegi tu folderul. Nimic nu pleacă de pe telefon.", style = BodyTiny.copy(color = TextDim), modifier = Modifier.padding(bottom = 14.dp))
                        SecondaryButton("Alege folderul", onClick = { treeLauncher.launch(null) })
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📁 $docFolder · ${docs.size} fișiere", style = BodySmall.copy(color = TextSecondary))
                        Text("alt folder", style = BodySmall.copy(color = Accent2), modifier = Modifier.pressable({ treeLauncher.launch(null) }))
                    }
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                        docs.forEach { d ->
                            val sel = d.uri in selectedDocs
                            ForjaCard(
                                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                                    .pressable({ selectedDocs = if (sel) selectedDocs - d.uri else selectedDocs + d.uri }),
                                fill = if (sel) Color(0x14FF4D3A) else Surface1,
                                stroke = if (sel) Color(0x66FF4D3A) else StrokeCard, padding = 12.dp
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name, style = BodyStrong.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            "${fmtSize(d.sizeBytes)} · ${Fmt.freshness(d.lastModified)}" + (if (d.suggested) " · ${d.reason}" else ""),
                                            style = BodyTiny.copy(color = if (d.suggested) Accent2 else TextDim)
                                        )
                                    }
                                    Box(
                                        Modifier.size(22.dp).clip(CircleShape)
                                            .background(if (sel) Error else Surface2)
                                            .border(1.dp, if (sel) Error else StrokeCardStrong, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) { if (sel) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(90.dp))
                    }
                    val selCount = selectedDocs.size
                    if (selCount > 0) {
                        val selBytes = docs.filter { it.uri in selectedDocs }.sumOf { it.sizeBytes }
                        PrimaryButton(
                            "Șterge $selCount ${if (selCount == 1) "fișier" else "fișiere"} · ${fmtSize(selBytes)}",
                            onClick = { deleteSelectedDocs() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)
                        )
                    }
                }
            }
        }
    }
}

private suspend fun queryRecentPhotos(context: Context): List<PhotoItem> = withContext(Dispatchers.IO) {
    val out = ArrayList<PhotoItem>()
    val proj = arrayOf(
        MediaStore.Images.Media._ID, MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME
    )
    try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idC = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val szC = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dtC = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val nmC = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bkC = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val sizeCount = HashMap<Long, Int>()
            val raw = ArrayList<Array<Any?>>()
            while (c.moveToNext() && raw.size < 300) {
                val id = c.getLong(idC); val sz = c.getLong(szC); val dt = c.getLong(dtC) * 1000L
                val nm = c.getString(nmC) ?: ""; val bk = c.getString(bkC) ?: ""
                sizeCount[sz] = (sizeCount[sz] ?: 0) + 1
                raw.add(arrayOf(id, sz, dt, nm, bk))
            }
            val now = System.currentTimeMillis()
            raw.forEach { r ->
                val id = r[0] as Long; val sz = r[1] as Long; val dt = r[2] as Long
                val nm = r[3] as String; val bk = r[4] as String
                val isShot = bk.lowercase().contains("screenshot") || nm.lowercase().startsWith("screenshot")
                val isTiny = sz in 1..80_000
                val isDup = (sizeCount[sz] ?: 0) > 1 && sz > 0
                val reason = when {
                    isShot -> "screenshot"
                    isDup -> "posibil duplicat"
                    isTiny -> "mic/meme"
                    else -> ""
                }
                out.add(
                    PhotoItem(
                        uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()),
                        sizeBytes = sz, dateAddedMs = dt, name = nm, bucket = bk,
                        suggested = reason.isNotEmpty(), reason = reason
                    )
                )
            }
        }
    } catch (_: Exception) { }
    out
}

private fun queryDocs(context: Context, treeUri: Uri): Pair<String, List<DocItem>> {
    val tree = DocumentFile.fromTreeUri(context, treeUri) ?: return "?" to emptyList()
    val folderName = tree.name ?: "Folder"
    val files = tree.listFiles().filter { it.isFile }
    val now = System.currentTimeMillis()
    val nameCount = HashMap<String, Int>()
    files.forEach { f -> val n = (f.name ?: "").substringBeforeLast('.'); nameCount[n] = (nameCount[n] ?: 0) + 1 }
    val list = files.map { f ->
        val name = f.name ?: "fișier"
        val size = f.length()
        val mod = f.lastModified()
        val baseName = name.substringBeforeLast('.')
        val old = mod > 0 && now - mod > 90L * 86_400_000L
        val big = size > 20_000_000
        val dup = name.contains("(1)") || name.contains(" copy") || (nameCount[baseName] ?: 0) > 1
        val reason = when {
            dup -> "posibil duplicat"; big -> "mare"; old -> "vechi (>3 luni)"; else -> ""
        }
        DocItem(f.uri, name, size, mod, reason.isNotEmpty(), reason)
    }.sortedByDescending { it.sizeBytes }
    return folderName to list
}
