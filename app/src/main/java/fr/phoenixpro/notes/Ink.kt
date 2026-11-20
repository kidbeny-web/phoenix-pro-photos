package fr.phoenixpro.notes

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import android.os.Build
import android.view.InputDevice
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.hypot

/** Actions pour annuler / rétablir. */
sealed class Edit {
    class Add(val s: Stroke) : Edit()
    class Remove(val list: List<Stroke>) : Edit()
}

class InkState(val note: Note) {
    var tool by mutableStateOf(Tool.PEN)
    var color by mutableIntStateOf(0xFFF1F3F4.toInt())
    var width by mutableFloatStateOf(4f)
    var fingerDraws by mutableStateOf(false)
    var stylusSeen by mutableStateOf(false)
    var drawing by mutableStateOf(false)
    var version by mutableIntStateOf(0)
    val undo = ArrayDeque<Edit>()
    val redo = ArrayDeque<Edit>()

    fun push(e: Edit) { undo.addLast(e); redo.clear(); version++ }
    fun doUndo() {
        val e = undo.removeLastOrNull() ?: return
        when (e) { is Edit.Add -> note.strokes.remove(e.s); is Edit.Remove -> note.strokes.addAll(e.list) }
        redo.addLast(e); version++
    }
    fun doRedo() {
        val e = redo.removeLastOrNull() ?: return
        when (e) { is Edit.Add -> note.strokes.add(e.s); is Edit.Remove -> note.strokes.removeAll(e.list.toSet()) }
        undo.addLast(e); version++
    }
}

