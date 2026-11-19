package fr.phoenixpro.sonometre

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val FR = Locale.FRANCE
fun fmtDate(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", FR).format(Date(ms))
fun fmtTime(ms: Long): String = SimpleDateFormat("HH:mm:ss", FR).format(Date(ms))
fun fmtDb(v: Double): String = String.format(FR, "%.1f", v)
fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) String.format(FR, "%d h %02d min", h, m) else if (m > 0) String.format(FR, "%d min %02d s", m, sec) else "$sec s"
}

/** Jour = 7 h à 22 h (repère réglementaire pour l'émergence). */
fun isDay(ms: Long): Boolean {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val h = c.get(Calendar.HOUR_OF_DAY)
    return h in 7..21
}

/** Émergence = niveau de l'événement moins le bruit de fond (L90) de la séance. */
fun emergence(s: Session, e: EventRec): Double = e.leq - s.lp(90)

object Report {
    private const val W = 595
    private const val H = 842
    private const val M = 40f

    fun build(ctx: Context, s: Session): File {
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
        val red = Paint(bold).apply { color = Color.rgb(200, 40, 40) }
        val line = Paint().apply { color = Color.rgb(205, 208, 212); strokeWidth = 0.7f }
        val orange = Color.rgb(240, 138, 28)

        fun newPage() {
            if (pageNo > 0) {
                c.drawText("Phoenix Pro Sonomètre · page $pageNo", M, H - 22f, small)
                doc.finishPage(page)
            }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            c = page.canvas
            y = M + 10
        }

        fun ensure(space: Float) { if (y + space > H - 50) newPage() }

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
            for (l in wrap(text, p, W - 2 * M)) { ensure(p.textSize + 3); c.drawText(l, M, y + p.textSize, p); y += p.textSize + 3 }
            y += gap
        }

        fun row(label: String, value: String) {
            ensure(16f)
            c.drawText(label, M, y + 10, small)
            c.drawText(value, M + 170, y + 10, body)
            y += 15
        }

        newPage()
        // En-tête
        val head = Paint().apply { color = orange }
        c.drawRect(M, y, M + 6, y + 26, head)
        c.drawText("Rapport de mesure sonore", M + 14, y + 20, title)
        y += 36
        para("Établi le ${fmtDate(System.currentTimeMillis())} avec Phoenix Pro Sonomètre.", small, 8f)

        c.drawText("Contexte", M, y + 13, h2); y += 22
        row("Lieu de la mesure", s.place.ifBlank { "Non renseigné" })
        row("Nature de la nuisance", s.nuisance.ifBlank { "Non renseignée" })
        row("Début", fmtDate(s.start))
        row("Fin", fmtDate(s.end))
        row("Durée", fmtDuration(s.durationMs))
        row("Appareil", s.device)
        row("Capteur", s.source)
        row("Calibrage appliqué", "${fmtDb(s.calibration)} dB")
        row("Seuil de nuisance", "${s.threshold} dB(A), au moins ${s.minDuration} s")
        y += 8

        c.drawText("Résultats", M, y + 13, h2); y += 22
        val l90 = s.lp(90)
        row("Niveau moyen LAeq", "${fmtDb(s.leq())} dB(A)")
        row("Bruit de fond L90", "${fmtDb(l90)} dB(A)")
        row("Bruit fort L10", "${fmtDb(s.lp(10))} dB(A)")
        row("Maximum (LAF max)", "${fmtDb(s.lmax)} dB(A)")
        row("Minimum (1 s)", "${fmtDb(s.lmin())} dB(A)")
        row("Événements", "${s.events.size}")
        row("Temps au-dessus du seuil", fmtDuration(s.secondsAbove() * 1000L))
        y += 10

        // Courbe
        ensure(190f)
        c.drawText("Niveau seconde par seconde", M, y + 13, h2); y += 20
        val gx = M + 28; val gw = W - M - gx; val gh = 150f; val gy = y
        val lo = 20f; val hi = max(100f, (s.levels.maxOrNull() ?: 90f) + 5)
        fun yOf(v: Float) = gy + gh - (min(max(v, lo), hi) - lo) / (hi - lo) * gh
        var t = 30
        while (t <= hi) {
            val yy = yOf(t.toFloat())
            c.drawLine(gx, yy, gx + gw, yy, line)
            c.drawText("$t", M, yy + 3, small)
            t += 20
        }
        val thr = Paint().apply { color = Color.rgb(200, 40, 40); strokeWidth = 1f; pathEffect = DashPathEffect(floatArrayOf(4f, 3f), 0f) }
        c.drawLine(gx, yOf(s.threshold.toFloat()), gx + gw, yOf(s.threshold.toFloat()), thr)
        if (s.levels.size > 1) {
            val pts = s.levels.size
            val step = max(1, pts / gw.toInt())
            val path = Path()
            var i = 0; var first = true
            while (i < pts) {
                var m = 0f
                for (j in i until min(pts, i + step)) m = max(m, s.levels[j])
                val px = gx + i.toFloat() / (pts - 1) * gw
                if (first) { path.moveTo(px, yOf(m)); first = false } else path.lineTo(px, yOf(m))
                i += step
            }
            c.drawPath(path, Paint().apply { color = orange; style = Paint.Style.STROKE; strokeWidth = 1.2f; isAntiAlias = true })
        }
        y = gy + gh + 4
        c.drawText(fmtTime(s.start), gx, y + 9, small)
        val endTxt = fmtTime(s.end)
        c.drawText(endTxt, gx + gw - small.measureText(endTxt), y + 9, small)
        c.drawText("pointillés : seuil ${s.threshold} dB(A)", gx + gw / 2 - 50, y + 9, small)
        y += 22

