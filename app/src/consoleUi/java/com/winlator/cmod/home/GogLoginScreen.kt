package com.winlator.cmod.home

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.winlator.cmod.R
import com.winlator.cmod.gog.GogApi
import com.winlator.cmod.gog.GogSession
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val PageShape = RoundedCornerShape(16.dp)

private sealed interface GogLoginStatus {
    data object Loading : GogLoginStatus
    data object Page : GogLoginStatus
    data object Working : GogLoginStatus
    data class Error(val message: String) : GogLoginStatus
}

/**
 * GOG sign-in: GOG's own login page in a WebView, so the user types the password into GOG
 * and WinXclipse only gets the one-time code from the final redirect.
 */
@Composable
fun GogLoginScreen(onBack: () -> Unit, onSignedIn: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<GogLoginStatus>(GogLoginStatus.Loading) }
    // Bumped by "Try again" to rebuild the WebView from the start page.
    var attempt by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    // The Google / Steam / Discord / Xbox sign-in window GOG's page opened, shown over it.
    var popup by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val child = popup
        val view = webView
        when {
            child != null -> if (child.canGoBack()) child.goBack() else popup = null
            view != null && view.canGoBack() && status != GogLoginStatus.Working -> view.goBack()
            else -> onBack()
        }
    }

    fun finish(code: String) {
        if (status == GogLoginStatus.Working) return
        status = GogLoginStatus.Working
        scope.launch {
            try {
                onSignedIn(GogSession.signIn(code))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GogLogin", "Sign-in failed", e)
                status = GogLoginStatus.Error(
                    if (e is IOException && e.cause != null) context.getString(R.string.gog_err_network)
                    else context.getString(R.string.gog_login_failed, e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    /** True (and sign-in started) when GOG redirected back with the code. */
    fun intercept(url: String?): Boolean {
        if (url == null || !url.startsWith(REDIRECT_PREFIX)) return false
        val code = Uri.parse(url).getQueryParameter("code")
        if (code.isNullOrBlank()) {
            status = GogLoginStatus.Error(context.getString(R.string.gog_login_failed, "no code"))
        } else {
            finish(code)
        }
        return true
    }

    Box(Modifier.fillMaxSize().background(Wx.Ground)) {
        Row(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(horizontal = 26.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WxIconButton(WxIcons.Back, stringResource(R.string.game_back_home), { if (popup != null) popup = null else onBack() })
                    Text(
                        stringResource(R.string.gog_login_title),
                        style = WxType.Title.copy(fontSize = 22.sp, fontWeight = FontWeight.ExtraBold),
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                Text(stringResource(R.string.gog_login_steps), style = WxType.Body.copy(color = Wx.TextSecondary))
                when (val current = status) {
                    GogLoginStatus.Loading, GogLoginStatus.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Wx.Lilac, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Text(
                            stringResource(if (current == GogLoginStatus.Working) R.string.gog_login_working else R.string.gog_login_loading),
                            style = WxType.Caption,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    is GogLoginStatus.Error -> {
                        Text(current.message, style = WxType.Body.copy(color = Wx.Lilac))
                        WxPrimaryButton(stringResource(R.string.steam_try_again), {
                            status = GogLoginStatus.Loading
                            attempt++
                        }, height = 42.dp)
                    }
                    GogLoginStatus.Page -> if (popup != null) {
                        Text(stringResource(R.string.gog_login_popup), style = WxType.Caption)
                    }
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(PageShape)
                    .border(1.dp, Wx.TintBorder.copy(alpha = 0.4f), PageShape)
                    .background(androidx.compose.ui.graphics.Color.White),
            ) {
                androidx.compose.runtime.key(attempt) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                applyLoginSettings(popups = true)
                                webChromeClient = object : LoggingChromeClient() {
                                    // GOG's social sign-in buttons window.open() the provider's page and
                                    // wait for it to postMessage a token back, so it needs a real child
                                    // window (with window.opener), not a reload of this page.
                                    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                                        val child = WebView(view.context).apply {
                                            applyLoginSettings(popups = false)
                                            webChromeClient = object : LoggingChromeClient() {
                                                override fun onCloseWindow(window: WebView) {
                                                    if (popup === window) popup = null
                                                }
                                            }
                                            webViewClient = object : WebViewClient() {
                                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                                    request.isForMainFrame && intercept(request.url.toString())
                                            }
                                        }
                                        (resultMsg.obj as WebView.WebViewTransport).webView = child
                                        resultMsg.sendToTarget()
                                        popup = child
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                        request.isForMainFrame && intercept(request.url.toString())

                                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                        if (intercept(url)) view.stopLoading()
                                    }

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        if (status == GogLoginStatus.Loading) status = GogLoginStatus.Page
                                    }

                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                        if (request.isForMainFrame && status != GogLoginStatus.Working &&
                                            !request.url.toString().startsWith(REDIRECT_PREFIX)
                                        ) {
                                            status = GogLoginStatus.Error(context.getString(R.string.gog_login_page_error))
                                        }
                                    }
                                }
                                loadUrl(GogApi.loginUrl)
                                webView = this
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        onRelease = { view ->
                            if (webView === view) {
                                webView = null
                                popup = null
                            }
                            view.stopLoading()
                            view.destroy()
                        },
                    )
                }
                popup?.let { child ->
                    androidx.compose.runtime.key(child) {
                        AndroidView(
                            factory = { child },
                            modifier = Modifier.fillMaxSize(),
                            onRelease = { view ->
                                view.stopLoading()
                                view.destroy()
                            },
                        )
                    }
                }
            }
        }
    }
}

private const val REDIRECT_PREFIX = "https://embed.gog.com/on_login_success"

/** Settings shared by the login page and the sign-in windows it opens. */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.applyLoginSettings(popups: Boolean) {
    // Not wrap_content: in that mode the page sees a 0 px tall window
    // and GOG's login box (sized from the viewport height) collapses.
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadWithOverviewMode = true
        useWideViewPort = true
        builtInZoomControls = true
        displayZoomControls = false
        setSupportZoom(true)
        allowFileAccess = false
        allowContentAccess = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        setSupportMultipleWindows(popups)
        javaScriptCanOpenWindowsAutomatically = popups
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

private open class LoggingChromeClient : WebChromeClient() {
    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
        if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
            Log.w("GogLogin", "page: ${message.message()} (${message.sourceId().substringBefore('?')}:${message.lineNumber()})")
        }
        return true
    }
}

/**
 * Forgets the GOG web session (so the next sign-in can use another account) without
 * touching cookies other WebViews of the app keep.
 */
fun clearGogWebSession() {
    val cookies = CookieManager.getInstance()
    for (host in listOf("https://auth.gog.com", "https://login.gog.com", "https://www.gog.com", "https://embed.gog.com", "https://gog.com")) {
        val names = cookies.getCookie(host)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() } ?: continue
        for (name in names) {
            cookies.setCookie(host, "$name=; Max-Age=0; Path=/")
            cookies.setCookie(host, "$name=; Max-Age=0; Path=/; Domain=.gog.com")
        }
    }
    cookies.flush()
}
