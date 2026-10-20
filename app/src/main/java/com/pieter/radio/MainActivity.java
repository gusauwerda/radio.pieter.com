package com.pieter.radio;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String URL = "https://radio.pieter.com/";

    // The site shows a "click to tune in" splash because browsers block autoplay with sound.
    // Autoplay is allowed in this WebView, so dismiss the splash and start the stream for the user.
    private static final String AUTO_TUNE_IN =
            "(function(){ try { if (typeof startRadio === 'function') startRadio(); } catch(e){} })();";

    private static final String HOST = "radio.pieter.com";
    private static final float DEFAULT_SCALE = 0.8f;
    private static final Pattern VIEWPORT_META =
            Pattern.compile("<meta[^>]+name=[\"']viewport[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEAD_OPEN = Pattern.compile("<head[^>]*>", Pattern.CASE_INSENSITIVE);

    private WebView webView;
    private SharedPreferences prefs;
    private String userAgent;

    /** Called from the injected toolbar's −/+ buttons. */
    private class AppBridge {
        @JavascriptInterface
        public void setScale(float scale) {
            float s = Math.max(0.5f, Math.min(1.5f, scale));
            prefs.edit().putFloat("scale", s).apply();
            runOnUiThread(() -> webView.reload());
        }
    }

    /**
     * Fetches the radio page and adds the app overlay (assets/inject.html) to its head: a viewport
     * that renders everything at the chosen scale, plus the chat / clear-view / size toolbar.
     * The page's own viewport meta is removed, since it would override ours.
     */
    private WebResourceResponse loadPageWithOverlay(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", userAgent);
        c.setRequestProperty("Accept", "text/html");
        CookieManager cm = CookieManager.getInstance();
        String cookies = cm.getCookie(url);
        if (cookies != null) c.setRequestProperty("Cookie", cookies);
        if (c.getResponseCode() != 200) return null;
        List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
        if (setCookies != null) for (String sc : setCookies) cm.setCookie(url, sc);

        String html = readAll(c.getInputStream());
        String scale = String.valueOf(prefs.getFloat("scale", DEFAULT_SCALE));
        String overlay = readAll(getAssets().open("inject.html")).replace("__SCALE__", scale);

        html = VIEWPORT_META.matcher(html).replaceAll("");
        Matcher head = HEAD_OPEN.matcher(html);
        html = head.find()
                ? html.substring(0, head.end()) + overlay + html.substring(head.end())
                : overlay + html;
        return new WebResourceResponse("text/html", "utf-8",
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);   // chrome://inspect on debug builds
        }
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        prefs = getSharedPreferences("radio", MODE_PRIVATE);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        userAgent = s.getUserAgentString();

        webView.addJavascriptInterface(new AppBridge(), "AppBridge");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (request.isForMainFrame() && "GET".equals(request.getMethod())
                        && HOST.equals(u.getHost())
                        && (u.getPath() == null || u.getPath().isEmpty() || "/".equals(u.getPath()))) {
                    try {
                        return loadPageWithOverlay(u.toString());
                    } catch (Exception e) {
                        return null;   // fall back to the plain page
                    }
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(AUTO_TUNE_IN, null);
                view.evaluateJavascript("JSON.stringify([innerWidth, innerHeight, devicePixelRatio,"
                        + " (document.querySelector('meta[name=viewport]')||{}).content, navigator.userAgent])",
                        v -> android.util.Log.d("RadioPieter", "viewport " + v));
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl(URL);
        }
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    // The remote's center/OK button goes to the page's appCenterButton() (next background video, or
    // press the focused toolbar button) instead of the WebView's default click.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                webView.evaluateJavascript(
                        "window.appCenterButton && appCenterButton()",
                        v -> android.util.Log.d("RadioPieter", "center button: " + v));
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    // onPause/onResume are intentionally not forwarded to the WebView so the radio keeps
    // playing when you switch to another app.

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
