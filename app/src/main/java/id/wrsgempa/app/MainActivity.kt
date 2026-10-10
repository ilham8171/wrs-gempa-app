package id.wrsgempa.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileChooserCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        fileChooserCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Native FCM subscription is independent of the website's existing UI and code.
        FirebaseMessaging.getInstance().subscribeToTopic("wrs-gempa-alerts")
        requestNotificationPermissionIfNeeded()

        @SuppressLint("SetJavaScriptEnabled")
        val view = WebView(this)
        webView = view
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.databaseEnabled = true
        view.settings.loadsImagesAutomatically = true
        view.settings.javaScriptCanOpenWindowsAutomatically = true
        view.settings.setSupportMultipleWindows(false)
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = true
        view.settings.setGeolocationEnabled(true)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return if (uri.scheme == "https" && uri.host == "wrsgempa.netlify.app") {
                    false
                } else if (uri.scheme == "http" || uri.scheme == "https") {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } else {
                    true
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val trusted = Uri.parse(origin).host == "wrsgempa.netlify.app"
                callback.invoke(origin, trusted, false)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                filePicker.launch("*/*")
                return true
            }
        }
        setContentView(view)
        view.loadUrl("https://wrsgempa.netlify.app")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5107)
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.destroy()
        }
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        super.onDestroy()
    }
}
