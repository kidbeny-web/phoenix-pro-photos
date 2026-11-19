package fr.phoenixpro.testeur

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val FRL = Locale.FRANCE
fun fmtDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy à HH:mm", FRL).format(Date(ms))

object Report {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    fun build(ctx: Context): File {
        val doc = PdfDocument()
        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var c: Canvas
        var y = 0f

        val title = Paint().apply { color = Color.rgb(20, 22, 25); textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val h2 = Paint().apply { color = Color.rgb(20, 22, 25); textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        val body = Paint().apply { color = Color.rgb(30, 32, 36); textSize = 10f; isAntiAlias = true }
        val small = Paint().apply { color = Color.rgb(90, 95, 102); textSize = 8.5f; isAntiAlias = true }
        val bold = Paint(body).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val line = Paint().apply { color = Color.rgb(205, 208, 212); strokeWidth = 0.7f }
        val okP = Paint(bold).apply { color = Color.rgb(20, 130, 90) }
        val koP = Paint(bold).apply { color = Color.rgb(200, 40, 40) }
        val naP = Paint(bold).apply { color = Color.rgb(120, 125, 130) }
        val orange = Color.rgb(240, 138, 28)

        fun newPage() {
            if (pageNo > 0) {
                c.drawText("Phoenix Pro Testeur · page $pageNo", M, H - 22f, small)
                doc.finishPage(page)
            }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            y = M + 10
        }

        fun wrap(text: String, p: Paint, width: Float): List<String> {
            val out = mutableListOf<String>()
            for (para in text.split("\n")) {
                var cur = ""
                for (w in para.split(" ")) {
                    val t = if (cur.isEmpty()) w else "$cur $w"
                    if (p.measureText(t) > width && cur.isNotEmpty()) { out.add(cur); cur = w } else cur = t
                }
                out.add(cur)
            }
            return out
        }

        fun para(text: String, p: Paint = body, gap: Float = 4f) {
            for (l in wrap(text, p, W - 2 * M)) {
                if (y + 14 > H - 50) newPage()
                c.drawText(l, M, y + p.textSize, p); y += p.textSize + 3
            }
            y += gap
        }

        fun row(label: String, value: String) {
            c.drawText(label, M, y + 10, small)
            val lines = wrap(value, body, W - 2 * M - 150)
            lines.forEachIndexed { i, l -> c.drawText(l, M + 150, y + 10 + i * 13, body) }
            y += 15 + (lines.size - 1) * 13
        }

        newPage()
        c.drawRect(M, y, M + 6, y + 26, Paint().apply { color = orange })
        c.drawText("Rapport de test du téléphone", M + 14, y + 20, title)
        y += 36
        para("Établi le ${fmtDate(System.currentTimeMillis())} avec Phoenix Pro Testeur, sur le téléphone testé lui-même.", small, 8f)

        val results = Store.results
        val ok = TESTS.count { results[it.id]?.status == Status.OK }
        val ko = TESTS.count { results[it.id]?.status == Status.KO }
        val na = TESTS.count { results[it.id]?.status == Status.NA }
        val todo = TESTS.size - ok - ko - na

        c.drawText("Téléphone", M, y + 13, h2); y += 22
        row("Modèle", Store.model.ifBlank { deviceName() })
        row("Modèle détecté", deviceName())
        if (Store.imei.isNotBlank()) row("IMEI / n° de série", Store.imei)
        if (Store.note.isNotBlank()) row("État visible", Store.note)
        results["info"]?.let { row("Caractéristiques", it.detail) }
        results["batterie"]?.let { row("Batterie", it.detail) }
        y += 8

        // Synthèse
        val box = Paint().apply { color = if (ko == 0) Color.rgb(230, 246, 239) else Color.rgb(253, 236, 236) }
        c.drawRect(M, y, W - M, y + 44, box)
        c.drawText(
            if (ko == 0) "$ok test(s) réussi(s), aucun défaut trouvé" else "$ok test(s) réussi(s), $ko défaut(s) trouvé(s)",
            M + 12, y + 19, Paint(h2).apply { color = if (ko == 0) Color.rgb(20, 110, 78) else Color.rgb(180, 30, 30) },
        )
        c.drawText("$na non disponible(s) sur ce modèle · $todo non fait(s)", M + 12, y + 35, small)
        y += 58

        c.drawText("Détail des tests", M, y + 13, h2); y += 22
        var group = ""
        for (t in TESTS) {
            val r = results[t.id]
            val lines = wrap(r?.detail ?: "Test non fait", body, W - 2 * M - 210)
            val need = 16f + (lines.size - 1) * 12 + if (t.group != group) 18 else 0
            if (y + need > H - 50) { newPage(); group = "" }
            if (t.group != group) {
                group = t.group
                c.drawText(group.uppercase(FRL), M, y + 12, small); y += 18
            }
            c.drawText(t.title, M, y + 10, body)
            val (label, paint) = when (r?.status) {
                Status.OK -> "OK" to okP; Status.KO -> "DÉFAUT" to koP; Status.NA -> "N/D" to naP; null -> "—" to naP
            }
            c.drawText(label, M + 150, y + 10, paint)
            lines.forEachIndexed { i, l -> c.drawText(l, M + 210, y + 10 + i * 12, body) }
            y += 16 + (lines.size - 1) * 12
            c.drawLine(M, y - 3, W - M, y - 3, line)
        }
        y += 14
        if (y + 120 > H - 50) newPage()
        c.drawText("À savoir", M, y + 13, h2); y += 22
        para(
            "Les tests automatiques lisent les informations données par le système du téléphone. Les tests guidés " +
                "(écran, son, caméras…) reposent sur ce que la personne qui fait le test a vu, entendu ou ressenti ; " +
                "pour le son, le vibreur et le flash, elle doit compter un nombre tiré au hasard, ce qui évite les réponses au hasard."
        )
        para(
            "La santé de la batterie est une estimation calculée à partir de la charge restante et de la capacité d'origine " +
                "déclarée par le fabricant ; elle n'est pas disponible sur tous les modèles. N/D = fonction absente de ce modèle ou non testable."
        )
        para("L'IMEI s'obtient en composant *#06# ; vérifiez qu'il correspond à celui inscrit sur la boîte ou la facture.")
        y += 12
        if (y + 40 > H - 50) newPage()
        c.drawText("Vendeur : ______________________      Acheteur : ______________________", M, y + 10, body)

        c.drawText("Phoenix Pro Testeur · page $pageNo", M, H - 22f, small)
        doc.finishPage(page)
        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val safe = (Store.model.ifBlank { deviceName() }).replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-').lowercase()
        val f = File(dir, "rapport-test-$safe.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
