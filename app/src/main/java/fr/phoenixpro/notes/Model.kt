package fr.phoenixpro.notes

import android.app.Application
import android.content.Context
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import kotlinx.coroutines.tasks.await
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

class NotesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

/** Largeur logique d'une page : les traits sont enregistrés dans ces unités, quel que soit l'écran. */
const val PAGE_W = 1000f
const val PAGE_H = 1414f // format A4

enum class Tool { PEN, HIGHLIGHT, ERASER }

/** Un trait : points x, y, pression, temps (ms) à la suite. */
class Stroke(val tool: Tool, val color: Int, val width: Float, val pts: FloatArray) {
    val count get() = pts.size / 4
    fun x(i: Int) = pts[i * 4]
    fun y(i: Int) = pts[i * 4 + 1]
    fun p(i: Int) = pts[i * 4 + 2]
    fun t(i: Int) = pts[i * 4 + 3]
    fun minY(): Float { var m = Float.MAX_VALUE; for (i in 0 until count) m = minOf(m, y(i)); return m }
    fun maxY(): Float { var m = 0f; for (i in 0 until count) m = maxOf(m, y(i)); return m }
}

class Note(
    val id: Long,
    var title: String = "",
    var text: String = "",
    var bg: String = "lignes",       // blanc, lignes, carreaux, points
    var color: Int = 0,              // index de couleur d'étiquette, 0 = aucune
    var pinned: Boolean = false,
    var updated: Long = System.currentTimeMillis(),
    var pages: Int = 1,
    val strokes: MutableList<Stroke> = mutableListOf(),
    var folder: String = "",
    var pdfPages: Int = 0,           // pages d'un PDF importé, servant de fond
) {
    val isEmpty get() = title.isBlank() && text.isBlank() && strokes.isEmpty()
    fun display() = title.ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: if (strokes.isNotEmpty()) "Note manuscrite" else "Note sans titre" }
}

object Store {
    private lateinit var dir: File
    fun init(ctx: Context) { if (!::dir.isInitialized) dir = File(ctx.filesDir, "notes").apply { mkdirs() } }

    fun save(n: Note) {
        n.updated = System.currentTimeMillis()
        File(dir, "${n.id}.json").writeText(JSONObject().put("id", n.id).put("title", n.title).put("text", n.text).put("bg", n.bg)
            .put("color", n.color).put("pinned", n.pinned).put("updated", n.updated).put("pages", n.pages)
            .put("folder", n.folder).put("pdfPages", n.pdfPages).toString())
        val tmp = File(dir, "${n.id}.ink.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeInt(1); o.writeInt(n.strokes.size)
            n.strokes.forEach { s -> o.writeInt(s.tool.ordinal); o.writeInt(s.color); o.writeFloat(s.width); o.writeInt(s.pts.size); s.pts.forEach { o.writeFloat(it) } }
        }
        tmp.renameTo(File(dir, "${n.id}.ink"))
    }

    fun load(id: Long, withInk: Boolean = true): Note? = try {
        val o = JSONObject(File(dir, "$id.json").readText())
        val n = Note(o.getLong("id"), o.optString("title"), o.optString("text"), o.optString("bg", "lignes"), o.optInt("color"),
            o.optBoolean("pinned"), o.optLong("updated"), o.optInt("pages", 1),
            folder = o.optString("folder"), pdfPages = o.optInt("pdfPages"))
        val ink = File(dir, "$id.ink")
        if (withInk && ink.exists()) DataInputStream(ink.inputStream().buffered()).use { i ->
            i.readInt(); repeat(i.readInt()) {
                val tool = Tool.entries[i.readInt()]; val c = i.readInt(); val w = i.readFloat()
                val pts = FloatArray(i.readInt()) { i.readFloat() }
                n.strokes.add(Stroke(tool, c, w, pts))
            }
        }
        n
    } catch (e: Exception) { null }

