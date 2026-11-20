package fr.phoenixpro.fichiers

import android.app.Application
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FichiersApp : Application()

enum class Cat(val label: String, val exts: Set<String>) {
    IMAGES("Photos", setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng")),
    VIDEOS("Vidéos", setOf("mp4", "mkv", "mov", "webm", "3gp", "avi")),
    AUDIO("Musique et sons", setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "amr")),
    DOCS("Documents", setOf("pdf", "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "txt", "csv", "rtf", "epub")),
    APK("Applis (APK)", setOf("apk")),
    ARCHIVES("Archives", setOf("zip", "rar", "7z", "tar", "gz")),
}

fun ext(f: File) = f.extension.lowercase(Locale.ROOT)
fun catOf(f: File): Cat? = Cat.entries.firstOrNull { ext(f) in it.exts }
fun mimeOf(f: File): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f)) ?: "*/*"

fun size(b: Long): String = when {
    b >= 1_000_000_000 -> String.format(Locale.FRANCE, "%.1f Go", b / 1e9)
    b >= 1_000_000 -> String.format(Locale.FRANCE, "%.1f Mo", b / 1e6)
    b >= 1_000 -> "${b / 1000} Ko"
    else -> "$b o"
}

fun date(ms: Long): String = SimpleDateFormat("d MMM yyyy · HH:mm", Locale.FRANCE).format(Date(ms))

data class Volume(val name: String, val root: File, val total: Long, val free: Long)

object Fs {
    val primary: File get() = Environment.getExternalStorageDirectory()
    val trash: File get() = File(primary, ".corbeille_phoenix").apply { mkdirs(); File(this, ".nomedia").createNewFile() }

    fun volumes(ctx: Context): List<Volume> {
        val out = mutableListOf<Volume>()
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (v in sm.storageVolumes) {
            val dir = if (android.os.Build.VERSION.SDK_INT >= 30) v.directory else (if (v.isPrimary) primary else null)
            if (dir == null || !dir.exists()) continue
            val st = try { StatFs(dir.path) } catch (e: Exception) { continue }
            out.add(Volume(if (v.isPrimary) "Mémoire interne" else (v.getDescription(ctx) ?: "Carte SD"), dir, st.totalBytes, st.availableBytes))
        }
        if (out.isEmpty()) StatFs(primary.path).let { out.add(Volume("Mémoire interne", primary, it.totalBytes, it.availableBytes)) }
        return out
    }

    fun list(dir: File, hidden: Boolean, sort: String): List<File> {
        val items = (dir.listFiles() ?: emptyArray()).filter { (hidden || !it.name.startsWith(".")) && it != trash }
        val cmp: Comparator<File> = when (sort) {
            "date" -> compareByDescending { it.lastModified() }
            "taille" -> compareByDescending { if (it.isDirectory) -1L else it.length() }
            "type" -> compareBy({ ext(it) }, { it.name.lowercase() })
            else -> compareBy { it.name.lowercase() }
        }
        return items.sortedWith(compareBy<File> { !it.isDirectory }.then(cmp))
    }

    fun childCount(d: File): Int = d.list()?.count { !it.startsWith(".") } ?: 0

