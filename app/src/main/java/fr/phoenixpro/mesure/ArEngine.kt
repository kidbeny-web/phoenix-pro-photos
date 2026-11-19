package fr.phoenixpro.mesure

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import com.google.ar.core.Anchor
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

/** Ce que l'écran doit dessiner par-dessus l'image de la caméra. */
data class ArView(
    val tracking: Boolean = false,
    val hint: String = "Démarrage de la caméra…",
    val reticleOnSurface: Boolean = false,
    val points: List<Offset?> = emptyList(),
    val segments: List<Float> = emptyList(), // en mètres, un par paire de points
    val live: Float? = null,                  // du dernier point posé jusqu'au viseur
    val count: Int = 0,
)

enum class Action { ADD, UNDO, CLEAR }

private fun dist(a: Pose, b: Pose): Float {
    val dx = a.tx() - b.tx(); val dy = a.ty() - b.ty(); val dz = a.tz() - b.tz()
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** Rendu de l'image caméra ARCore + calcul des mesures (thread OpenGL). */
class ArRenderer(private val publish: (ArView) -> Unit) : GLSurfaceView.Renderer {
    @Volatile var session: Session? = null
    @Volatile var rotation = 0
    private val actions = ConcurrentLinkedQueue<Action>()
    private val anchors = mutableListOf<Anchor>()

    private var tex = 0
    private var prog = 0
    private var aPos = 0
    private var aTex = 0
    private var uTex = 0
    private var w = 1
    private var h = 1
    private var geometryChanged = true
    private var lastHit: HitResult? = null

    private val quad: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTex: FloatBuffer = floatBuffer(FloatArray(8))
    private var texReady = false

    fun post(a: Action) { actions.add(a) }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        tex = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        prog = program(
            """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() { gl_Position = a_Position; v_TexCoord = a_TexCoord; }
            """.trimIndent(),
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() { gl_FragColor = texture2D(sTexture, v_TexCoord); }
            """.trimIndent(),
        )
        aPos = GLES20.glGetAttribLocation(prog, "a_Position")
        aTex = GLES20.glGetAttribLocation(prog, "a_TexCoord")
        uTex = GLES20.glGetUniformLocation(prog, "sTexture")
        geometryChanged = true
        texReady = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        w = width; h = height
        geometryChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (geometryChanged) { s.setDisplayGeometry(rotation, w, h); geometryChanged = false; texReady = false }
        s.setCameraTextureName(tex)
        val frame = try { s.update() } catch (e: Exception) { return }

        if (frame.hasDisplayGeometryChanged() || !texReady) {
            quad.position(0); quadTex.position(0)
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quad, Coordinates2d.TEXTURE_NORMALIZED, quadTex)
            texReady = true
        }
        if (frame.timestamp != 0L) drawBackground()

        val cam = frame.camera

        // Actions demandées par l'écran (à faire sur ce thread)
        while (true) {
            when (actions.poll() ?: break) {
                Action.ADD -> lastHit?.let { if (anchors.size < 60 && cam.trackingState == TrackingState.TRACKING) anchors.add(it.createAnchor()) }
                Action.UNDO -> anchors.removeLastOrNull()?.detach()
                Action.CLEAR -> { anchors.forEach { it.detach() }; anchors.clear() }
            }
        }

        if (cam.trackingState != TrackingState.TRACKING) {
            lastHit = null
            publish(ArView(tracking = false, hint = failureHint(cam.trackingFailureReason), count = anchors.size))
            return
        }

        val proj = FloatArray(16); val view = FloatArray(16); val vp = FloatArray(16)
        cam.getProjectionMatrix(proj, 0, 0.05f, 100f)
        cam.getViewMatrix(view, 0)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        val hit = frame.hitTest(w / 2f, h / 2f).firstOrNull { hr ->
            when (val t = hr.trackable) {
                is Plane -> t.isPoseInPolygon(hr.hitPose) && t.trackingState == TrackingState.TRACKING
                is DepthPoint -> true
                is Point -> t.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL
                else -> false
            }
        }
        lastHit = hit

        fun screen(p: Pose): Offset? {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, vp, 0, floatArrayOf(p.tx(), p.ty(), p.tz(), 1f), 0)
            if (out[3] <= 0f) return null
            val x = (out[0] / out[3] * 0.5f + 0.5f) * w
            val y = (1f - (out[1] / out[3] * 0.5f + 0.5f)) * h
            return Offset(x, y)
        }

        val pts = anchors.map { a -> if (a.trackingState == TrackingState.STOPPED) null else screen(a.pose) }
        val segs = (0 until anchors.size / 2).map { i -> dist(anchors[2 * i].pose, anchors[2 * i + 1].pose) }
        val live = if (anchors.size % 2 == 1 && hit != null) dist(anchors.last().pose, hit.hitPose) else null
        val hint = when {
            hit == null -> "Visez une surface avec du relief ou des motifs, et bougez lentement"
            anchors.size % 2 == 0 -> "Visez le début de la mesure et touchez +"
            else -> "Visez la fin de la mesure et touchez +"
        }
        publish(ArView(true, hint, hit != null, pts, segs, live, anchors.size))
    }

    private fun failureHint(r: TrackingFailureReason): String = when (r) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Pas assez de lumière : allumez la pièce"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Bougez plus lentement"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Visez une zone avec des détails (meubles, motifs, objets)"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Caméra indisponible"
        else -> "Bougez doucement le téléphone pour que l'appli reconnaisse la pièce"
    }

    private fun drawBackground() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(uTex, 0)
        quad.position(0); quadTex.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, quadTex)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
        GLES20.glDepthMask(true)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    companion object {
        fun floatBuffer(a: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
    }
}