        // Événements
        newPage()
        c.drawText("Détail des événements", M, y + 13, h2); y += 22
        if (s.events.isEmpty()) para("Aucun dépassement du seuil pendant la mesure.")
        val cols = floatArrayOf(M, M + 22, M + 140, M + 200, M + 262, M + 324, M + 400)
        val heads = listOf("#", "Date et heure", "Durée", "LAeq", "Max", "Émergence", "Extrait audio (SHA-256)")
        fun header() {
            heads.forEachIndexed { i, h -> c.drawText(h, cols[i], y + 9, small) }
            y += 13; c.drawLine(M, y, W - M, y, line); y += 3
        }
        if (s.events.isNotEmpty()) header()
        s.events.forEachIndexed { i, e ->
            if (y + 28 > H - 50) { newPage(); header() }
            val em = emergence(s, e)
            val limit = if (isDay(e.start)) 5.0 else 3.0
            c.drawText("${i + 1}", cols[0], y + 10, body)
            c.drawText(fmtDate(e.start), cols[1], y + 10, body)
            c.drawText(fmtDuration(max(1000L, e.durationMs)), cols[2], y + 10, body)
            c.drawText(fmtDb(e.leq), cols[3], y + 10, body)
            c.drawText(fmtDb(e.lmax), cols[4], y + 10, body)
            c.drawText("+${fmtDb(em)} dB", cols[5], y + 10, if (em > limit) red else body)
            c.drawText(e.clip ?: "aucun", cols[6], y + 10, body)
            c.drawText(if (isDay(e.start)) "jour" else "nuit", cols[5], y + 21, small)
            c.drawText(e.sha?.take(24) ?: "", cols[6], y + 21, small)
            y += 26
            c.drawLine(M, y - 2, W - M, y - 2, line)
        }
        y += 12

        ensure(150f)
        c.drawText("Méthode et limites", M, y + 13, h2); y += 22
        para(
            "Mesure faite avec le micro du téléphone, pondération fréquentielle A et constante de temps rapide (125 ms). " +
                "Un niveau est enregistré chaque seconde. Un événement commence quand le niveau dépasse le seuil pendant au moins " +
                "${s.minDuration} s ; il prend fin après 2 s sous le seuil. L'émergence est la différence entre le niveau de l'événement " +
                "et le bruit de fond L90 de la séance."
        )
        para(
            "Repère : pour les bruits de voisinage, le Code de la santé publique (art. R1336-7) fixe une émergence maximale de " +
                "5 dB(A) le jour (7 h - 22 h) et 3 dB(A) la nuit, augmentée d'un terme correctif selon la durée du bruit. " +
                "Les émergences en rouge dépassent ce repère avant correction."
        )
        para(
            "Un micro de téléphone n'est pas un sonomètre homologué : la précision dépend du modèle et du calibrage " +
                "(de l'ordre de ±3 dB une fois calibré). Ce rapport est un relevé personnel qui peut appuyer une démarche amiable " +
                "(voisin, syndic, mairie, conciliateur de justice). Il ne remplace pas un constat de commissaire de justice ni une mesure officielle."
        )
        para(
            "Chaque extrait audio est accompagné de son empreinte SHA-256 : si le fichier est modifié, son empreinte change. " +
                "Les fichiers sont joints à ce rapport lorsqu'il est partagé avec les extraits."
        )
        y += 16
        ensure(40f)
        c.drawText("Fait à ______________________   le ____ / ____ / ________", M, y + 10, body)
        y += 26
        c.drawText("Signature :", M, y + 10, body)

        c.drawText("Phoenix Pro Sonomètre · page $pageNo", M, H - 22f, small)
        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "reports").apply { mkdirs() }
        val name = "rapport-bruit-" + SimpleDateFormat("yyyy-MM-dd-HH'h'mm", FR).format(Date(s.start)) + ".pdf"
        val f = File(dir, name)
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }
}
