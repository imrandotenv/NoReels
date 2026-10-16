package com.example.minsta

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Minsta - Digital Wellbeing Instagram Wrapper
 * Features:
 * - One-time per install Startup Credit Dialog (via SharedPreferences)
 * - Blocks external deep links (intent://, instagram://, market://)
 * - Custom Back button navigation & double-tap to exit on /direct/inbox/
 * - Shared 5-minute usage timer for Home Feed (/) and Explore (/explore/)
 * - 1-hour cooldown with dynamic time remaining warning via JavascriptInterface
 * - Stories (/stories/) bypasses all timers and cooldowns
 * - DM Reels touch/swipe lock (single video playback only)
 * - Force Dark Mode, Grayscale Mode, and Social Metrics Hiding (Likes/Followers)
 * - Custom Loading Screen Overlay
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var loadingOverlay: View

    // Double-tap to exit state
    private var backPressedTime: Long = 0
    private var backToast: Toast? = null

    companion object {
        private const val PREFS_NAME = "minsta_app_prefs"
        private const val KEY_STARTUP_CREDIT_SHOWN = "has_shown_startup_credit"

        private const val INSTAGRAM_HOME_URL = "https://www.instagram.com/"
        private const val INSTAGRAM_DIRECT_URL = "https://www.instagram.com/direct/inbox/"
        private const val USER_AGENT_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        webView.visibility = View.VISIBLE

        // Safety fallback timer: Guarantee loading screen is dismissed even on slow network
        Handler(Looper.getMainLooper()).postDelayed({
            hideLoading()
        }, 5000)

        // 1. One-Time Startup Credit using SharedPreferences (only shows once per install)
        checkAndShowStartupCredit()

        setupBackNavigation()
        setupWebView()

        // Restore state or load initial page
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(INSTAGRAM_HOME_URL)
        }
    }

    /**
     * Hides the full-screen loading spinner overlay and ensures the WebView is visible.
     */
    private fun hideLoading() {
        runOnUiThread {
            if (::loadingOverlay.isInitialized) {
                loadingOverlay.visibility = View.GONE
            } else {
                findViewById<View>(R.id.loadingOverlay)?.visibility = View.GONE
            }
            if (::progressBar.isInitialized) {
                progressBar.visibility = View.GONE
            } else {
                findViewById<View>(R.id.progressBar)?.visibility = View.GONE
            }
            if (::webView.isInitialized) {
                webView.visibility = View.VISIBLE
            } else {
                findViewById<View>(R.id.webView)?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Checks SharedPreferences and displays the startup credit dialog only on the first install run.
     */
    private fun checkAndShowStartupCredit() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasShown = prefs.getBoolean(KEY_STARTUP_CREDIT_SHOWN, false)
        if (!hasShown) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Digital Wellbeing")
                .setMessage("Developed by Imran Ahmad — For those seeking an escape from endless doomscrolling.")
                .setPositiveButton("Got it") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .show()

            prefs.edit().putBoolean(KEY_STARTUP_CREDIT_SHOWN, true).apply()
        }
    }

    /**
     * Custom Back Button Navigation:
     * - If WebView has backward history and not at the exit point, navigate back.
     * - If on /direct/inbox/ or at the end of history, require double-tap within 2 seconds to exit.
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })
    }

    fun handleBackPress() {
        val currentUrl = webView.url ?: ""
        val uri = Uri.parse(currentUrl)
        val path = uri.path ?: ""
        val isDirectInbox = path == "/direct/inbox" || path == "/direct/inbox/" || path.startsWith("/direct/inbox")
        val canGoBack = webView.canGoBack()

        // Exit condition: user is on direct inbox OR webView has reached the root history
        if (isDirectInbox || !canGoBack) {
            if (backPressedTime + 2000 > System.currentTimeMillis()) {
                backToast?.cancel()
                finish()
            } else {
                backToast = Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT)
                backToast?.show()
                backPressedTime = System.currentTimeMillis()
            }
        } else {
            webView.goBack()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings

        // 1. Explicitly enable Cookies & Third-Party Cookies for session persistence
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // 2. Explicitly enable DOM storage & JavaScript (crucial for localStorage timers & login tokens)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // 3. Force Dark Mode (Native WebSettings)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            settings.isAlgorithmicDarkeningAllowed = true
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            settings.forceDark = WebSettings.FORCE_DARK_ON
        }

        // 4. Core WebSettings Configuration
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false // Allow video autoplay for DM Reels & Stories
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportMultipleWindows(false)
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.userAgentString = USER_AGENT_MOBILE

        // 4. Android JavascriptInterface Bridge (named AndroidApp and AndroidBridge)
        val jsInterface = object {
            @JavascriptInterface
            fun showToast(message: String) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }

            @JavascriptInterface
            fun showDialog(title: String, message: String) {
                Handler(Looper.getMainLooper()).post {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(title)
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }

            @JavascriptInterface
            fun goBack() {
                Handler(Looper.getMainLooper()).post {
                    handleBackPress()
                }
            }
        }
        webView.addJavascriptInterface(jsInterface, "AndroidApp")
        webView.addJavascriptInterface(jsInterface, "AndroidBridge")

        // 5. WebViewClient: Strict URL Filtering & SPA Navigation Hook
        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlNavigation(view, url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlNavigation(view, url)
            }

            private fun handleUrlNavigation(view: WebView?, url: String): Boolean {
                val uri = Uri.parse(url)
                val scheme = uri.scheme?.lowercase() ?: ""

                // BLOCK: Deep Links, Intents, and Market schemes (never open external apps/Play Store)
                if (scheme == "intent" || scheme == "instagram" || scheme == "market" ||
                    url.startsWith("intent://") || url.startsWith("instagram://") || url.startsWith("market://")
                ) {
                    return true
                }

                // BLOCK: App store domains and tracking redirects
                val host = uri.host?.lowercase() ?: ""
                if (host.contains("play.google.com") || host.contains("apps.apple.com") || host.contains("app.adjust.com")) {
                    return true
                }

                // ALLOW ONLY standard http and https
                if (scheme != "http" && scheme != "https") {
                    return true
                }

                // RULE: Block dedicated Reels tab -> Redirect to Direct Inbox
                // (Explore /explore/ is permitted and governed by shared 5-min timer in JS)
                if (url.contains("/reels/")) {
                    view?.loadUrl(INSTAGRAM_DIRECT_URL)
                    return true
                }

                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE

                // Guard check: Instantly redirect /reels/
                if (url != null && url.contains("/reels/")) {
                    view?.loadUrl(INSTAGRAM_DIRECT_URL)
                    return
                }

                // Early CSS injection: Apply dark theme, grayscale, hide metrics & reels
                injectCustomCSS()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Hide loading overlay and ensure webView is visible
                hideLoading()

                // Persist session cookies to disk
                CookieManager.getInstance().flush()

                // Dual injection on finish: Reapply CSS and run Digital Wellbeing JS controller
                injectCustomCSS()
                injectCustomJS()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                hideLoading()
            }

            @Deprecated("Deprecated in Java")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                @Suppress("DEPRECATION")
                super.onReceivedError(view, errorCode, description, failingUrl)
                hideLoading()
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                hideLoading()
            }

            // SPA Route Listener: Notifies injected JS whenever history updates without a page reload
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null) {
                    if (url.contains("/reels/")) {
                        view?.loadUrl(INSTAGRAM_DIRECT_URL)
                        return
                    }
                    view?.evaluateJavascript(
                        "if (typeof window.__handleMinstaRoute === 'function') { window.__handleMinstaRoute(); }",
                        null
                    )
                }
            }
        }

        // 6. WebChromeClient for page loading progress bar
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress >= 70) {
                    hideLoading()
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                return true
            }
        }
    }

    /**
     * Injects CSS into the DOM:
     * 1. Force Dark Mode (color-scheme & black background)
     * 2. Grayscale Mode (filter: grayscale(100%))
     * 3. Hide Metrics (like counts on posts & follower/following counts on profiles)
     * 4. Hide Reels navigation icon (Search/Explore icon is KEPT VISIBLE)
     * 5. Hide 'Open App' & 'Open Instagram' banners and buttons
     * 6. Lock vertical swiping on single DM Reel page
     */
    private fun injectCustomCSS() {
        val css = """
            /* 1. Force Dark Mode via CSS */
            :root {
                color-scheme: dark !important;
            }
            html, body {
                background-color: #000000 !important;
                color: #f5f5f5 !important;
            }

            /* 2. Grayscale Mode: Calm monochrome aesthetic to reduce visual stimulation */
            html, body {
                filter: grayscale(100%) !important;
                -webkit-filter: grayscale(100%) !important;
            }

            /* 3. Hide Metrics: Likes on posts & Follower/Following counts on profiles */
            a[href*="/liked_by/"],
            div:has(> a[href*="/liked_by/"]),
            span:has(> a[href*="/liked_by/"]),
            section:has(svg[aria-label="Like"]) + div:has(a),
            section:has(svg[aria-label="Unlike"]) + div:has(a) {
                display: none !important;
                visibility: hidden !important;
                pointer-events: none !important;
            }

            /* Hide Follower & Following Counts on User Profiles */
            a[href*="/followers/"],
            a[href*="/following/"],
            li:has(a[href*="/followers/"]),
            li:has(a[href*="/following/"]),
            div:has(> a[href*="/followers/"]),
            div:has(> a[href*="/following/"]),
            header section ul li:nth-child(2),
            header section ul li:nth-child(3) {
                display: none !important;
                visibility: hidden !important;
                pointer-events: none !important;
            }

            /* 4. Permanently hide Reels navigation icons (Search/Explore icon is KEPT VISIBLE) */
            nav a[href*="/reels"],
            div[role="navigation"] a[href*="/reels"],
            footer a[href*="/reels"],
            header a[href*="/reels"],
            a:has(svg[aria-label="Reels"]),
            a:has(svg[aria-label="reels"]),
            a:has(svg[aria-label="Clips"]),
            svg[aria-label="Reels"],
            svg[aria-label="reels"],
            svg[aria-label="Clips"] {
                display: none !important;
                visibility: hidden !important;
                pointer-events: none !important;
                width: 0 !important;
                height: 0 !important;
                overflow: hidden !important;
                opacity: 0 !important;
            }

            /* 5. Hide 'Open App' and 'Open Instagram' banners, buttons, and prompts */
            div[role="banner"],
            header a[href*="app"],
            header button,
            a[href^="intent:"],
            a[href^="instagram:"],
            a[href^="market:"],
            a[href*="play.google.com"],
            a[href*="apps.apple.com"],
            a[href*="/download/"],
            a[href*="app_id="],
            div:has(> a[href*="play.google.com"]),
            div:has(> a[href^="intent:"]),
            div:has(> a[href^="instagram:"]),
            div[aria-label*="Get the app"],
            div[aria-label*="Open in app"],
            div[aria-label*="Open App"],
            div[aria-label*="Use the app"] {
                display: none !important;
                visibility: hidden !important;
                pointer-events: none !important;
            }

            /* Hide Reels feed cards outside direct messages */
            body:not(.on-dm-chat) a[href*="/reels/"],
            body:not(.on-dm-chat) a[href*="/reel/"],
            article:has(a[href*="/reel/"]),
            article:has(a[href*="/reels/"]) {
                display: none !important;
            }

            /* 6. DM Reel Lock Styles (Single Video Viewer, No Swipe-Up) */
            body.dm-reel-locked {
                overflow: hidden !important;
                position: fixed !important;
                width: 100vw !important;
                height: 100vh !important;
                touch-action: pan-x pinch-zoom !important;
                overscroll-behavior: none !important;
            }

            body.dm-reel-locked div[style*="overflow-y: scroll"],
            body.dm-reel-locked div[style*="overflow-y: auto"],
            body.dm-reel-locked div[style*="scroll-snap-type"] {
                overflow-y: hidden !important;
                scroll-snap-type: none !important;
            }

            /* Floating "Back to Chat" Button on DM Reel View */
            #minsta-back-btn {
                position: fixed;
                top: 14px;
                left: 14px;
                z-index: 999999;
                background: rgba(0, 0, 0, 0.75);
                color: #ffffff;
                border: 1px solid rgba(255, 255, 255, 0.3);
                border-radius: 20px;
                padding: 8px 16px;
                font-size: 13px;
                font-weight: 600;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                display: flex;
                align-items: center;
                gap: 6px;
                cursor: pointer;
                backdrop-filter: blur(8px);
                box-shadow: 0 4px 12px rgba(0,0,0,0.4);
            }

            /* Floating Usage Timer Badge */
            #minsta-timer-badge {
                position: fixed;
                top: 10px;
                right: 14px;
                z-index: 99999;
                background: rgba(18, 18, 18, 0.9);
                color: #ffffff;
                border: 1px solid rgba(255, 255, 255, 0.2);
                border-radius: 16px;
                padding: 5px 12px;
                font-size: 11px;
                font-weight: 600;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                display: flex;
                align-items: center;
                gap: 5px;
                pointer-events: none;
                box-shadow: 0 2px 8px rgba(0,0,0,0.3);
            }

            #minsta-timer-badge.warning {
                background: rgba(220, 38, 38, 0.92);
                border-color: rgba(239, 68, 68, 0.6);
            }
        """.trimIndent()

        val encoded = Base64.encodeToString(css.toByteArray(), Base64.NO_WRAP)
        val script = """
            (function() {
                var parent = document.head || document.documentElement;
                var style = document.getElementById('minsta-injected-css');
                if (!style) {
                    style = document.createElement('style');
                    style.id = 'minsta-injected-css';
                    style.type = 'text/css';
                    parent.appendChild(style);
                }
                style.innerHTML = window.atob('$encoded');
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    /**
     * Injects JavaScript rules:
     * 1. Force dark theme classes on Instagram HTML
     * 2. Hide metrics (like counts, follower/following counts) dynamically
     * 3. Explore/Search button is PRESERVED (not hidden)
     * 4. Shared 5-Minute Limit for Home Feed (/) AND Explore Feed (/explore/)
     * 5. 1-Hour Cooldown: Once 5 minutes are exhausted, locks both Home and Explore for 1 hour.
     *    Dynamically calculates remaining time (e.g., 'Feed limit reached. 45 minutes remaining.')
     *    and calls AndroidApp.showToast(...) before redirecting to /direct/inbox/.
     * 6. Bypass Stories (/stories/): Unlimited, never limited or blocked.
     * 7. DM Reels lock: Allows single video playback, completely disables vertical swiping.
     */
    private fun injectCustomJS() {
        val js = """
            (function() {
                if (window.__MINSTA_WELLBEING_INITIALIZED__) {
                    if (typeof window.__handleMinstaRoute === 'function') {
                        window.__handleMinstaRoute();
                    }
                    return;
                }
                window.__MINSTA_WELLBEING_INITIALIZED__ = true;

                const FEED_LIMIT_SEC = 5 * 60; // Shared 5 minutes (300 seconds)
                const COOLDOWN_DURATION_MS = 60 * 60 * 1000; // 1 hour (3,600,000 ms)

                let sharedTimer = null;
                let lastUrl = window.location.href;
                let lockedReelPath = null;
                let touchStartY = 0;

                // --- 1. Enforce Dark Mode on Instagram DOM ---
                function enforceDarkMode() {
                    try {
                        document.documentElement.classList.add('theme-dark');
                        document.documentElement.setAttribute('data-theme', 'dark');
                    } catch (_) {}
                }

                // --- 2. Hide Metrics Dynamically ---
                function hideMetrics() {
                    const likeLinks = document.querySelectorAll('a[href*="/liked_by/"]');
                    likeLinks.forEach(function(el) {
                        el.style.display = 'none';
                        if (el.parentElement) el.parentElement.style.display = 'none';
                    });

                    const followerLinks = document.querySelectorAll('a[href*="/followers/"], a[href*="/following/"]');
                    followerLinks.forEach(function(el) {
                        el.style.display = 'none';
                        if (el.parentElement && el.parentElement.tagName.toLowerCase() === 'li') {
                            el.parentElement.style.display = 'none';
                        }
                    });
                }

                // --- Helper to show Toast via AndroidApp Interface ---
                function notifyNativeToast(msg) {
                    if (window.AndroidApp && window.AndroidApp.showToast) {
                        window.AndroidApp.showToast(msg);
                    } else if (window.AndroidBridge && window.AndroidBridge.showToast) {
                        window.AndroidBridge.showToast(msg);
                    }
                }

                // --- Hide 'Open App' / 'Open Instagram' Elements by Text & Attributes ---
                function hideOpenAppPrompts() {
                    const candidates = document.querySelectorAll('button, a, div[role="button"], div[role="banner"]');
                    candidates.forEach(function(el) {
                        const text = (el.innerText || el.textContent || '').trim().toLowerCase();
                        if (
                            text === 'open instagram' ||
                            text === 'open app' ||
                            text === 'get app' ||
                            text === 'get instagram' ||
                            text === 'use the app' ||
                            text === 'install' ||
                            text.startsWith('open in the instagram app') ||
                            text === 'open in app' ||
                            text === 'open'
                        ) {
                            if (!text.includes('log in') && !text.includes('sign up')) {
                                el.style.display = 'none';
                                el.style.visibility = 'hidden';
                                el.style.pointerEvents = 'none';
                                if (el.parentElement && el.parentElement.children.length === 1) {
                                    el.parentElement.style.display = 'none';
                                }
                            }
                        }
                    });

                    const deepLinks = document.querySelectorAll('a[href^="intent:"], a[href^="instagram:"], a[href^="market:"], a[href*="play.google.com"]');
                    deepLinks.forEach(function(a) {
                        a.style.display = 'none';
                        a.style.pointerEvents = 'none';
                    });
                }

                // --- Storage Helpers (Shared between Home and Explore) ---
                function getSharedSpent() {
                    const v = localStorage.getItem('minsta_shared_spent_sec');
                    return v ? parseInt(v, 10) : 0;
                }
                function setSharedSpent(sec) {
                    localStorage.setItem('minsta_shared_spent_sec', sec.toString());
                }
                function getCooldownUntil() {
                    const v = localStorage.getItem('minsta_shared_cooldown_until');
                    return v ? parseInt(v, 10) : 0;
                }
                function setCooldownUntil(ms) {
                    localStorage.setItem('minsta_shared_cooldown_until', ms.toString());
                }
                function clearCooldown() {
                    localStorage.removeItem('minsta_shared_cooldown_until');
                    setSharedSpent(0);
                }

                // --- Dynamic Remaining Cooldown Warning Message ---
                function getCooldownRemainingMessage() {
                    const cooldown = getCooldownUntil();
                    const now = Date.now();
                    const diffMs = cooldown - now;
                    if (diffMs <= 0) return null;
                    const mins = Math.max(1, Math.ceil(diffMs / 60000));
                    return 'Feed limit reached. ' + mins + ' ' + (mins === 1 ? 'minute' : 'minutes') + ' remaining.';
                }

                // --- Route Checks ---
                function isStories() {
                    return window.location.pathname.includes('/stories/') || window.location.href.includes('/stories/');
                }
                function isReelsFeed() {
                    const p = window.location.pathname;
                    return p.includes('/reels/') || p === '/reels';
                }
                function isLimitedFeedRoute() {
                    const p = window.location.pathname;
                    const isHome = (p === '/' || p === '' || p === '/index.php');
                    const isExplore = p.startsWith('/explore');
                    return (isHome || isExplore);
                }
                function isDirectChat() {
                    return window.location.pathname.startsWith('/direct/');
                }
                function isSingleReel() {
                    return window.location.pathname.startsWith('/reel/');
                }
                function isUserLoggedIn() {
                    const hasNav = document.querySelector('nav, div[role="navigation"], footer a[href*="/direct/"]');
                    const isLoginPage = document.body && (
                        document.body.innerText.includes('Log in or sign up') ||
                        document.querySelector('input[name="username"]') ||
                        window.location.pathname.startsWith('/accounts/')
                    );
                    return !!hasNav && !isLoginPage;
                }

                // --- UI Timer Badge ---
                function updateTimerBadge(text, isWarning, visible) {
                    let badge = document.getElementById('minsta-timer-badge');
                    if (!badge) {
                        badge = document.createElement('div');
                        badge.id = 'minsta-timer-badge';
                        document.body.appendChild(badge);
                    }
                    if (!visible) {
                        badge.style.display = 'none';
                        return;
                    }
                    badge.style.display = 'flex';
                    badge.innerHTML = '<span>⏱️</span> <span>' + text + '</span>';
                    if (isWarning) badge.classList.add('warning');
                    else badge.classList.remove('warning');
                }

                // --- Floating "Back to Chat" Button for DM Reels ---
                function updateReelBackButton(visible) {
                    let btn = document.getElementById('minsta-back-btn');
                    if (!visible) {
                        if (btn) btn.remove();
                        return;
                    }
                    if (!btn) {
                        btn = document.createElement('button');
                        btn.id = 'minsta-back-btn';
                        btn.innerHTML = '&#8592; Back to Chat';
                        btn.addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            if (window.AndroidApp && window.AndroidApp.goBack) {
                                window.AndroidApp.goBack();
                            } else if (window.AndroidBridge && window.AndroidBridge.goBack) {
                                window.AndroidBridge.goBack();
                            } else if (window.history.length > 1) {
                                window.history.back();
                            } else {
                                window.location.replace('https://www.instagram.com/direct/inbox/');
                            }
                        });
                        document.body.appendChild(btn);
                    }
                }

                // --- DM Reel Swipe & Scroll Prevention ---
                function setupReelLockListeners() {
                    window.addEventListener('touchstart', function(e) {
                        if (isSingleReel() && e.touches && e.touches.length > 0) {
                            touchStartY = e.touches[0].clientY;
                        }
                    }, { passive: true, capture: true });

                    window.addEventListener('touchmove', function(e) {
                        if (!isSingleReel()) return;
                        if (!e.touches || e.touches.length === 0) return;
                        const deltaY = Math.abs(e.touches[0].clientY - touchStartY);
                        if (deltaY > 5) {
                            e.preventDefault();
                            e.stopPropagation();
                            e.stopImmediatePropagation();
                            return false;
                        }
                    }, { passive: false, capture: true });

                    window.addEventListener('wheel', function(e) {
                        if (isSingleReel()) {
                            e.preventDefault();
                            e.stopPropagation();
                            return false;
                        }
                    }, { passive: false, capture: true });

                    window.addEventListener('keydown', function(e) {
                        if (isSingleReel()) {
                            if (['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', ' ', 'Spacebar'].includes(e.key)) {
                                e.preventDefault();
                                e.stopPropagation();
                                return false;
                            }
                        }
                    }, { capture: true });
                }

                function applyReelState() {
                    if (isSingleReel()) {
                        document.body.classList.add('dm-reel-locked');
                        updateReelBackButton(true);

                        if (!lockedReelPath) {
                            lockedReelPath = window.location.pathname;
                        } else if (window.location.pathname !== lockedReelPath) {
                            window.history.back();
                        }

                        const containers = document.querySelectorAll('div[style*="overflow-y"], div[style*="scroll-snap-type"]');
                        containers.forEach(function(el) {
                            el.style.overflow = 'hidden';
                            el.style.scrollSnapType = 'none';
                        });
                    } else {
                        document.body.classList.remove('dm-reel-locked');
                        updateReelBackButton(false);
                        lockedReelPath = null;
                    }
                }

                // --- Shared 5-Minute Timer (Home Feed + Explore) & 1-Hour Cooldown ---
                function startSharedTimer() {
                    stopSharedTimer();
                    let spent = getSharedSpent();

                    const tick = () => {
                        if (!isUserLoggedIn()) {
                            updateTimerBadge('', false, false);
                            return;
                        }

                        const now = Date.now();
                        const cooldown = getCooldownUntil();
                        if (cooldown > now) {
                            stopSharedTimer();
                            const warningMsg = getCooldownRemainingMessage() || "Feed limit reached. Come back after 1 hour.";
                            notifyNativeToast(warningMsg);
                            window.location.replace('https://www.instagram.com/direct/inbox/');
                            return;
                        } else if (cooldown > 0) {
                            clearCooldown();
                        }

                        spent++;
                        setSharedSpent(spent);
                        const remaining = Math.max(0, FEED_LIMIT_SEC - spent);
                        const mins = Math.floor(remaining / 60);
                        const secs = remaining % 60;
                        const formatted = mins + ':' + (secs < 10 ? '0' : '') + secs;

                        const isExplore = window.location.pathname.startsWith('/explore');
                        const label = isExplore ? 'Explore: ' : 'Home Feed: ';
                        updateTimerBadge(label + formatted + ' left', remaining <= 60, true);

                        // If 5 minutes exhausted
                        if (spent >= FEED_LIMIT_SEC) {
                            stopSharedTimer();
                            const cooldownUntil = Date.now() + COOLDOWN_DURATION_MS;
                            setCooldownUntil(cooldownUntil);
                            setSharedSpent(0);

                            notifyNativeToast("Feed limit reached. 60 minutes remaining.");
                            window.location.replace('https://www.instagram.com/direct/inbox/');
                        }
                    };

                    tick();
                    sharedTimer = setInterval(tick, 1000);
                }

                function stopSharedTimer() {
                    if (sharedTimer) {
                        clearInterval(sharedTimer);
                        sharedTimer = null;
                    }
                    updateTimerBadge('', false, false);
                }

                function redirectMessages(reason) {
                    if (reason) notifyNativeToast(reason);
                    window.location.replace('https://www.instagram.com/direct/inbox/');
                }

                // --- Route Handler (Global for SPA changes) ---
                window.__handleMinstaRoute = function() {
                    enforceDarkMode();
                    hideOpenAppPrompts();
                    hideMetrics();
                    lastUrl = window.location.href;
                    const path = window.location.pathname;

                    if (isDirectChat()) {
                        document.body.classList.add('on-dm-chat');
                    } else {
                        document.body.classList.remove('on-dm-chat');
                    }

                    // 1. Rule: Block Reels Feed (Explore is permitted!)
                    if (isReelsFeed()) {
                        stopSharedTimer();
                        redirectMessages("Reels are blocked.");
                        return;
                    }

                    applyReelState();

                    // 2. Rule: Bypass Stories (/stories/): Unlimited, no timer, no redirect
                    if (isStories()) {
                        stopSharedTimer();
                        return;
                    }

                    // 3. Rule: Check 1-Hour Cooldown for Home Feed (/) AND Explore (/explore/)
                    const now = Date.now();
                    const cooldown = getCooldownUntil();
                    if (cooldown > now) {
                        if (isLimitedFeedRoute() && isUserLoggedIn()) {
                            stopSharedTimer();
                            const dynamicMsg = getCooldownRemainingMessage() || "Feed limit reached. Come back after 1 hour.";
                            notifyNativeToast(dynamicMsg);
                            window.location.replace('https://www.instagram.com/direct/inbox/');
                            return;
                        } else {
                            stopSharedTimer();
                            return;
                        }
                    } else if (cooldown > 0) {
                        clearCooldown();
                    }

                    // 4. Rule: Shared 5-Minute Timer on Home Feed OR Explore
                    if (isLimitedFeedRoute()) {
                        startSharedTimer();
                    } else {
                        stopSharedTimer();
                    }
                };

                // Setup SPA History hooks
                const pushState = history.pushState;
                history.pushState = function() {
                    pushState.apply(this, arguments);
                    window.__handleMinstaRoute();
                };
                const replaceState = history.replaceState;
                history.replaceState = function() {
                    replaceState.apply(this, arguments);
                    window.__handleMinstaRoute();
                };
                window.addEventListener('popstate', window.__handleMinstaRoute);
                window.addEventListener('hashchange', window.__handleMinstaRoute);

                // Polling safety check for URL changes, recurring prompt cleanup & metrics
                setInterval(() => {
                    enforceDarkMode();
                    hideOpenAppPrompts();
                    hideMetrics();
                    if (window.location.href !== lastUrl) {
                        window.__handleMinstaRoute();
                    }
                }, 300);

                // DOM observer to sweep Reels, Metrics, and 'Open App' banners
                const observer = new MutationObserver(() => {
                    enforceDarkMode();
                    hideOpenAppPrompts();
                    hideMetrics();
                    const inDm = isDirectChat();
                    const navs = document.querySelectorAll('nav a, div[role="navigation"] a, footer a');
                    navs.forEach(function(a) {
                        const href = a.getAttribute('href') || '';
                        const svg = a.querySelector('svg');
                        const label = svg ? svg.getAttribute('aria-label') : '';
                        if (href.includes('/reels') || label === 'Reels' || label === 'Clips') {
                            a.style.display = 'none';
                            a.style.pointerEvents = 'none';
                        }
                    });

                    if (!inDm && !isSingleReel()) {
                        const reels = document.querySelectorAll('a[href*="/reels/"], a[href*="/reel/"]');
                        reels.forEach(function(r) {
                            r.style.display = 'none';
                            r.style.pointerEvents = 'none';
                        });
                    }

                    if (isSingleReel()) {
                        applyReelState();
                    }
                });

                observer.observe(document.documentElement || document.body, {
                    childList: true,
                    subtree: true
                });

                setupReelLockListeners();
                enforceDarkMode();
                hideOpenAppPrompts();
                hideMetrics();
                window.__handleMinstaRoute();
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        webView.onPause()
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }
}