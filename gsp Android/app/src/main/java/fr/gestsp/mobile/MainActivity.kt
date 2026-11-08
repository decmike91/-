package fr.gestsp.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GestSP Mobile.
 *
 * 1. Affiche la vraie page de connexion du site SharePoint (aucun mot de passe n'est stocké par l'application :
 *    seule la session SharePoint, sous forme de cookie, est conservée par le WebView).
 * 2. Une fois connecté, charge l'interface mobile (assets/app.html) avec l'adresse du site comme origine :
 *    l'interface appelle l'API REST de SharePoint avec la même session, comme une page du site.
 * 3. Le pont « GestSPNative » donne à l'interface : ouverture des documents (téléchargement), partage de liens,
 *    reconnexion, déconnexion et changement de site.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var prefs: SharedPreferences
    private var appLoaded = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var photoUri: Uri? = null
    private val downloads = mutableMapOf<Long, String>()

    private val site: String
        get() = (prefs.getString(KEY_SITE, DEFAULT_SITE) ?: DEFAULT_SITE).trimEnd('/')

    private val host: String
        get() = Uri.parse(site).let { "${it.scheme}://${it.host}" + if (it.port > 0) ":${it.port}" else "" }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("gestsp", Context.MODE_PRIVATE)
        if (0 != (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        web = WebView(this)
        setContentView(web)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString GestSPMobile/${BuildInfo.VERSION}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "GestSPNative")
        web.webViewClient = Client()
        web.webChromeClient = Chrome()

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadDone, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(downloadDone, filter)
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        }
        if (prefs.getString(KEY_SITE, null) == null) askSite(first = true) else start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadDone)
        } catch (_: Exception) {
        }
        web.destroy()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    // ------------------------------------------------------------------ session
    private fun start() {
        if (hasSession()) showApp() else showLogin()
    }

    private fun hasSession(): Boolean {
        val c = CookieManager.getInstance().getCookie(site) ?: return false
        return c.contains("FedAuth=") || c.contains(".ASPXAUTH=") || c.contains("rtFa=")
    }

    private fun showLogin() {
        appLoaded = false
        val path = Uri.parse(site).path?.ifEmpty { "/" } ?: "/"
        val source = URLEncoder.encode(path.trimEnd('/') + "/", "UTF-8")
        web.loadUrl("$site/_layouts/15/Authenticate.aspx?Source=$source")
    }

    private fun showApp() {
        val html = assets.open("app.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            .replace("%%SITE%%", site)
        appLoaded = true
        web.loadDataWithBaseURL("$site/", html, "text/html", "utf-8", null)
        web.postDelayed({ web.clearHistory() }, 500)
    }

    private fun isLoginPage(url: String): Boolean {
        val u = url.lowercase(Locale.ROOT)
        return u.contains("signin") || u.contains("authenticate.aspx") || u.contains("/_login/") ||
            u.contains("/_forms/") || u.contains("login")
    }

    private fun logout(clear: Boolean) {
        if (clear) {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            web.clearCache(true)
        }
        showLogin()
    }

    private fun askSite(first: Boolean) {
        val input = EditText(this).apply {
            setText(prefs.getString(KEY_SITE, DEFAULT_SITE))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(if (first) "Bienvenue dans GestSP" else "Adresse du site")
            .setMessage("Adresse du site SharePoint :")
            .setView(input)
            .setCancelable(!first)
            .setPositiveButton("Continuer") { _, _ -> setSite(input.text.toString()) }
            .show()
    }

    private fun setSite(raw: String) {
        var u = raw.trim().trimEnd('/')
        if (u.isEmpty()) u = DEFAULT_SITE
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        // on ne garde que l'adresse du site si une page complète a été collée
        u = u.split(Regex("/(_layouts|SitePages|Pages|_api|Forms)/", RegexOption.IGNORE_CASE))[0]
        prefs.edit().putString(KEY_SITE, u).apply()
        logout(clear = false)
    }

    // ------------------------------------------------------------------ retour
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (appLoaded) {
            web.evaluateJavascript("(window.GestSP && GestSP.back()) ? 'oui' : 'non'") { r ->
                if (r?.contains("oui") != true) finish()
            }
        } else if (web.canGoBack()) {
            web.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- fichiers
    private fun openDocument(url: String, name: String) {
        if (!url.startsWith(host)) return
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { URLUtil.guessFileName(url, null, null) }
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
            addRequestHeader("User-Agent", web.settings.userAgentString)
            setTitle(safeName)
            setDescription("GestSP — téléchargement")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "GestSP/$safeName")
        }
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(req)
        downloads[id] = safeName
        toast("Téléchargement de « $safeName »…")
    }

    private val downloadDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            val name = downloads.remove(id) ?: return
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = dm.getUriForDownloadedFile(id)
            if (uri == null) {
                toast("Échec du téléchargement de « $name » (session expirée ?)")
                return
            }
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, dm.getMimeTypeForDownloadedFile(id) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(view)
            } catch (_: ActivityNotFoundException) {
                toast("« $name » est dans Téléchargements/GestSP ; aucune application ne sait l'ouvrir.")
            }
        }
    }

    private fun shareLink(title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        }
        startActivity(Intent.createChooser(send, "Partager le lien"))
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILES) return
        val cb = fileCallback ?: return
        fileCallback = null
        if (resultCode != RESULT_OK) {
            photoUri?.let { contentResolver.delete(it, null, null) }
            photoUri = null
            cb.onReceiveValue(null)
            return
        }
        val uris = mutableListOf<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data?.data?.let { uris.add(it) }
        if (uris.isEmpty()) photoUri?.let { uris.add(it) }      // photo prise avec l'appareil
        photoUri = null
        cb.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------- navigateur
    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (!appLoaded) return false                     // pages de connexion : on laisse faire
            if (request.isForMainFrame && (url.startsWith("http://") || url.startsWith("https://"))) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }
            return false
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            if (!appLoaded && url.startsWith(site, ignoreCase = true) && !isLoginPage(url) && hasSession()) {
                CookieManager.getInstance().flush()
                showApp()
            }
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            handler.cancel()
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Certificat non reconnu")
                .setMessage("Le certificat du serveur n'est pas reconnu par ce téléphone. Installez le certificat de " +
                    "l'autorité du CEA (Paramètres › Sécurité › Chiffrement et identifiants › Installer un " +
                    "certificat), puis relancez GestSP.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>,
                                       params: FileChooserParams): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = callback
            val accept = params.acceptTypes.joinToString(",")
            val pick = params.createIntent().apply {
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            val intent = if (params.isCaptureEnabled || accept.contains("image")) {
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.FRANCE).format(Date())
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Photo_$stamp.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GestSP")
                }
                photoUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                if (params.isCaptureEnabled) camera
                else Intent.createChooser(pick, "Ajouter").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(camera))
            } else {
                photoUri = null
                Intent.createChooser(pick, "Choisir des fichiers")
            }
            return try {
                startActivityForResult(intent, REQ_FILES)
                true
            } catch (_: ActivityNotFoundException) {
                fileCallback = null
                toast("Aucune application ne permet de choisir un fichier.")
                false
            }
        }
    }

    // ------------------------------------------------------- pont JavaScript
    private inner class Bridge {
        @JavascriptInterface
        fun openFile(url: String, name: String) = runOnUiThread { if (appLoaded) openDocument(url, name) }

        @JavascriptInterface
        fun share(title: String, url: String) = runOnUiThread { if (appLoaded) shareLink(title, url) }

        @JavascriptInterface
        fun relogin() = runOnUiThread { if (appLoaded) this@MainActivity.logout(clear = false) }

        @JavascriptInterface
        fun logout() = runOnUiThread { if (appLoaded) this@MainActivity.logout(clear = true) }

        @JavascriptInterface
        fun setSite(url: String) = runOnUiThread { if (appLoaded) this@MainActivity.setSite(url) }

        @JavascriptInterface
        fun version(): String = BuildInfo.VERSION
    }

    companion object {
        const val DEFAULT_SITE = "https://aaccea.cea.fr"
        const val KEY_SITE = "site"
        const val REQ_FILES = 41
    }
}

object BuildInfo {
    const val VERSION = "1.0.0"
}