    /** Nom libre dans le dossier : « photo (1).jpg », « photo (2).jpg »… */
    fun freeName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name); val e = if ('.' in name && !name.startsWith(".")) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$e"); i++ }
        return f
    }

    fun copy(src: File, destDir: File): File {
        val target = freeName(destDir, src.name)
        if (src.isDirectory) src.copyRecursively(target) else src.copyTo(target)
        return target
    }

    fun move(src: File, destDir: File): File {
        val target = freeName(destDir, src.name)
        if (!src.renameTo(target)) { if (src.isDirectory) src.copyRecursively(target) else src.copyTo(target); src.deleteRecursively() }
        return target
    }

    fun rename(f: File, newName: String): File? {
        val t = File(f.parentFile, newName)
        if (t.exists() || newName.isBlank() || '/' in newName) return null
        return if (f.renameTo(t)) t else null
    }

    // ---------------------------------------------------------------- corbeille

    private fun index(): JSONObject = try { JSONObject(File(trash, ".index.json").readText()) } catch (e: Exception) { JSONObject() }
    private fun saveIndex(o: JSONObject) = File(trash, ".index.json").writeText(o.toString())

    fun toTrash(f: File) {
        val o = index()
        val stamp = System.currentTimeMillis()
        val t = freeName(trash, "${stamp}_${f.name}")
        if (!f.renameTo(t)) { if (f.isDirectory) f.copyRecursively(t) else f.copyTo(t); f.deleteRecursively() }
        o.put(t.name, JSONObject().put("from", f.absolutePath).put("at", stamp))
        saveIndex(o)
    }

    data class Trashed(val file: File, val from: String, val at: Long)

    fun trashed(): List<Trashed> {
        val o = index()
        return (trash.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }.map { f ->
            val e = o.optJSONObject(f.name)
            Trashed(f, e?.optString("from") ?: f.name.substringAfter('_'), e?.optLong("at") ?: f.lastModified())
        }.sortedByDescending { it.at }
    }

    fun restore(t: Trashed): File {
        val orig = File(t.from)
        val dir = orig.parentFile?.apply { mkdirs() } ?: primary
        val target = freeName(dir, orig.name)
        if (!t.file.renameTo(target)) { t.file.copyRecursively(target); t.file.deleteRecursively() }
        val o = index(); o.remove(t.file.name); saveIndex(o)
        return target
    }

    fun purge(t: Trashed) { t.file.deleteRecursively(); val o = index(); o.remove(t.file.name); saveIndex(o) }

    fun purgeOld() { val limit = System.currentTimeMillis() - 30L * 86_400_000; trashed().filter { it.at < limit }.forEach { purge(it) } }

    // ---------------------------------------------------------------- zip

    fun zip(files: List<File>, dest: File) {
        ZipOutputStream(FileOutputStream(dest)).use { zos ->
            fun add(f: File, path: String) {
                if (f.isDirectory) {
                    zos.putNextEntry(ZipEntry("$path/")); zos.closeEntry()
                    f.listFiles()?.forEach { add(it, "$path/${it.name}") }
                } else {
                    zos.putNextEntry(ZipEntry(path)); FileInputStream(f).use { it.copyTo(zos) }; zos.closeEntry()
                }
            }
            files.forEach { add(it, it.name) }
        }
    }

    fun unzip(zip: File, destDir: File): File {
        val out = freeName(destDir, zip.nameWithoutExtension).apply { mkdirs() }
        val root = out.canonicalPath
        ZipInputStream(FileInputStream(zip)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                val t = File(out, e.name)
                if (!t.canonicalPath.startsWith(root)) continue // protection contre les chemins piégés
                if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); FileOutputStream(t).use { zis.copyTo(it) } }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- catégories, récents, gros fichiers, recherche

    /** Fichiers connus du système (MediaStore), filtrés. */
    fun query(ctx: Context, where: String?, args: Array<String>?, order: String, limit: Int): List<File> {
        val out = mutableListOf<File>()
        val uri = MediaStore.Files.getContentUri("external")
        try {
            ctx.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns.DATA), where, args, order)?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val p = c.getString(0) ?: continue
                    val f = File(p)
                    if (f.isFile && !p.contains("/.")) out.add(f)
                }
            }
        } catch (_: Exception) { }
        return out
    }

    fun category(ctx: Context, cat: Cat): List<File> {
        if (cat == Cat.IMAGES || cat == Cat.VIDEOS || cat == Cat.AUDIO) {
            val type = when (cat) { Cat.IMAGES -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE; Cat.VIDEOS -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO; else -> MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO }
            return query(ctx, "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?", arrayOf(type.toString()), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", 3000)
        }
        val like = cat.exts.joinToString(" OR ") { "${MediaStore.Files.FileColumns.DATA} LIKE ?" }
        return query(ctx, like, cat.exts.map { "%.$it" }.toTypedArray(), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", 3000)
    }

    fun recent(ctx: Context): List<File> {
        val since = System.currentTimeMillis() / 1000 - 30L * 86400
        return query(ctx, "${MediaStore.Files.FileColumns.DATE_MODIFIED}>? AND ${MediaStore.Files.FileColumns.SIZE}>0", arrayOf(since.toString()), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", 40)
    }

    fun big(ctx: Context): List<File> =
        query(ctx, "${MediaStore.Files.FileColumns.SIZE}>?", arrayOf("50000000"), "${MediaStore.Files.FileColumns.SIZE} DESC", 200)

    /** Espace occupé par catégorie (octets). */
    fun usage(ctx: Context): Map<Cat, Long> {
        val m = mutableMapOf<Cat, Long>()
        try {
            ctx.contentResolver.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val p = c.getString(0) ?: continue
                    val cat = Cat.entries.firstOrNull { p.substringAfterLast('.', "").lowercase() in it.exts } ?: continue
                    m[cat] = (m[cat] ?: 0L) + c.getLong(1)
                }
            }
        } catch (_: Exception) { }
        return m
    }

    /** Recherche par nom dans tout le stockage (s'arrête à 400 résultats). */
    fun search(roots: List<File>, q: String, onFound: (File) -> Unit) {
        val needle = q.lowercase()
        var n = 0
        for (r in roots) {
            r.walkTopDown().onEnter { !it.name.startsWith(".") && it != trash && n < 400 }.forEach { f ->
                if (n < 400 && f != r && f.name.lowercase().contains(needle)) { onFound(f); n++ }
            }
        }
    }

    fun rescan(ctx: Context, files: List<File>) {
        val paths = files.flatMap { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.map { f -> f.absolutePath }.toList() else listOf(it.absolutePath) }
        if (paths.isNotEmpty()) MediaScannerConnection.scanFile(ctx, paths.take(2000).toTypedArray(), null, null)
    }

    // ---- création de fichiers

    val TEXT_EXT = setOf("txt", "md", "csv", "json", "log", "xml", "ini", "conf", "html", "htm", "kt", "java", "py", "js", "css", "srt", "properties", "yml", "yaml")
    fun isText(f: File) = f.isFile && ext(f) in TEXT_EXT && f.length() < 2_000_000

    val documents: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).apply { mkdirs() }

    /** Assemble des images en PDF : une page A4 par image, ajustée et centrée. */
    fun imagesToPdf(images: List<File>, out: File): File {
        val doc = android.graphics.pdf.PdfDocument()
        try {
            images.forEachIndexed { i, f ->
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.path, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / sample > 2400) sample *= 2
                var bmp = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@forEachIndexed
                val rot = try {
                    when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                        6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                    }
                } catch (e: Exception) { 0f }
                if (rot != 0f) bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rot) }, true)
                val landscape = bmp.width > bmp.height
                val w = if (landscape) 842 else 595; val h = if (landscape) 595 else 842; val m = 24f
                val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, i + 1).create())
                val s = minOf((w - 2 * m) / bmp.width, (h - 2 * m) / bmp.height)
                val dw = bmp.width * s; val dh = bmp.height * s
                page.canvas.drawColor(android.graphics.Color.WHITE)
                page.canvas.drawBitmap(bmp, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                doc.finishPage(page)
                bmp.recycle()
            }
            out.outputStream().use { doc.writeTo(it) }
        } finally { doc.close() }
        return out
    }

    /** Convertit un texte en PDF A4 (retour à la ligne automatique). */
    fun textToPdf(title: String, text: String, out: File): File {
        val doc = android.graphics.pdf.PdfDocument()
        val w = 595; val h = 842; val m = 48f
        val tp = android.graphics.Paint().apply { textSize = 16f; isFakeBoldText = true; isAntiAlias = true; color = android.graphics.Color.rgb(20, 22, 25) }
        val bp = android.graphics.Paint().apply { textSize = 11f; isAntiAlias = true; color = android.graphics.Color.rgb(30, 32, 36) }
        var no = 0
        var page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, ++no).create())
        var y = m + 16
        page.canvas.drawText(title, m, y, tp); y += 28
        for (para in text.split("\n")) {
            var rest = para
            do {
                var n = bp.breakText(rest, true, w - 2 * m, null)
                if (n < rest.length) { val sp = rest.lastIndexOf(' ', n); if (sp > 0) n = sp + 1 }
                if (y > h - m) { doc.finishPage(page); page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, ++no).create()); y = m + 11 }
                page.canvas.drawText(rest.substring(0, n).trimEnd(), m, y, bp); y += 15
                rest = rest.substring(n)
            } while (rest.isNotEmpty())
        }
        doc.finishPage(page)
        try { out.outputStream().use { doc.writeTo(it) } } finally { doc.close() }
        return out
    }
}
