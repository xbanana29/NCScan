# NC Scan

Scanner dokumen Android untuk Nextcloud — pengganti fitur scan bawaan Nextcloud Android yang menghasilkan file 5MB+/halaman. NC Scan meniru alur Nextcloud iOS: buka app → langsung kamera → scan → OCR otomatis → PDF kecil yang bisa di-search → upload ke Nextcloud (atau dibagikan ke aplikasi lain).

[**⬇ Download APK terbaru**](../../releases/latest)

## Fitur

- **Langsung scan** — buka app langsung masuk kamera dengan deteksi tepi dokumen live, auto-capture, multi-halaman, crop/rotate/filter (ML Kit Document Scanner, UI yang sama dengan Google Drive).
- **OCR otomatis** — teks dikenali di background tanpa setup bahasa/model per sesi (ML Kit Text Recognition, on-device). Hasilnya ditanam sebagai invisible text layer: PDF bisa di-Ctrl+F dan di-select di viewer mana pun.
- **File kecil** — kompresi at-source dengan 3 preset kualitas; dokumen teks standar ±40–150 KB/halaman (vs 5MB+ scanner Nextcloud bawaan).
- **Upload Nextcloud via WebDAV** — pilih folder tujuan bertingkat langsung dari server (termasuk Group Folder), buat folder baru dari app, antre otomatis saat offline dan retry saat koneksi pulih.
- **Prefix nama file wajib** — format `PREFIX_DD-MM-YYYY_HH-MM-SS.pdf`, zona waktu mengikuti device.
- **Riwayat upload per file** di halaman awal: ⏳ antre · ⬆ mengunggah · ✓ sukses · ✗ gagal (dengan kode HTTP).
- **Mode share** — matikan switch Nextcloud di Pengaturan, hasil scan langsung dibagikan ke WhatsApp/Drive/Email/dll lewat share sheet Android.
- **Kredensial aman** — app password dienkripsi AES/GCM via Android Keystore, tidak pernah tersimpan plaintext.
- Tampilan Material 3 + Dynamic Colors (warna mengikuti wallpaper di Android 12+).

## Kebutuhan minimum

| Kebutuhan | Nilai |
|---|---|
| Android | 6.0 (API 23) ke atas |
| Google Play Services | Wajib (scanner & model OCR) — HP de-Googled tidak didukung |
| RAM | ≥ 1,7 GB (syarat ML Kit Document Scanner) |
| Internet | Sekali di awal untuk unduh model OCR; setelah itu scan & OCR jalan offline |
| Server | Nextcloud dengan WebDAV aktif (bawaan) |

## Cara pakai

### Persiapan (sekali saja)

1. Buat **app password** di Nextcloud: buka Nextcloud di browser → **Settings → Security → Devices & sessions** → beri nama (mis. "NC Scan") → **Create new app password** → salin. *Jangan pakai password login biasa — apalagi jika 2FA aktif.*
2. Install APK (dari [Releases](../../releases/latest)), buka app → halaman Pengaturan muncul otomatis:
   - **URL server** — mis. `https://cloud.perusahaan.id`
   - **Username** dan **App password**
   - **Folder tujuan** — folder dasar semua scan, mis. `Scans` atau nama Group Folder. Penjelajah folder saat upload dibatasi di dalam folder ini.
   - **Kualitas kompresi** — Rendah/Sedang/Tinggi (default Sedang ±120 KB/hal)
   - **Terima self-signed certificate** — hanya untuk server internal tanpa sertifikat publik (default mati; membuka celah MITM saat aktif)
3. Simpan.

### Scan harian

1. Buka app → kamera langsung aktif → arahkan ke dokumen (auto-capture) → tambah halaman bila perlu → tap ✓/Next.
2. Pilih folder tujuan (tap nama folder untuk masuk, `⬅ ..` untuk naik, **Upload di sini** untuk memilih, `➕ Folder baru…` untuk membuat).
3. Ketik **prefix** (wajib — biasanya nama toko/perusahaan pada nota) → Upload.
4. Pantau daftar **Riwayat upload** di halaman awal. File gagal otomatis dicoba ulang setiap app dibuka.

### Mode share (tanpa Nextcloud)

Pengaturan → matikan **Upload ke Nextcloud** → setelah scan cukup isi prefix, lalu pilih aplikasi tujuan dari share sheet.

## Build sendiri

Butuh JDK 17 dan Android SDK (platform 36).

```
git clone https://github.com/nikokevin29/NCScan.git
cd NCScan
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Rilis resmi dibangun otomatis oleh GitHub Actions setiap tag `v*` di-push, ditandatangani dengan keystore rilis, dan diunggah ke halaman Releases.

## Teknologi

Kotlin (native, single module) · ML Kit Document Scanner & Text Recognition v2 · PDFBox-Android (invisible text layer + JPEG passthrough) · OkHttp (WebDAV PUT/MKCOL/PROPFIND) · WorkManager (antrean upload) · Android Keystore (enkripsi kredensial) · Material 3.

## Lisensi

[MIT](LICENSE)
