package com.raj.ncscan

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val file = File(inputData.getString(KEY_FILE) ?: return Result.failure())
        if (!file.exists()) return Result.success() // sudah terunggah oleh attempt sebelumnya
        val prefs = Prefs(applicationContext)
        // mode share: jangan upload; file menunggu di queue sampai Nextcloud diaktifkan lagi
        if (!prefs.useNextcloud) return Result.failure()
        if (!prefs.configured) return Result.failure()

        val base = prefs.serverUrl.toHttpUrlOrNull() ?: return Result.failure()
        // folder tujuan = folder dasar + posisi file relatif terhadap queue dir,
        // jadi pilihan subfolder per-scan selamat dari restart (tersimpan sebagai path)
        val queueRoot = File(applicationContext.filesDir, "queue")
        val rel = file.parentFile?.relativeToOrNull(queueRoot)?.path?.replace('\\', '/') ?: ""
        val segments = (prefs.folder.trim('/').split('/') + rel.split('/')).filter { it.isNotBlank() }
        val client = Dav.client(prefs.trustSelfSigned) // dibangun per run agar toggle langsung berlaku
        val auth = Dav.auth(prefs)
        val fileUrl = base.newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(prefs.username)
            .apply { segments.forEach { addPathSegment(it) } }
            .addPathSegment(file.name)
            .build()

        return try {
            var code = put(client, auth, fileUrl, file)
            if (code == 404 || code == 409) { // folder tujuan belum ada
                mkcolAll(client, auth, base, prefs.username, segments)
                code = put(client, auth, fileUrl, file)
            }
            when {
                // hanya 201 (dibuat) / 204 (timpa) = PUT WebDAV sukses; 200 dari
                // portal/proxy nyasar tidak boleh memicu file.delete()
                code == 201 || code == 204 -> { file.delete(); Result.success() }
                code in 400..499 -> failWith(code) // 401/403/404/409/413 dst.: permanen, retry percuma
                code in 300..399 -> failWith(code) // redirect = URL server salah (redirect tidak diikuti)
                else -> retryCapped() // 5xx / kode aneh: transien
            }
        } catch (e: IOException) {
            retryCapped() // offline / jaringan goyang → backoff + tunggu koneksi
        }
    }

    // ponytail: pagar retry sederhana; file gagal tetap di queue dan di-enqueue
    // ulang saat app dibuka, jadi failure di sini bukan akhir dunia
    private fun retryCapped(): Result =
        if (runAttemptCount >= 10) Result.failure() else Result.retry()

    private fun failWith(code: Int): Result =
        Result.failure(workDataOf(KEY_HTTP_CODE to code)) // kode tampil di status UI

    private fun put(client: OkHttpClient, auth: String, url: HttpUrl, file: File): Int =
        client.newCall(
            Request.Builder().url(url)
                .header("Authorization", auth)
                .put(file.asRequestBody("application/pdf".toMediaType()))
                .build()
        ).execute().use { it.code }

    private fun mkcolAll(client: OkHttpClient, auth: String, base: HttpUrl, user: String, segments: List<String>) {
        val b = base.newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(user)
        for (seg in segments) {
            b.addPathSegment(seg)
            client.newCall(
                Request.Builder().url(b.build())
                    .header("Authorization", auth)
                    .method("MKCOL", null)
                    .build()
            ).execute().close() // 201 dibuat / 405 sudah ada — dua-duanya beres
        }
    }

    companion object {
        private const val KEY_FILE = "file"
        const val KEY_HTTP_CODE = "http_code"
        const val TAG_UPLOAD = "upload"

        fun enqueue(context: Context, file: File) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(workDataOf(KEY_FILE to file.absolutePath))
                // tag = sumber data daftar riwayat di halaman awal
                .addTag(TAG_UPLOAD)
                .addTag(file.name)
                .addTag("t:${System.currentTimeMillis()}")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // unique per nama file: KEEP mencegah dobel; kerja yang sudah selesai bisa di-enqueue ulang
            WorkManager.getInstance(context).enqueueUniqueWork(file.name, ExistingWorkPolicy.KEEP, req)
        }
    }
}
