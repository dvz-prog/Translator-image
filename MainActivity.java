package br.tradutorimagens.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String HOME = "https://www.google.com";

    private static final String[] TARGET_LABELS = {
            "Português", "Inglês", "Espanhol", "Francês", "Alemão", "Italiano", "Russo", "Japonês", "Coreano", "Chinês"};
    private static final String[] TARGET_CODES = {
            "pt", "en", "es", "fr", "de", "it", "ru", "ja", "ko", "zh-CN"};
    private static final String[] SOURCE_LABELS = {
            "Detectar automaticamente", "Japonês", "Coreano", "Chinês", "Alfabeto latino (inglês, espanhol…)"};
    private static final String[] SOURCE_CODES = {"auto", "ja", "ko", "zh", "latin"};

    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private TranslatorEngine engine;
    private String pitJs = "";
    private String userAgent = "";
    private boolean injectedEarly = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("pit", MODE_PRIVATE);
        engine = new TranslatorEngine();
        pitJs = readAsset("pit.js");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor("#1C1B1F"));
        bar.setPadding(dp(6), dp(6), dp(6), dp(6));

        Button back = barButton("◀");
        back.setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
        });

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(Color.parseColor("#A7A4B0"));
        urlBar.setHint("Digite um endereço ou pesquise");
        urlBar.setTextSize(14);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            go(urlBar.getText().toString());
            return true;
        });

        Button menuBtn = barButton("⋮");
        menuBtn.setOnClickListener(this::showMenu);

        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        bar.addView(urlBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(menuBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setLoadWithOverviewMode(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setMediaPlaybackRequiresUserGesture(true);
        userAgent = ws.getUserAgentString();

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "PitBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return scheme == null || !(scheme.equals("http") || scheme.equals("https"));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                urlBar.setText(url);
                injectedEarly = false;
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                urlBar.setText(url);
                progress.setVisibility(View.GONE);
                inject();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                // injeta cedo para as imagens começarem a ser traduzidas sem esperar a página terminar
                if (newProgress >= 70 && !injectedEarly) {
                    injectedEarly = true;
                    inject();
                }
            }
        });

        web.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = web.getHitTestResult();
            int type = hit.getType();
            final String src = hit.getExtra();
            if (src != null && (type == WebView.HitTestResult.IMAGE_TYPE
                    || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                new AlertDialog.Builder(MainActivity.this)
                        .setItems(new String[]{"Traduzir esta imagem"}, (d, which) ->
                                runJs("window.__pitCmd&&window.__pitCmd('translateSrc'," + JSONObject.quote(src) + ")"))
                        .show();
                return true;
            }
            return false;
        });

        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        Uri data = getIntent() != null ? getIntent().getData() : null;
        web.loadUrl(data != null ? data.toString() : HOME);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri data = intent != null ? intent.getData() : null;
        if (data != null) web.loadUrl(data.toString());
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------ UI

    private Button barButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void go(String input) {
        String text = input.trim();
        if (text.isEmpty()) return;
        String url;
        if (text.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            url = text;
        } else if (!text.contains(" ") && text.contains(".")) {
            url = "https://" + text;
        } else {
            url = "https://www.google.com/search?q=" + Uri.encode(text);
        }
        web.loadUrl(url);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        web.requestFocus();
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        MenuItem auto = m.add(0, 1, 0, "Traduzir automaticamente neste site");
        auto.setCheckable(true);
        auto.setChecked(isAuto(currentHost()));
        m.add(0, 2, 1, "Traduzir imagens desta página");
        m.add(0, 3, 2, "Alternar original / tradução");
        m.add(0, 4, 3, "Remover traduções");
        m.add(0, 5, 4, "Traduzir para…");
        m.add(0, 6, 5, "Idioma do texto na imagem…");
        m.add(0, 7, 6, "Recarregar");

        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    toggleAuto();
                    return true;
                case 2:
                    runJs("window.__pitCmd&&window.__pitCmd('translateAll')");
                    return true;
                case 3:
                    runJs("window.__pitCmd&&window.__pitCmd('toggleAll')");
                    return true;
                case 4:
                    runJs("window.__pitCmd&&window.__pitCmd('clear')");
                    return true;
                case 5:
                    pick("Traduzir para", TARGET_LABELS, TARGET_CODES, "target", "pt");
                    return true;
                case 6:
                    pick("Idioma do texto na imagem", SOURCE_LABELS, SOURCE_CODES, "source", "auto");
                    return true;
                case 7:
                    web.reload();
                    return true;
                default:
                    return false;
            }
        });
        pm.show();
    }

    private void pick(String title, String[] labels, final String[] codes, final String key, String def) {
        String current = prefs.getString(key, def);
        int checked = 0;
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(current)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    prefs.edit().putString(key, codes[which]).apply();
                    d.dismiss();
                })
                .show();
    }

    // ------------------------------------------------------ preferências

    private String currentHost() {
        String url = web != null ? web.getUrl() : null;
        if (url == null) return "";
        String h = Uri.parse(url).getHost();
        return h == null ? "" : h;
    }

    private boolean isAuto(String host) {
        if (host.isEmpty()) return false;
        return prefs.getStringSet("autoHosts", new HashSet<String>()).contains(host);
    }

    private void toggleAuto() {
        String host = currentHost();
        if (host.isEmpty()) return;
        Set<String> hosts = new HashSet<>(prefs.getStringSet("autoHosts", new HashSet<String>()));
        boolean on;
        if (hosts.contains(host)) {
            hosts.remove(host);
            on = false;
        } else {
            hosts.add(host);
            on = true;
        }
        prefs.edit().putStringSet("autoHosts", hosts).apply();
        runJs("window.__pitCmd&&window.__pitCmd('auto'," + on + ")");
    }

    // ------------------------------------------------------------- JS

    private void inject() {
        if (pitJs.isEmpty()) return;
        web.evaluateJavascript(pitJs, null);
        runJs("window.__pitCmd&&window.__pitCmd('auto'," + isAuto(currentHost()) + ")");
    }

    private void runJs(String js) {
        web.evaluateJavascript(js, null);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void deliver(final String id, final String json, final String err) {
        runOnUiThread(() -> {
            if (web == null) return;
            if (json != null) {
                runJs("window.__pitDone&&window.__pitDone(" + JSONObject.quote(id) + "," + json + ")");
            } else {
                runJs("window.__pitFail&&window.__pitFail(" + JSONObject.quote(id) + ","
                        + JSONObject.quote(err == null ? "Erro" : err) + ")");
            }
        });
    }

    /** Ponte chamada pelo JavaScript da página (thread de fundo do WebView). */
    private final class Bridge {
        @JavascriptInterface
        public void translate(final String id, final String url, final String pageUrl) {
            final String cookie = CookieManager.getInstance().getCookie(url);
            final String target = prefs.getString("target", "pt");
            final String source = prefs.getString("source", "auto");
            engine.submit(() -> {
                try {
                    deliver(id, engine.translateUrl(url, pageUrl, cookie, userAgent, target, source), null);
                } catch (Throwable t) {
                    deliver(id, null, message(t));
                }
            });
        }

        @JavascriptInterface
        public void translateData(final String id, final String dataUrl) {
            final String target = prefs.getString("target", "pt");
            final String source = prefs.getString("source", "auto");
            engine.submit(() -> {
                try {
                    deliver(id, engine.translateData(dataUrl, target, source), null);
                } catch (Throwable t) {
                    deliver(id, null, message(t));
                }
            });
        }

        private String message(Throwable t) {
            String m = t.getMessage();
            return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
        }
    }
}
