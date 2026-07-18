package com.raj.ncscan

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

// helper WebDAV kecil: dipakai UploadWorker (PUT/MKCOL) dan MainActivity (daftar folder)
object Dav {

    fun auth(prefs: Prefs): String =
        // UTF-8: default ISO-8859-1 memangle username non-Latin jadi '?' -> 401 permanen
        Credentials.basic(prefs.username, prefs.appPassword, Charsets.UTF_8)

    // ponytail: trust-all = lubang MITM yang disengaja, di belakang toggle Settings
    // untuk server internal self-signed (sesuai PRD). Default OFF.
    fun client(trustAll: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            // redirect 301/302 mengubah PUT jadi GET (OkHttp redirectsToGet) ->
            // sukses palsu -> file lokal terhapus tanpa pernah terunggah. Jangan ikuti.
            .followRedirects(false)
            .followSslRedirects(false)
        if (trustAll) {
            val tm = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            b.sslSocketFactory(ssl.socketFactory, tm).hostnameVerifier { _, _ -> true }
        }
        return b.build()
    }

    /** Subfolder level-1 di dalam folder dasar + subPath. Blocking — panggil dari Dispatchers.IO. */
    fun listSubfolders(prefs: Prefs, subPath: String = ""): List<String> {
        val base = prefs.serverUrl.toHttpUrlOrNull() ?: return emptyList()
        val urlB = base.newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(prefs.username)
        (prefs.folder.trim('/').split('/') + subPath.trim('/').split('/'))
            .filter { it.isNotBlank() }
            .forEach { urlB.addPathSegment(it) }
        val url = urlB.build()
        val req = Request.Builder().url(url)
            .header("Authorization", auth(prefs))
            .header("Depth", "1")
            .method("PROPFIND", null) // tanpa body = allprop, Nextcloud oke
            .build()
        client(prefs.trustSelfSigned).newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) return emptyList()
            val xml = resp.body.string()
            val basePath = url.encodedPath.trimEnd('/')
            // ponytail: parse href pakai regex, bukan parser XML — respons sabre/dav stabil
            return Regex("<(?:[A-Za-z0-9]+:)?href>([^<]+)</(?:[A-Za-z0-9]+:)?href>")
                .findAll(xml)
                .map { it.groupValues[1].trim() }
                .filter { it.endsWith("/") } // koleksi = folder
                .map { it.trimEnd('/') }
                .filter { it != basePath && it.startsWith("$basePath/") }
                .map { URLDecoder.decode(it.substringAfterLast('/'), "UTF-8") }
                .filter { it.isNotBlank() }
                .distinct().sorted().toList()
        }
    }
}
