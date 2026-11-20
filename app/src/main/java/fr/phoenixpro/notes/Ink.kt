package fr.phoenixpro.notes

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
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
import androidx.compose.ui.input.pointer.pointerInteropFilter
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

/**
 * Zone d'écriture. Stylet : écrit (bouton latéral maintenu = gomme). Doigt : fait défiler la page,
 * sauf si « écrire au doigt » est activé. La paume posée pendant l'écriture est ignorée.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun InkCanvas(st: InkState, scroll: ScrollState, onChange: () -> Unit) {
    val note = st.note
    var widthPx by remember { mutableFloatStateOf(1f) }
    val k = widthPx / PAGE_W
    var current by remember { mutableStateOf<FloatArray?>(null) }
    var currentTool by remember { mutableStateOf(Tool.PEN) }
    val buf = remember { ArrayList<Float>() }
    var activeId by remember { mutableIntStateOf(-1) }
    val density = LocalDensity.current
    val heightUnits = note.pages * PAGE_H
    @Suppress("UNUSED_VARIABLE") val v = st.version
    // Pages du PDF importé (fond), chargées en qualité écran.
    val pdf by produceState<List<ImageBitmap?>>(emptyList(), note.id, note.pdfPages) {
        value = withContext(Dispatchers.IO) {
            (0 until note.pdfPages).map { i ->
                try { BitmapFactory.decodeFile(Store.pageImage(note.id, i).path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })?.asImageBitmap() } catch (e: Exception) { null }
            }
        }
    }

    fun addPoint(x: Float, y: Float, p: Float) {
        buf.add(x / k); buf.add(y / k); buf.add(p.coerceIn(0.05f, 1f)); buf.add((SystemClock.uptimeMillis() % 10_000_000L).toFloat())
    }

    fun eraseAt(x: Float, y: Float) {
        val ux = x / k; val uy = y / k
        val hit = note.strokes.filter { s -> (0 until s.count).any { hypot(s.x(it) - ux, s.y(it) - uy) < 14f + s.width / 2 } }
        if (hit.isNotEmpty()) { note.strokes.removeAll(hit.toSet()); st.push(Edit.Remove(hit)); onChange() }
    }

    Canvas(
        Modifier.fillMaxWidth().height(with(density) { (heightUnits * k).toDp() })
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInteropFilter { e ->
                val idx = e.actionIndex
                val type = e.getToolType(idx)
                val stylus = type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
                if (stylus) st.stylusSeen = true
                val draws = stylus || (st.fingerDraws && type == MotionEvent.TOOL_TYPE_FINGER)
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        if (!draws || activeId != -1) return@pointerInteropFilter stylus // le doigt laisse défiler
                        activeId = e.getPointerId(idx)
                        val eraserButton = type == MotionEvent.TOOL_TYPE_ERASER || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                        currentTool = if (eraserButton) Tool.ERASER else st.tool
                        buf.clear()
                        if (currentTool == Tool.ERASER) eraseAt(e.getX(idx), e.getY(idx))
                        else { addPoint(e.getX(idx), e.getY(idx), if (stylus) e.getPressure(idx) else 0.5f); current = buf.toFloatArray() }
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (activeId == -1) return@pointerInteropFilter false
                        val pi = e.findPointerIndex(activeId); if (pi < 0) return@pointerInteropFilter true
                        val isStylus = e.getToolType(pi) != MotionEvent.TOOL_TYPE_FINGER
                        if (currentTool == Tool.ERASER) { eraseAt(e.getX(pi), e.getY(pi)); return@pointerInteropFilter true }
                        for (h in 0 until e.historySize) addPoint(e.getHistoricalX(pi, h), e.getHistoricalY(pi, h), if (isStylus) e.getHistoricalPressure(pi, h) else 0.5f)
                        addPoint(e.getX(pi), e.getY(pi), if (isStylus) e.getPressure(pi) else 0.5f)
                        current = buf.toFloatArray()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                        if (activeId == -1 || e.getPointerId(idx) != activeId && e.actionMasked != MotionEvent.ACTION_CANCEL) return@pointerInteropFilter activeId != -1
                        val pts = current
                        if (currentTool != Tool.ERASER && pts != null && pts.isNotEmpty() && e.actionMasked != MotionEvent.ACTION_CANCEL) {
                            val s = Stroke(currentTool, if (currentTool == Tool.HIGHLIGHT) st.color else st.color, if (currentTool == Tool.HIGHLIGHT) st.width * 5f else st.width, pts)
                            note.strokes.add(s); st.push(Edit.Add(s))
                            // Ajoute une page quand on écrit près du bas.
                            if (s.maxY() > heightUnits - 200f) note.pages++
                            onChange()
                        }
                        current = null; activeId = -1; buf.clear()
                        true
                    }
                    else -> false
                }
            },
    ) {
        if (st.version < 0) return@Canvas // relit la version : redessine après annuler / gomme
        val pageH = (PAGE_H * k).toInt()
        pdf.forEachIndexed { i, img ->
            if (img != null) drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset(0, i * pageH), dstSize = IntSize(size.width.toInt(), pageH))
        }
        if (note.pdfPages == 0) drawBackground(note.bg, k, heightUnits)
        else for (p in 1 until note.pages) drawLine(Color(0x55F08A1C), Offset(0f, p * PAGE_H * k), Offset(size.width, p * PAGE_H * k), 2f)
        drawAll(note, k)
        current?.let { pts -> if (pts.isNotEmpty()) drawStroke(Stroke(currentTool, st.color, if (currentTool == Tool.HIGHLIGHT) st.width * 5f else st.width, pts), k) }
    }
}
