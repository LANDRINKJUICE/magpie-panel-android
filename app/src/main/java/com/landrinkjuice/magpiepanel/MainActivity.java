package com.landrinkjuice.magpiepanel;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Magpie 网关面板的 WebView 壳。
 * 只做四件事：承载本机面板、把外链交给系统浏览器、把面板导出下载到 Download 目录、
 * 提供 URL/密钥设置。无任何自启、保活、后台行为。
 */
public class MainActivity extends Activity {

    private static final String PREFS = "panel";
    private static final String PREF_URL = "url";
    private static final String PREF_KEY = "key";
    private static final String DEFAULT_URL = "http://127.0.0.1:3430";
    private static final int REQ_FILE = 1001;

    private WebView web;
    private View overlay;
    private TextView errDetail;
    private ValueCallback<Uri[]> fileCallback;
    private AlertDialog settingsDialog;
    /** onCreateWindow 临时窗口的一次性销毁器（幂等，延迟执行）。 */
    private Runnable popupDispose;
    private boolean loadFailed;
    private boolean settingsShown;
    /** loadPanel() 发起后置位：面板成功落地时清一次历史，避免 303/401 跳转残留在返回栈。 */
    private boolean pendingClearHistory;
    private final ExecutorService dl = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "magpie-download");
        t.setDaemon(true);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        web = findViewById(R.id.web);
        overlay = findViewById(R.id.error_overlay);
        errDetail = findViewById(R.id.err_detail);
        findViewById(R.id.btn_retry).setOnClickListener(v -> loadPanel());
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettings());

        setupWebView();
        loadPanel();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // 面板用 window.open 打开外部链接：必须允许多窗口，onCreateWindow 里截获后路由
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= 33) {
            s.setAlgorithmicDarkeningAllowed(true);
        }

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                loadFailed = false;
                settingsShown = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (loadFailed) {
                    return;
                }
                overlay.setVisibility(View.GONE);
                Uri u = Uri.parse(url);
                if (pendingClearHistory && isPanelOrigin(u)) {
                    view.clearHistory();
                    pendingClearHistory = false;
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame() && isFatalError(error.getErrorCode())) {
                    loadFailed = true;
                    showError(error.getDescription());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse resp) {
                if (!request.isForMainFrame()) {
                    return;
                }
                int code = resp.getStatusCode();
                if (code == 401) {
                    // 面板自己的 401 提示页可读：弹设置框让用户填密钥，不盖错误层
                    if (!settingsShown && !isFinishing() && !isDestroyed()) {
                        settingsShown = true;
                        main.post(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                showSettings();
                            }
                        });
                    }
                    return;
                }
                if (code >= 400) {
                    loadFailed = true;
                    showError("HTTP " + code);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            // 面板无需摄像头/麦克风等资源权限；默认实现会拒绝所有 PermissionRequest，正合适。
            // 剪贴板（复制按钮）由用户手势触发，WebView 自动放行，无需在此授权。

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // window.open 的落点：用临时 WebView 截获首个导航，按 URL 路由后销毁。
                // 只保留一个临时窗口；销毁幂等，且有 15 秒兜底（防 window.open 无首跳导致泄漏）。
                if (popupDispose != null) {
                    popupDispose.run();
                }
                final WebView p = new WebView(MainActivity.this);
                final AtomicBoolean disposed = new AtomicBoolean(false);
                final Runnable dispose = () -> {
                    if (disposed.compareAndSet(false, true)) {
                        main.post(() -> {
                            if (!p.isDestroyed()) {
                                p.destroy();
                            }
                        });
                    }
                };
                p.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        Uri u = request.getUrl();
                        v.stopLoading();
                        if (u != null && isPanelOrigin(u)) {
                            web.loadUrl(u.toString());
                        } else if (u != null && isHttpUrl(u)) {
                            openExternal(u);
                        }
                        dispose.run();
                        return true;
                    }
                });
                popupDispose = dispose;
                main.postDelayed(dispose, 15000);
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(p);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    return false;
                }
            }
        });

        web.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                startDownload(url, contentDisposition, mimeType));
    }

    /** 面板同源的导航留在 WebView；http/https 外链交给系统浏览器；其余 scheme 一律拦截。 */
    private boolean handleUrl(Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            // 无法辨识的导航一律拦下，不放行到 WebView
            return true;
        }
        if (!isHttpUrl(uri)) {
            // 只放行 http/https；file:/intent:/javascript: 等其他 scheme 一律拦下不处理
            return true;
        }
        if (isPanelOrigin(uri)) {
            return false;
        }
        openExternal(uri);
        return true;
    }

    private static boolean isHttpUrl(Uri uri) {
        String scheme = uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException e) {
            toast(getString(R.string.no_app_to_open));
        }
    }

    private boolean isPanelOrigin(Uri uri) {
        Uri base = Uri.parse(normalizedBase());
        String bs = base.getScheme(), us = uri.getScheme();
        String bh = base.getHost(), uh = uri.getHost();
        if (bs == null || us == null || bh == null || uh == null) {
            return false;
        }
        return bs.equalsIgnoreCase(us) && bh.equalsIgnoreCase(uh) && effectivePort(base) == effectivePort(uri);
    }

    private static int effectivePort(Uri u) {
        int p = u.getPort();
        if (p != -1) {
            return p;
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    private static boolean isFatalError(int code) {
        switch (code) {
            case WebViewClient.ERROR_CONNECT:
            case WebViewClient.ERROR_TIMEOUT:
            case WebViewClient.ERROR_HOST_LOOKUP:
            case WebViewClient.ERROR_IO:
            case WebViewClient.ERROR_PROXY_AUTHENTICATION:
                return true;
            default:
                return false;
        }
    }

    // ---- 设置与加载 ----

    private String normalizedBase() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_URL, DEFAULT_URL);
        if (raw == null || raw.trim().isEmpty()) {
            raw = DEFAULT_URL;
        }
        raw = raw.trim();
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://" + raw;
        }
        Uri u = Uri.parse(raw);
        if (u.getHost() == null) {
            return DEFAULT_URL;
        }
        return u.buildUpon().clearQuery().clearFragment().build().toString();
    }

    private void loadPanel() {
        overlay.setVisibility(View.GONE);
        pendingClearHistory = true;
        String key = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_KEY, "");
        String url = normalizedBase();
        if (key != null && !key.isEmpty()) {
            url = url + "?k=" + Uri.encode(key);
        }
        web.loadUrl(url);
    }

    private void showError(CharSequence detail) {
        String msg = getString(R.string.err_hint);
        if (detail != null && detail.length() > 0) {
            msg = msg + "\n\n[" + detail + "]";
        }
        errDetail.setText(msg);
        overlay.setVisibility(View.VISIBLE);
    }

    private void showSettings() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (settingsDialog != null && settingsDialog.isShowing()) {
            return;
        }
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null, false);
        EditText urlEt = v.findViewById(R.id.set_url);
        EditText keyEt = v.findViewById(R.id.set_key);
        urlEt.setText(normalizedBase());
        keyEt.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_KEY, ""));

        settingsDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dlg_title)
                .setView(v)
                .setPositiveButton(R.string.save_connect, (d, w) -> {
                    String url = urlEt.getText().toString().trim();
                    String key = keyEt.getText().toString().trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        toast(getString(R.string.bad_url));
                        return;
                    }
                    Uri parsed = Uri.parse(url);
                    if (parsed.getHost() == null) {
                        toast(getString(R.string.bad_url));
                        return;
                    }
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putString(PREF_URL, url)
                            .putString(PREF_KEY, key)
                            .apply();
                    loadPanel();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        settingsDialog.show();
    }

    // ---- 应用内下载（MediaStore.Downloads，无需任何存储权限） ----

    private void startDownload(final String url, String contentDisposition, String fallbackMime) {
        toast(getString(R.string.toast_downloading));
        final String cookie = CookieManager.getInstance().getCookie(url);
        dl.execute(() -> {
            boolean ok = false;
            Uri out = null;
            String name = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                if (cookie != null && !cookie.isEmpty()) {
                    c.setRequestProperty("Cookie", cookie);
                }
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                String serverCd = c.getHeaderField("Content-Disposition");
                String cd = (serverCd != null && !serverCd.isEmpty()) ? serverCd : contentDisposition;
                String m0 = c.getContentType();
                if (m0 == null || m0.isEmpty()) {
                    m0 = fallbackMime;
                }
                String m = (m0 == null || m0.isEmpty())
                        ? "application/octet-stream"
                        : m0.split(";")[0].trim();
                if (m.isEmpty()) {
                    m = "application/octet-stream";
                }
                name = URLUtil.guessFileName(url, cd, m);
                if (name == null || name.trim().isEmpty()) {
                    name = "magpie-download";
                }

                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, m);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                out = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (out == null) {
                    throw new IOException("MediaStore insert failed");
                }

                try (InputStream in = c.getInputStream();
                     OutputStream os = getContentResolver().openOutputStream(out)) {
                    if (os == null) {
                        throw new IOException("openOutputStream failed");
                    }
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                    }
                }
                ContentValues doneCv = new ContentValues();
                doneCv.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(out, doneCv, null, null);
                ok = true;
            } catch (Exception e) {
                final String msg = e.getMessage();
                main.post(() -> toast(getString(R.string.toast_dl_fail) + (msg == null ? "" : " (" + msg + ")")));
            } finally {
                if (c != null) {
                    c.disconnect();
                }
                if (!ok && out != null) {
                    try {
                        getContentResolver().delete(out, null, null);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (ok) {
                final String done = name;
                main.post(() -> toast(getString(R.string.toast_saved) + " " + done));
            }
        });
    }

    // ---- 生命周期 ----

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        web.onPause();
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (popupDispose != null) {
            popupDispose.run();
            popupDispose = null;
        }
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.dismiss();
        }
        if (fileCallback != null) {
            fileCallback.onReceiveValue(null);
            fileCallback = null;
        }
        dl.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