    fun all(): List<Note> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { load(it.nameWithoutExtension.toLongOrNull() ?: return@mapNotNull null) }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })

    fun delete(id: Long) {
        File(dir, "$id.json").delete(); File(dir, "$id.ink").delete()
        dir.listFiles { f -> f.name.startsWith("${id}_p") }?.forEach { it.delete() }
    }

    /** Image de la page n d'un PDF importé (fond de la note). */
    fun pageImage(id: Long, n: Int) = File(dir, "${id}_p$n.jpg")

    // ---- dossiers
    private val foldersFile get() = File(dir, "dossiers.json")
    var folders: List<String>
        get() = try { val a = JSONArray(foldersFile.readText()); (0 until a.length()).map { a.getString(it) } } catch (e: Exception) { emptyList() }
        set(v) = foldersFile.writeText(JSONArray(v.distinct()).toString())

    fun renameFolder(old: String, new: String) {
        folders = folders.map { if (it == old) new else it }
        all().filter { it.folder == old }.forEach { it.folder = new; saveKeepDate(it) }
    }

    fun deleteFolder(name: String) {
        folders = folders - name
        all().filter { it.folder == name }.forEach { it.folder = ""; saveKeepDate(it) }
    }

    private fun saveKeepDate(n: Note) { val d = n.updated; save(n); n.updated = d; File(dir, "${n.id}.json").let { f -> f.writeText(JSONObject(f.readText()).put("updated", d).toString()) } }

    /** Importe un PDF : chaque page devient l'image de fond d'une page de la note (60 pages au plus). */
    fun importPdf(ctx: Context, uri: Uri, folder: String): Note? {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        var name = "Document PDF"
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0)?.removeSuffix(".pdf")?.removeSuffix(".PDF") ?: name }
        } catch (_: Exception) { }
        val r = PdfRenderer(pfd)
        try {
            val n = Note(System.currentTimeMillis(), title = name, bg = "blanc", folder = folder)
            val count = minOf(r.pageCount, 60)
            val w = 1240; val h = (w * PAGE_H / PAGE_W).toInt()
            for (i in 0 until count) {
                val page = r.openPage(i)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                val s = minOf(w.toFloat() / page.width, h.toFloat() / page.height)
                val m = Matrix().apply { postScale(s, s); postTranslate((w - page.width * s) / 2, (h - page.height * s) / 2) }
                page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                pageImage(n.id, i).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bmp.recycle()
            }
            n.pdfPages = count; n.pages = count
            save(n)
            return n
        } finally { r.close(); pfd.close() }
    }
}

/** Reconnaissance de l'écriture (français), entièrement sur le téléphone après un téléchargement unique. */
object Writing {
    private val model by lazy {
        DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.fromLanguageTag("fr-FR")!!).build()
    }

    suspend fun ready(): Boolean = try { RemoteModelManager.getInstance().isModelDownloaded(model).await() } catch (e: Exception) { false }

    suspend fun download(): Boolean = try {
        RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build()).await(); true
    } catch (e: Exception) { false }

    /** Regroupe les traits en lignes d'écriture puis reconnaît chaque ligne. */
    suspend fun recognize(strokes: List<Stroke>): String {
        val pens = strokes.filter { it.tool == Tool.PEN && it.count > 0 }.sortedBy { it.minY() }
        if (pens.isEmpty()) return ""
        val lines = mutableListOf<MutableList<Stroke>>()
        var top = 0f; var bottom = 0f
        for (s in pens) {
            val a = s.minY(); val b = s.maxY(); val mid = (a + b) / 2
            if (lines.isEmpty() || mid > bottom + 8) { lines.add(mutableListOf(s)); top = a; bottom = b }
            else { lines.last().add(s); top = minOf(top, a); bottom = maxOf(bottom, b) }
        }
        val rec = DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
        val out = StringBuilder()
        try {
            for (line in lines) {
                val ink = Ink.builder()
                line.sortedBy { it.t(0) }.forEach { s ->
                    val sb = Ink.Stroke.builder()
                    for (i in 0 until s.count) sb.addPoint(Ink.Point.create(s.x(i), s.y(i), s.t(i).toLong()))
                    ink.addStroke(sb.build())
                }
                val r = rec.recognize(ink.build()).await()
                r.candidates.firstOrNull()?.text?.let { if (out.isNotEmpty()) out.append('\n'); out.append(it) }
            }
        } finally { rec.close() }
        return out.toString()
    }
}
