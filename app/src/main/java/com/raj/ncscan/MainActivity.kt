package com.raj.ncscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnScan: Button
    private var autoLaunched = false
    private var uploadLive: LiveData<List<WorkInfo>>? = null // hanya scan terbaru yang menyetir status UI

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val pages = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
                ?.pages?.map { it.imageUri }.orEmpty()
            if (res.resultCode == RESULT_OK && pages.isNotEmpty()) processScan(pages)
            else showIdle(getString(R.string.ready))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        btnScan = findViewById(R.id.btn_scan)
        btnScan.setOnClickListener { startScan() }
        findViewById<Button>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // penyembuh crash/offline: buang .part yatim (save terpotong), enqueue ulang sisa
        // .pdf (KEEP mencegah dobel), dan tampilkan status upload file terbaru.
        // Rekursif: subdir queue = subfolder tujuan pilihan user.
        queueDir().walkTopDown().filter { it.isFile && it.extension == "part" }.forEach { it.delete() }
        val pending = queueDir().walkTopDown().filter { it.isFile && it.extension == "pdf" }
            .sortedBy { it.name }.toList()
        // file mode share sudah diterima app tujuan saat launch berikutnya — bersihkan
        File(cacheDir, "share").listFiles()?.forEach { it.delete() }

        // riwayat upload per file: sumber = record WorkManager ber-tag "upload"
        val history = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        findViewById<ListView>(R.id.history).adapter = history
        WorkManager.getInstance(this).getWorkInfosByTagLiveData(UploadWorker.TAG_UPLOAD)
            .observe(this) { infos ->
                val rows = infos.orEmpty().map { wi ->
                    val name = wi.tags.firstOrNull { it.endsWith(".pdf") } ?: "?"
                    val t = wi.tags.firstOrNull { it.startsWith("t:") }?.drop(2)?.toLongOrNull() ?: 0L
                    val label = when (wi.state) {
                        WorkInfo.State.SUCCEEDED -> "✓ $name"
                        WorkInfo.State.RUNNING -> "⬆ $name — mengunggah…"
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "⏳ $name — antre"
                        WorkInfo.State.FAILED -> {
                            val code = wi.outputData.getInt(UploadWorker.KEY_HTTP_CODE, 0)
                            "✗ $name — gagal" + if (code > 0) " (HTTP $code)" else ""
                        }
                        WorkInfo.State.CANCELLED -> "— $name — dibatalkan"
                    }
                    t to label
                }.sortedByDescending { it.first }.take(100).map { it.second }
                history.clear()
                history.addAll(rows)
            }
        pending.forEach { UploadWorker.enqueue(this, it) }
        pending.lastOrNull()?.let { observeUpload(it.name, it.length() / 1024, "") }

        // alur ala Nextcloud iOS: buka app → langsung kamera (sekali per lifecycle activity)
        autoLaunched = savedInstanceState?.getBoolean("autoLaunched") ?: false
        if (!autoLaunched) {
            autoLaunched = true
            if (Prefs(this).configured) startScan()
            else startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("autoLaunched", autoLaunched)
    }

    private fun startScan() {
        if (!Prefs(this).configured) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val options = GmsDocumentScannerOptions.Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG) // PDF dibuat sendiri
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                showIdle(getString(R.string.scanner_unavailable, e.message ?: ""))
            }
    }

    private fun processScan(pages: List<Uri>) {
        status.text = getString(R.string.processing, pages.size)
        progress.visibility = View.VISIBLE
        btnScan.isEnabled = false
        lifecycleScope.launch {
            val name = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pdf"
            val out = File(queueDir(), name)
            val ocrWords = try {
                withContext(Dispatchers.Default) {
                    OcrPdfBuilder.build(applicationContext, pages, Prefs(this@MainActivity), out)
                }
            } catch (e: CancellationException) {
                throw e // activity mati (rotasi dsb.): jangan telan pembatalan
            } catch (e: Exception) {
                showIdle(getString(R.string.pdf_failed, e.message ?: e.javaClass.simpleName))
                return@launch
            }
            val kb = out.length() / 1024
            val note = if (ocrWords == 0) "\n" + getString(R.string.ocr_skipped) else ""
            progress.visibility = View.GONE
            if (isFinishing || isDestroyed) return@launch // sweep di launch berikutnya → folder utama
            // tombol tetap nonaktif + status terisi sampai alur folder/prefix selesai,
            // supaya user tahu app masih bekerja dan tidak keluar sembarangan
            status.text = getString(R.string.awaiting_choice)
            val p = Prefs(this@MainActivity)
            if (p.useNextcloud) browseFolder(p, "", out, kb, note)
            else askPrefix { pre -> sharePdf(pre, out, kb) }
        }
    }

    // cache listing per path selama activity hidup; PROPFIND cuma sekali per folder
    private val folderCache = mutableMapOf<String, List<String>>()

    /** Penjelajah folder bertingkat: tap nama = masuk, ".." = naik, tombol = pilih di sini. */
    private fun browseFolder(prefs: Prefs, path: String, out: File, kb: Long, note: String) {
        lifecycleScope.launch {
            // kunci cache diikat ke folder dasar: ganti Folder tujuan di Settings = cache lama hangus
            val key = "${prefs.folder}|$path"
            val cached = folderCache[key]
            if (cached == null) { // akan PROPFIND ke server: tunjukkan sibuk
                progress.visibility = View.VISIBLE
                status.text = getString(R.string.loading_folders)
            }
            val subs = cached ?: withContext(Dispatchers.IO) {
                try {
                    Dav.listSubfolders(prefs, path).also { folderCache[key] = it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList() // offline: tetap bisa "Upload di sini" / folder baru
                }
            }
            if (isFinishing || isDestroyed) return@launch
            progress.visibility = View.GONE
            status.text = getString(R.string.awaiting_choice)
            val here = listOf(prefs.folder, path).filter { it.isNotBlank() }
                .joinToString("/").ifBlank { "/" }
            val items = buildList {
                if (path.isNotBlank()) add(getString(R.string.folder_up))
                subs.forEach { add("📁 $it") }
                add(getString(R.string.folder_new))
            }.toTypedArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(getString(R.string.folder_pick_at, here))
                .setItems(items) { _, i ->
                    val offset = if (path.isNotBlank()) 1 else 0
                    when {
                        path.isNotBlank() && i == 0 ->
                            browseFolder(prefs, path.substringBeforeLast('/', ""), out, kb, note)
                        i - offset < subs.size ->
                            browseFolder(prefs, joinPath(path, subs[i - offset]), out, kb, note)
                        else -> promptNewFolder { name ->
                            val dest = if (name.isBlank()) path else joinPath(path, name)
                            askPrefix { p -> finishUpload(dest, p, out, kb, note) }
                        }
                    }
                }
                .setPositiveButton(R.string.upload_here) { _, _ ->
                    askPrefix { p -> finishUpload(path, p, out, kb, note) }
                }
                .setOnCancelListener { // batal navigasi = pakai posisi sekarang
                    askPrefix { p -> finishUpload(path, p, out, kb, note) }
                }
                .show()
        }
    }

    // EditText polos di dialog nempel ke tepi; beri nafas ala Material
    private fun boxed(v: View): View = FrameLayout(this).apply {
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(v)
    }

    private fun promptNewFolder(onChosen: (String) -> Unit) {
        val input = EditText(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.folder_new_title)
            .setView(boxed(input))
            .setPositiveButton(R.string.upload) { _, _ ->
                onChosen(input.text.toString().trim().trim('/'))
            }
            .setOnCancelListener { onChosen("") }
            .show()
    }

    /** Prefix nama file, wajib diisi — biasanya nama toko/perusahaan pada nota. */
    private fun askPrefix(onDone: (String) -> Unit) {
        val input = EditText(this).apply { hint = getString(R.string.prefix_hint) }
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.prefix_title)
            .setMessage(R.string.prefix_note)
            .setView(boxed(input))
            .setPositiveButton(R.string.upload, null) // handler manual agar bisa tahan dismiss
            .setCancelable(false) // wajib isi: back/tap-luar tidak bisa melewati
            .create()
        dlg.show()
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p = input.text.toString().trim()
            if (p.isBlank()) input.error = getString(R.string.prefix_required)
            else { dlg.dismiss(); onDone(p) }
        }
    }

    /** PREFIX_DD-MM-YYYY_HH-MM-SS.pdf — zona waktu mengikuti setelan device. */
    private fun stampedName(prefix: String): String {
        val stamp = SimpleDateFormat("dd-MM-yyyy_HH-mm-ss", Locale.US).format(Date())
        val safe = prefix.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        return "${safe}_$stamp.pdf"
    }

    private fun finishUpload(sub: String, prefix: String, out: File, kb: Long, note: String) {
        val destDir = if (sub.isBlank()) queueDir() else File(queueDir(), sub).apply { mkdirs() }
        var target = File(destDir, stampedName(prefix))
        // gagal rename (nama/path tak valid di filesystem) → upload dengan nama & lokasi asal
        if (!out.renameTo(target)) target = out
        btnScan.isEnabled = true // alur pilihan selesai; status selanjutnya dari observer
        UploadWorker.enqueue(this, target)
        observeUpload(target.name, kb, note)
    }

    /** Mode non-Nextcloud: rename lalu buka share sheet Android. */
    private fun sharePdf(prefix: String, out: File, kb: Long) {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        var target = File(dir, stampedName(prefix))
        if (!out.renameTo(target)) target = out
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, target.name))
        btnScan.isEnabled = true
        progress.visibility = View.GONE
        status.text = getString(R.string.shared_ready, target.name, kb)
    }

    private fun joinPath(path: String, name: String) =
        if (path.isBlank()) name else "$path/$name"

    private fun observeUpload(workName: String, kb: Long, note: String) {
        uploadLive?.removeObservers(this) // scan lama berhenti menyetir status UI
        val live = WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(workName)
        uploadLive = live
        live.observe(this) { infos ->
            val info = infos?.firstOrNull() ?: return@observe
            val state = info.state
            when (state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    progress.visibility = View.GONE
                    status.text = getString(R.string.queued, kb) + note
                }
                WorkInfo.State.RUNNING -> {
                    progress.visibility = View.VISIBLE
                    status.text = getString(R.string.uploading, kb) + note
                }
                WorkInfo.State.SUCCEEDED -> {
                    progress.visibility = View.GONE
                    status.text = getString(R.string.uploaded, workName, kb) + note
                }
                WorkInfo.State.FAILED -> {
                    progress.visibility = View.GONE
                    val code = info.outputData.getInt(UploadWorker.KEY_HTTP_CODE, 0)
                    status.text = (if (code > 0) getString(R.string.upload_failed_code, code)
                        else getString(R.string.upload_failed)) + note
                }
                WorkInfo.State.CANCELLED -> progress.visibility = View.GONE
            }
            if (state.isFinished) {
                live.removeObservers(this)
                if (uploadLive === live) uploadLive = null
            }
        }
    }

    private fun showIdle(msg: String) {
        progress.visibility = View.GONE
        btnScan.isEnabled = true
        status.text = msg
    }

    private fun queueDir(): File = File(filesDir, "queue").apply { mkdirs() }
}
