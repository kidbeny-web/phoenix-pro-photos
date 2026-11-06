package fr.phoenixpro.photos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.provider.MediaStore
import android.content.ContentUris
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DetectedFace(val x: Float, val y: Float, val w: Float, val h: Float, val score: Float, val embedding: FloatArray, val thumb: Bitmap)

/** Détection YuNet + reconnaissance SFace (mêmes modèles que la version PC), 100 % sur le téléphone. */
class FaceEngine(private val ctx: Context) {
    private val detector: FaceDetectorYN
    private val recognizer: FaceRecognizerSF

    init {
        val det = copyAsset("face_detection_yunet_2023mar.onnx")
        val rec = copyAsset("face_recognition_sface_2021dec.onnx")
        detector = FaceDetectorYN.create(det.absolutePath, "", Size(320.0, 320.0), MIN_SCORE, 0.3f, 5000)
        recognizer = FaceRecognizerSF.create(rec.absolutePath, "")
    }

    private fun copyAsset(name: String): File {
        val out = File(ctx.filesDir, name)
        if (!out.exists() || out.length() < 100_000) {
            ctx.assets.open(name).use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        }
        return out
    }

    fun loadBitmap(photoId: Long, maxSide: Int = DETECT_SIDE): Bitmap {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        val src = ImageDecoder.createSource(ctx.contentResolver, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val scale = min(1f, maxSide.toFloat() / max(w, h))
            decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
        }.copy(Bitmap.Config.ARGB_8888, false)
    }

    fun analyse(bitmap: Bitmap, originalWidth: Int): List<DetectedFace> {
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        val W = bgr.cols()
        val H = bgr.rows()
        val faces = Mat()
        val out = mutableListOf<DetectedFace>()
        synchronized(this) {
            detector.setInputSize(Size(W.toDouble(), H.toDouble()))
            detector.detect(bgr, faces)
            val scale = if (W > 0) max(1f, originalWidth.toFloat() / W) else 1f
            for (r in 0 until faces.rows()) {
                val row = faces.row(r)
                val v = FloatArray(15)
                row.get(0, 0, v)
                val (x, y, w, h) = listOf(v[0], v[1], v[2], v[3])
                val score = v[14]
                if (score < MIN_SCORE || min(w, h) * scale < MIN_FACE_PX || min(w, h) < 24f) continue
                val aligned = Mat()
                recognizer.alignCrop(bgr, row, aligned)
                val feat = Mat()
                recognizer.feature(aligned, feat)
                val e = FloatArray(128)
                feat.get(0, 0, e)
                aligned.release(); feat.release()
                var n = 0f
                for (f in e) n += f * f
                n = sqrt(n)
                if (n == 0f) continue
                for (i in e.indices) e[i] /= n
                // vignette carrée avec marge
                val side = max(w, h) * 1.6f
                val cx = x + w / 2
                val cy = y + h / 2
                val left = max(0f, cx - side / 2).toInt()
                val top = max(0f, cy - side / 2).toInt()
                val right = min(W.toFloat(), cx + side / 2).toInt()
                val bottom = min(H.toFloat(), cy + side / 2).toInt()
                if (right - left < 4 || bottom - top < 4) continue
                val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                val thumb = Bitmap.createScaledBitmap(crop, THUMB, THUMB, true)
                out += DetectedFace(x / W, y / H, w / W, h / H, score, e, thumb)
            }
        }
        faces.release(); bgr.release()
        return out
    }

    companion object {
        const val DETECT_SIDE = 1280
        const val MIN_SCORE = 0.86f
        const val MIN_FACE_PX = 50
        const val THUMB = 200

        fun faceFile(ctx: Context, faceId: Long) = File(File(ctx.filesDir, "visages").apply { mkdirs() }, "$faceId.jpg")
    }
}