private fun DrawScope.drawStroke(s: Stroke, k: Float, dy: Float = 0f) {
    if (s.count == 0) return
    val c = Color(s.color)
    if (s.tool == Tool.HIGHLIGHT) {
        val p = Path(); p.moveTo(s.x(0) * k, s.y(0) * k + dy)
        for (i in 1 until s.count) p.lineTo(s.x(i) * k, s.y(i) * k + dy)
        drawPath(p, c.copy(alpha = 0.35f), style = DrawStroke(s.width * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
        return
    }
    if (s.count == 1) { drawCircle(c, s.width * k * 0.6f, Offset(s.x(0) * k, s.y(0) * k + dy)); return }
    for (i in 1 until s.count) {
        // Épaisseur selon la pression du stylet (le doigt donne une pression moyenne).
        val w = s.width * k * (0.35f + 0.9f * ((s.p(i - 1) + s.p(i)) / 2f))
        drawLine(c, Offset(s.x(i - 1) * k, s.y(i - 1) * k + dy), Offset(s.x(i) * k, s.y(i) * k + dy), w, cap = StrokeCap.Round)
    }
}

fun DrawScope.drawBackground(bg: String, k: Float, heightUnits: Float) {
    val line = Color(0x22FFFFFF)
    when (bg) {
        "lignes" -> { var y = 120f; while (y < heightUnits) { drawLine(line, Offset(0f, y * k), Offset(size.width, y * k), 1.5f); y += 48f } }
        "carreaux" -> {
            var y = 0f; while (y < heightUnits) { drawLine(line, Offset(0f, y * k), Offset(size.width, y * k), 1f); y += 40f }
            var x = 0f; while (x < PAGE_W) { drawLine(line, Offset(x * k, 0f), Offset(x * k, heightUnits * k), 1f); x += 40f }
        }
        "points" -> { var y = 40f; while (y < heightUnits) { var x = 40f; while (x < PAGE_W) { drawCircle(Color(0x44FFFFFF), 2f, Offset(x * k, y * k)); x += 40f }; y += 40f } }
    }
    // Séparation des pages
    for (p in 1 until (heightUnits / PAGE_H).toInt()) drawLine(Color(0x55F08A1C), Offset(0f, p * PAGE_H * k), Offset(size.width, p * PAGE_H * k), 2f)
}

fun DrawScope.drawAll(note: Note, k: Float, dy: Float = 0f) { note.strokes.forEach { drawStroke(it, k, dy) } }

/** Trait en cours : tableau qui grandit sans recopie à chaque point. */
class LiveStroke {
    var pts = FloatArray(4096); var n = 0
    var tick by mutableIntStateOf(0)
    fun add(x: Float, y: Float, p: Float, t: Float) {
        if (n + 4 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[n++] = x; pts[n++] = y; pts[n++] = p; pts[n++] = t
    }
    fun clear() { n = 0; tick++ }
    fun snapshot() = pts.copyOf(n)
}

private fun DrawScope.drawLive(l: LiveStroke, tool: Tool, color: Int, width: Float, k: Float) {
    val count = l.n / 4; if (count == 0) return
    val c = Color(color); val a = l.pts
    if (tool == Tool.HIGHLIGHT) {
        val path = Path(); path.moveTo(a[0] * k, a[1] * k)
        for (i in 1 until count) path.lineTo(a[i * 4] * k, a[i * 4 + 1] * k)
        drawPath(path, c.copy(alpha = 0.35f), style = DrawStroke(width * 5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)); return
    }
    if (count == 1) { drawCircle(c, width * k * 0.6f, Offset(a[0] * k, a[1] * k)); return }
    for (i in 1 until count) {
        val w = width * k * (0.35f + 0.9f * ((a[i * 4 - 2] + a[i * 4 + 2]) / 2f))
        drawLine(c, Offset(a[i * 4 - 4] * k, a[i * 4 - 3] * k), Offset(a[i * 4] * k, a[i * 4 + 1] * k), w, cap = StrokeCap.Round)
    }
}

/**
 * Zone d'écriture. Stylet : écrit (bouton latéral maintenu = gomme). Doigt : fait défiler la page,
 * sauf si « écrire au doigt » est activé. Pendant l'écriture, la paume et les autres doigts sont ignorés.
 *
 * Deux calques : le fond et les traits terminés (redessinés seulement quand la note change),
 * et le trait en cours (redessiné à chaque point) — l'écriture reste fluide même sur une note chargée.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun InkCanvas(st: InkState, scroll: ScrollState, onChange: () -> Unit) {
    val note = st.note
    var widthPx by remember { mutableFloatStateOf(1f) }
    val k = widthPx / PAGE_W
    val live = remember { LiveStroke() }
    var liveTool by remember { mutableStateOf(Tool.PEN) }
    val density = LocalDensity.current
    val view = LocalView.current
    val heightUnits = note.pages * PAGE_H
    // Points du S Pen livrés sans attendre l'image suivante : moins de retard sous la pointe.
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= 30) view.requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
        onDispose { }
    }
    val pdf by produceState<List<ImageBitmap?>>(emptyList(), note.id, note.pdfPages) {
        value = withContext(Dispatchers.IO) {
            (0 until note.pdfPages).map { i ->
                try { BitmapFactory.decodeFile(Store.pageImage(note.id, i).path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })?.asImageBitmap() } catch (e: Exception) { null }
            }
        }
    }
    val kNow by rememberUpdatedState(k)
    val onChangeNow by rememberUpdatedState(onChange)

    fun eraseAt(x: Float, y: Float) {
        val ux = x / kNow; val uy = y / kNow
        val hit = note.strokes.filter { s -> s.minY() - 30f < uy && s.maxY() + 30f > uy && (0 until s.count).any { hypot(s.x(it) - ux, s.y(it) - uy) < 14f + s.width / 2 } }
        if (hit.isNotEmpty()) { note.strokes.removeAll(hit.toSet()); st.push(Edit.Remove(hit)); onChangeNow() }
    }
    fun now() = (SystemClock.uptimeMillis() % 10_000_000L).toFloat()

    Box(
        Modifier.fillMaxWidth().height(with(density) { (heightUnits * k).toDp() })
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(note.id) {
                awaitPointerEventScope {
                    var active: PointerId? = null
                    var lastP = 0.5f
                    while (true) {
                        val ev = awaitPointerEvent()
                        for (c in ev.changes) {
                            val stylus = c.type == PointerType.Stylus || c.type == PointerType.Eraser
                            if (stylus && !st.stylusSeen) st.stylusSeen = true
                            val draws = stylus || (st.fingerDraws && c.type == PointerType.Touch)
                            val kk = kNow
                            when {
                                active == null && c.changedToDownIgnoreConsumed() && draws -> {
                                    active = c.id
                                    st.drawing = true
                                    liveTool = if (c.type == PointerType.Eraser || ev.buttons.isSecondaryPressed) Tool.ERASER else st.tool
                                    lastP = if (stylus) c.pressure.coerceIn(0.05f, 1f) else 0.5f
                                    live.n = 0
                                    if (liveTool == Tool.ERASER) eraseAt(c.position.x, c.position.y)
                                    else live.add(c.position.x / kk, c.position.y / kk, lastP, now())
                                    live.tick++
                                    c.consume()
                                }
                                c.id == active -> {
                                    if (c.pressed) {
                                        if (liveTool == Tool.ERASER) eraseAt(c.position.x, c.position.y)
                                        else {
                                            val p = if (stylus) c.pressure.coerceIn(0.05f, 1f) else 0.5f
                                            val hist = c.historical
                                            hist.forEachIndexed { h, hc ->
                                                val f = (h + 1f) / (hist.size + 1f)
                                                live.add(hc.position.x / kk, hc.position.y / kk, lastP + (p - lastP) * f, now())
                                            }
                                            live.add(c.position.x / kk, c.position.y / kk, p, now())
                                            lastP = p
                                            live.tick++
                                        }
                                    } else {
                                        if (liveTool != Tool.ERASER && live.n > 0) {
                                            val s = Stroke(liveTool, st.color, if (liveTool == Tool.HIGHLIGHT) st.width * 5f else st.width, live.snapshot())
                                            note.strokes.add(s); st.push(Edit.Add(s))
                                            if (s.maxY() > note.pages * PAGE_H - 200f) note.pages++
                                            onChangeNow()
                                        }
                                        live.clear(); active = null; st.drawing = false
                                    }
                                    c.consume()
                                }
                                active != null -> c.consume() // paume posée : ni trait ni défilement
                            }
                        }
                    }
                }
            },
    ) {
        // Calque 1 : fond + traits terminés
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            if (st.version < 0) return@Canvas // relit la version : redessine après un trait, annuler, gomme
            val pageH = (PAGE_H * k).toInt()
            pdf.forEachIndexed { i, img ->
                if (img != null) drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                    dstOffset = IntOffset(0, i * pageH), dstSize = IntSize(size.width.toInt(), pageH))
            }
            if (note.pdfPages == 0) drawBackground(note.bg, k, heightUnits)
            else for (p in 1 until note.pages) drawLine(Color(0x55F08A1C), Offset(0f, p * PAGE_H * k), Offset(size.width, p * PAGE_H * k), 2f)
            drawAll(note, k)
        }
        // Calque 2 : trait en cours
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            if (live.tick < 0) return@Canvas
            if (liveTool != Tool.ERASER) drawLive(live, liveTool, st.color, st.width, k)
        }
    }
}
