package fr.phoenixpro.retouche

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Phoenix Pro Retouche : l'éditeur (HTML + WebGL) tourne dans une WebView,
 * servi depuis les assets de l'appli. Ce fichier gère le choix des photos,
 * l'enregistrement dans la galerie, le partage et « Modifier avec ».
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val incoming = ConcurrentHashMap<String, Uri>()
    private val saves = ConcurrentHashMap<String, File>()
    private var pageReady = false
    private var pendingJs: String? = null

    companion object {
        private const val PICK = 42
        private const val HOST = "appassets.androidplatform.net"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(0xFF0D0E10.toInt())
        setContentView(web)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host == HOST && u.path?.startsWith("/incoming/") == true) {
                    val src = incoming[u.lastPathSegment ?: ""] ?: return null
                    return try {
                        val ins = contentResolver.openInputStream(src) ?: return null
                        WebResourceResponse(contentResolver.getType(src) ?: "application/octet-stream", null, ins)
                    } catch (e: Exception) {
                        null
                    }
                }
                return loader.shouldInterceptRequest(u)
            }

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                if (req.url.host == HOST) return false
                try { startActivity(Intent(Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pendingJs?.let { view.evaluateJavascript(it, null) }
                pendingJs = null
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/octet-stream"))
                }
                return try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(pick, PICK)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "Android")
        handleIntent(intent)
        web.loadUrl("https://$HOST/assets/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK) {
            val uri = data?.data
            fileCallback?.onReceiveValue(if (resultCode == RESULT_OK && uri != null) arrayOf(uri) else null)
            fileCallback = null
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Photo reçue depuis la galerie (« Partager » / « Modifier avec »). */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uri: Uri = when (i.action) {
            Intent.ACTION_SEND ->
                streamOf(i)
            Intent.ACTION_EDIT, Intent.ACTION_VIEW -> i.data
            else -> null
        } ?: return
        val id = System.currentTimeMillis().toString()
        incoming[id] = uri
        val js = "window.__pp&&window.__pp.openUrl('https://$HOST/incoming/$id'," +
            JSONObject.quote(displayName(uri)) + ")"
        if (pageReady) web.evaluateJavascript(js, null) else pendingJs = js
    }

    @Suppress("DEPRECATION")
    private fun streamOf(i: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {}
        return uri.lastPathSegment ?: "photo.jpg"
    }

    private fun saveToGallery(f: File, filename: String, mime: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Phoenix Pro Retouche")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return "Erreur : enregistrement impossible"
        contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        f.delete()
        return "Enregistrée dans Images › Phoenix Pro Retouche"
    }

    private fun shareFile(f: File, filename: String, mime: String) {
        val dir = File(cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, filename)
        f.renameTo(dest)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", dest)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runOnUiThread { startActivity(Intent.createChooser(send, "Partager la photo")) }
    }

    /** Pont appelé depuis l'éditeur : l'image exportée arrive en morceaux base64. */
    inner class Bridge {
        @JavascriptInterface
        fun begin(name: String): String {
            val id = System.nanoTime().toString()
            val f = File(cacheDir, "export_$id")
            f.delete()
            saves[id] = f
            return id
        }

        @JavascriptInterface
        fun chunk(id: String, b64: String) {
            val f = saves[id] ?: return
            FileOutputStream(f, true).use { it.write(Base64.decode(b64, Base64.DEFAULT)) }
        }

        @JavascriptInterface
        fun finish(id: String, filename: String, mime: String, share: Boolean): String {
            val f = saves.remove(id) ?: return "Erreur d'export"
            return try {
                if (share) {
                    shareFile(f, filename, mime); "ok"
                } else {
                    val msg = saveToGallery(f, filename, mime)
                    runOnUiThread { Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show() }
                    msg
                }
            } catch (e: Exception) {
                "Erreur : ${e.message}"
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Retour : l'appli passe en arrière-plan, la retouche en cours est conservée
        moveTaskToBack(true)
    }
}
