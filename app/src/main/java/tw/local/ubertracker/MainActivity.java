package tw.local.ubertracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 原生測試版：App 保持在前景時，以 WebView 讀取 Uber 分享頁面文字。 */
public final class MainActivity extends Activity {
    private static final int DARK = Color.rgb(16, 35, 50);
    private static final int GREEN = Color.rgb(8, 123, 101);
    private static final int MUTED = Color.rgb(90, 111, 125);
    private static final int BG = Color.rgb(244, 247, 247);
    private static final String POLL_SCRIPT = "(function(){try{ function state(text){ var t=(text||'').replace(/[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]/g,'').trim().replace(/\\s+/g,' '); var compact=t.replace(/\\s/g,''); if(compact==='上線中')return 'ONLINE'; if(compact==='正在途中')return 'TRIP'; var m=t.match(/^(?:[\\u3400-\\u9FFFA-Za-z0-9·・._-]{1,24})\\s+(上線中|正在途中)$/); return m?(m[1]==='上線中'?'ONLINE':'TRIP'):null; } function visible(e){ if(!e||!e.getClientRects().length)return false; for(var a=e;a;a=a.parentElement){ if(/^(SCRIPT|STYLE|NOSCRIPT|TEMPLATE|TEXTAREA|OPTION|SVG|CANVAS)$/.test(a.tagName))return false; if(a.hidden||a.getAttribute('aria-hidden')==='true'||a.hasAttribute('inert'))return false; var css=getComputedStyle(a); if(css.display==='none'||css.visibility==='hidden'||css.visibility==='collapse'||Number(css.opacity)===0)return false; } return true; } if(!document.body)return 'UNKNOWN'; var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),node,online=false,trip=false; while((node=walker.nextNode())){ var el=node.parentElement; if(!visible(el))continue; var value=state(node.nodeValue); /* Join split inline status text only within a compact local element. */ if(!value&&el.childElementCount<=4&&el.textContent.length<=64)value=state(el.textContent); if(value==='ONLINE')online=true; if(value==='TRIP')trip=true; } return trip&&online?'AMBIGUOUS':trip?'TRIP':online?'ONLINE':'UNKNOWN'; }catch(e){return 'UNKNOWN';}})()";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat when = new SimpleDateFormat("MM/dd HH:mm:ss", Locale.TAIWAN);
    private DeliveryStore store;
    private SharedPreferences settings;
    private TextView stateView, timerView, liveMoneyView, todayView, todayCountView, debugView, watcherView, statusTextView;
    private EditText urlInput;
    private LinearLayout historyBox;
    private WebView web;
    private boolean watching = false, webLoaded = false, active = false;
    private long startedAt = 0L, lastReloadAt = 0L;
    private String activeSource = "UBER", lastObserved = "", stableCandidate = "";
    private int stableCount = 0;
    private boolean pollingNow = false;
    private long pendingLocationId = -1L;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshLive();
            if (watching && webLoaded && web != null && !pollingNow) inspectPage();
            if (watching && webLoaded && web != null && System.currentTimeMillis() - lastReloadAt > 45000L) {
                lastReloadAt = System.currentTimeMillis();
                web.reload();
            }
            handler.postDelayed(this, 2000L);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);
        store = new DeliveryStore(this);
        settings = getSharedPreferences("tracker", MODE_PRIVATE);
        active = settings.getBoolean("active", false);
        startedAt = settings.getLong("started_at", 0L);
        activeSource = settings.getString("active_source", "UBER");
        if (active && startedAt <= 0L) active = false;
        buildUi();
        acceptSharedLink(getIntent());
        // Activity 被 Android 重新建立時，恢復上次未暫停的網頁監測。
        if (!watching && settings.getBoolean("watching", false)
                && !urlInput.getText().toString().trim().isEmpty()) beginWatching();
        refreshHistory();
        refreshLive();
        handler.post(tick);
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptSharedLink(intent);
    }

    /** 從 Android「分享 → 外送計時收入」取得網址後，立即載入並啟動監測。 */
    private void acceptSharedLink(Intent intent) {
        if (intent == null) return;
        String sharedText = null;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            CharSequence extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (extra != null) sharedText = extra.toString();
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            sharedText = intent.getData().toString();
        }
        if (sharedText == null) return;
        String url = UberShareLink.extract(sharedText);
        if (url == null) { toast("分享內容沒有有效的 Uber 行程網址"); return; }
        urlInput.setText(url);
        beginWatching();
        toast("已接收 Uber 網址，開始偵測『上線中／正在途中』");
    }

    private int dp(float px) { return (int)(getResources().getDisplayMetrics().density * px + 0.5f); }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable heroBackground() {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(16, 35, 50), Color.rgb(20, 51, 66), Color.rgb(20, 92, 81)});
        drawable.setCornerRadius(dp(24));
        return drawable;
    }

    private GradientDrawable outlinedBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.WHITE);
        drawable.setCornerRadius(dp(14));
        drawable.setStroke(dp(1), Color.rgb(223, 233, 233));
        return drawable;
    }

    private TextView txt(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        v.setLineSpacing(dp(2), 1.0f);
        return v;
    }

    private Button btn(String label, int bgColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setPadding(dp(8), dp(2), dp(8), dp(2));
        b.setBackground(bg(bgColor, 12));
        return b;
    }

    private void add(LinearLayout root, View view, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        root.addView(view, lp);
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(bg(Color.WHITE, 22));
        v.setPadding(dp(16), dp(15), dp(16), dp(15));
        v.setElevation(dp(2));
        return v;
    }

    private void addButtons(LinearLayout parent, Button... buttons) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b : buttons) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
            p.setMargins(dp(3), dp(3), dp(3), dp(3));
            r.addView(b, p);
        }
        add(parent, r, 8);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(38));
        scroll.addView(root);
        setContentView(scroll);

        // Home overview: today's confirmed deliveries, distinct from test/simulation data.
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(21), dp(20), dp(21), dp(23));
        hero.setBackground(heroBackground());
        add(hero, txt("↗  外送計時收入", 23, Color.WHITE, true), 0);
        add(hero, txt("DELIVERY TRACKER · Android v0.5", 11, Color.rgb(167, 194, 201), false), 4);
        add(hero, txt("今日預估收入", 13, Color.rgb(190, 215, 219), false), 27);
        todayView = txt("NT$0.00", 38, Color.WHITE, true);
        add(hero, todayView, 6);
        add(hero, txt("依自訂費率計算 · 不含模擬與測試單", 11, Color.rgb(153, 185, 190), false), 4);
        todayCountView = txt("今日完成 0 筆", 15, Color.rgb(116, 238, 201), true);
        add(hero, todayCountView, 17);
        add(root, hero, 0);

        LinearLayout live = card();
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = txt("目前配送狀態", 14, MUTED, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        stateView = txt("等待接單", 13, GREEN, true);
        stateView.setBackground(bg(Color.rgb(233, 248, 242), 18));
        stateView.setPadding(dp(12), dp(6), dp(12), dp(6));
        titleRow.addView(stateView);
        add(live, titleRow, 0);
        timerView = txt("00:00:00", 39, DARK, true);
        timerView.setLetterSpacing(-0.035f);
        add(live, timerView, 17);
        add(live, txt("目前預估金額", 12, MUTED, false), 13);
        liveMoneyView = txt("NT$0.00", 28, GREEN, true);
        add(live, liveMoneyView, 3);
        add(live, txt("前 11 分鐘固定 NT$45 · 超時每分鐘 +NT$4.10 · 餘秒達 30 秒進位", 12, MUTED, false), 16);
        add(root, live, 14);

        LinearLayout observe = card();
        add(observe, txt("⤴  Uber 分享網址", 18, DARK, true), 0);
        add(observe, txt("僅比對狀態列的『上線中／正在途中』，忽略地圖、姓名、車牌與地址", 12, MUTED, false), 6);
        urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setTextSize(13);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(settings.getString("url", ""));
        urlInput.setHint("貼上 Uber 分享網址（trip.uber.com 或 m.uber.com）");
        urlInput.setSelectAllOnFocus(true);
        urlInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        urlInput.setBackground(outlinedBackground());
        add(observe, urlInput, 17);
        Button begin = btn("開始監測", GREEN);
        Button stop = btn("暫停監測", DARK);
        begin.setOnClickListener(v -> beginWatching());
        stop.setOnClickListener(v -> stopWatching());
        addButtons(observe, begin, stop);
        watcherView = txt("等待 Uber 分享網址", 13, GREEN, true);
        add(observe, watcherView, 12);
        statusTextView = txt("狀態文字：等待讀取", 20, DARK, true);
        add(observe, statusTextView, 10);
        debugView = txt("請從 Uber App 分享行程連結到本 App；首次測試須確認網頁正常載入。", 12, MUTED, false);
        add(observe, debugView, 4);
        add(observe, txt("注意：收到連結不等於已接單。只有確實偵測到『正在途中』才開始計時。須保持 App 前景且螢幕開啟；Uber 可能限制內嵌讀取。", 12, Color.rgb(158, 106, 29), false), 10);
        web = new WebView(this);
        WebSettings w = web.getSettings();
        w.setJavaScriptEnabled(true);
        w.setDomStorageEnabled(true);
        w.setLoadsImagesAutomatically(true);
        w.setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                webLoaded = true;
                lastReloadAt = System.currentTimeMillis();
                debugView.setText("頁面已載入；正在尋找『上線中／正在途中』文字…");
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    webLoaded = false;
                    debugView.setText("Uber 頁面無法載入：可能連結失效、網路異常或 Uber 限制內嵌瀏覽器。");
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (isUberUrl(uri)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) { }
                return true;
            }
        });
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(295));
        wp.topMargin = dp(14);
        observe.addView(web, wp);
        add(root, observe, 14);

        LinearLayout recent = card();
        add(recent, txt("▤  最近配送紀錄", 18, DARK, true), 0);
        add(recent, txt("含結束時間、計費分鐘、收入與完整地址", 12, MUTED, false), 5);
        Button csv = btn("匯出 CSV 紀錄", GREEN);
        csv.setOnClickListener(v -> exportCsv());
        add(recent, csv, 13);
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        add(recent, historyBox, 10);
        add(root, recent, 14);

        LinearLayout demo = card();
        add(demo, txt("立即試用／手動備援", 18, DARK, true), 0);
        add(demo, txt("驗證計時、收入與 GPS 地址。模擬訂單不會算入今日正式收入。", 12, MUTED, false), 6);
        Button manualStart = btn("模擬接單", GREEN);
        Button manualEnd = btn("結束配送", DARK);
        manualStart.setOnClickListener(v -> startDelivery("模擬／手動"));
        manualEnd.setOnClickListener(v -> finishDelivery(true));
        addButtons(demo, manualStart, manualEnd);
        Button sample = btn("10分30秒測試（NT$45.00）", Color.rgb(49, 99, 111));
        sample.setOnClickListener(v -> insertSample(10, 30));
        add(demo, sample, 10);
        Button sampleExtra = btn("11分30秒測試（NT$49.10）", Color.rgb(49, 99, 111));
        sampleExtra.setOnClickListener(v -> insertSample(11, 30));
        add(demo, sampleExtra, 7);
        add(root, demo, 14);
        add(root, txt("本程式非 Uber 官方 App。預估費用依自訂規則計算，資料僅存手機本機。GPS 地址可能沒有門牌，可手動修正。", 12, MUTED, false), 18);
    }

    private boolean isUberUrl(Uri uri) {
        String host = uri == null ? null : uri.getHost();
        return uri != null && "https".equalsIgnoreCase(uri.getScheme()) &&
                ("trip.uber.com".equalsIgnoreCase(host) || "m.uber.com".equalsIgnoreCase(host));
    }

    private void beginWatching() {
        String url = urlInput.getText().toString().trim();
        Uri uri;
        try { uri = Uri.parse(url); } catch (Exception e) { toast("網址格式不正確"); return; }
        if (!isUberUrl(uri)) { toast("請使用 trip.uber.com 或 m.uber.com 的 HTTPS 分享網址"); return; }
        settings.edit().putString("url", url).putBoolean("watching", true).apply();
        watching = true;
        webLoaded = false;
        pollingNow = false;
        stableCandidate = "";
        stableCount = 0;
        lastObserved = "";
        lastReloadAt = System.currentTimeMillis();
        web.loadUrl(url);
        watcherView.setText("● 正在監測（需保持畫面開啟）");
        statusTextView.setText("狀態文字：等待讀取");
        debugView.setText("正在載入 Uber 分享頁面…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ensureLocationPermission();
    }

    private void stopWatching() {
        watching = false;
        settings.edit().putBoolean("watching", false).apply();
        webLoaded = false;
        watcherView.setText("監測已暫停");
        debugView.setText(active ? "計時仍在繼續；可按『結束配送』手動結算。" : "尚未監測；可再按開始監測。");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void inspectPage() {
        pollingNow = true;
        try {
            web.evaluateJavascript(POLL_SCRIPT, result -> {
                pollingNow = false;
                if (!watching) return;
                String value = result == null ? "UNKNOWN" : result.replace("\"", "").trim();
                statusTextView.setText("ONLINE".equals(value) ? "狀態文字：上線中"
                        : "TRIP".equals(value) ? "狀態文字：正在途中" : "狀態文字：暫時無法確認");
                if ("ONLINE".equals(value) || "TRIP".equals(value)) {
                    if (stableCandidate.equals(value)) stableCount++;
                    else { stableCandidate = value; stableCount = 1; }
                    if (stableCount >= 2 && !lastObserved.equals(value)) {
                        lastObserved = value;
                        processStatus(value);
                    }
                } else {
                    stableCandidate = "";
                    stableCount = 0;
                    debugView.setText("AMBIGUOUS".equals(value)
                            ? "同一頁面同時出現『上線中／正在途中』，狀態不明；本次不變更計時。"
                            : "找不到指定狀態文字。WebView 可能尚未載入，或 Uber 已更改頁面。計時不會因此自動結束。");
                }
            });
        } catch (Exception e) {
            pollingNow = false;
            debugView.setText("網頁文字讀取失敗，請使用模擬測試。");
        }
    }

    private void processStatus(String status) {
        if ("TRIP".equals(status)) {
            debugView.setText("已連續偵測到『正在途中』，時間：" + when.format(new Date()));
            if (!active) startDelivery("UBER 網頁偵測");
        } else {
            debugView.setText("已連續偵測到『上線中』，時間：" + when.format(new Date()));
            if (active && "UBER 網頁偵測".equals(activeSource)) finishDelivery(false);
        }
        refreshLive();
    }

    private void startDelivery(String source) {
        if (active) { toast("已有一筆配送正在計時，請先完成配送"); return; }
        startedAt = System.currentTimeMillis();
        activeSource = source;
        active = true;
        settings.edit().putBoolean("active", true).putLong("started_at", startedAt)
                .putString("active_source", activeSource).apply();
        ensureLocationPermission();
        refreshLive();
        toast("開始計時：" + when.format(new Date(startedAt)));
    }

    private void finishDelivery(boolean manuallyEnded) {
        if (!active) { toast("目前沒有配送在計時"); return; }
        // 先把時間固定，再查 GPS；定位所花時間不計入配送。
        long endedAt = System.currentTimeMillis();
        String source = activeSource + (manuallyEnded ? "／手動完成" : "");
        long id = store.save(startedAt, endedAt, source);
        active = false;
        startedAt = 0L;
        settings.edit().putBoolean("active", false).remove("started_at").apply();
        refreshLive();
        refreshHistory();
        captureEndLocation(id);
        toast("已儲存配送結算；正在取得結束地址");
    }

    private void insertSample(int mins, int secs) {
        long end = System.currentTimeMillis();
        long start = end - ((mins * 60L) + secs) * 1000L;
        long id = store.save(start, end, "測試範例（非真實訂單）");
        refreshHistory();
        captureEndLocation(id);
        toast("測試紀錄：" + mins + "分" + secs + "秒，" +
                FareCalculator.money(FareCalculator.amountCents(FareCalculator.billedMinutes(end - start))));
    }

    private void captureEndLocation(long id) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            AddressResolver.capture(this, store, id, () -> runOnUiThread(this::refreshHistory));
        } else {
            pendingLocationId = id;
            ensureLocationPermission();
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == 101 && pendingLocationId >= 0L) {
            long id = pendingLocationId;
            pendingLocationId = -1L;
            AddressResolver.capture(this, store, id, () -> runOnUiThread(this::refreshHistory));
        }
    }

    private void ensureLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 101);
        }
    }

    private void refreshLive() {
        if (timerView == null) return;
        if (active) {
            long elapsed = Math.max(0L, System.currentTimeMillis() - startedAt);
            long seconds = elapsed / 1000;
            timerView.setText(String.format(Locale.TAIWAN, "%02d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60));
            liveMoneyView.setText(FareCalculator.money(FareCalculator.amountCents(FareCalculator.billedMinutes(elapsed))));
            stateView.setText(activeSource.contains("UBER") ? "● 配送中" : "● 模擬配送中");
        } else {
            timerView.setText("00:00:00");
            liveMoneyView.setText("NT$0.00");
            stateView.setText("ONLINE".equals(lastObserved) ? "● 上線中" : "● 等待接單");
        }
    }

    private void refreshHistory() {
        if (historyBox == null) return;
        historyBox.removeAllViews();
        long[] today = store.todayTotal();
        todayView.setText(FareCalculator.money(today[1]));
        todayCountView.setText("今日完成 " + today[0] + " 筆");
        List<DeliveryStore.Delivery> rows = store.all();
        if (rows.isEmpty()) {
            add(historyBox, txt("尚無紀錄，可先點下方的測試單按鈕。", 13, MUTED, false), 4);
            return;
        }
        for (DeliveryStore.Delivery d : rows) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(0), dp(12), dp(0), dp(12));
            String name = "#" + d.id + "   " + FareCalculator.money(d.amountCents);
            add(item, txt(name, 17, GREEN, true), 0);
            add(item, txt("起 " + when.format(new Date(d.startMs)) + "  →  迄 " + when.format(new Date(d.endMs)), 12, DARK, false), 4);
            add(item, txt("實際 " + (d.durationMs / 1000) / 60 + "分" + (d.durationMs / 1000) % 60 + "秒／計費 " + d.minutes + " 分鐘", 12, MUTED, false), 4);
            add(item, txt("結束地址：" + d.address, 13, DARK, false), 8);
            if (d.latitude != null && d.longitude != null) {
                add(item, txt(String.format(Locale.US, "GPS：%.6f, %.6f · 精度 %s 公尺", d.latitude, d.longitude, d.accuracy == null ? "未知" : String.format(Locale.US, "%.0f", d.accuracy)), 11, MUTED, false), 4);
            }
            add(item, txt("來源：" + d.source, 11, MUTED, false), 4);
            Button edit = btn("修改地址", MUTED);
            edit.setOnClickListener(v -> editAddress(d));
            Button delete = btn("刪除這筆", Color.rgb(160, 70, 67));
            delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("刪除配送紀錄 #" + d.id)
                    .setMessage("確定永久刪除這筆紀錄？這也會從今日合計及 CSV 匯出中移除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("刪除", (dialog, which) -> {
                        store.delete(d.id);
                        refreshHistory();
                    }).show());
            if (d.latitude != null && d.longitude != null) {
                Button map = btn("地圖位置", GREEN);
                map.setOnClickListener(v -> openMap(d));
                addButtons(item, edit, map, delete);
            } else addButtons(item, edit, delete);
            View line = new View(this);
            line.setBackgroundColor(Color.rgb(228, 232, 237));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
            p.topMargin = dp(10);
            item.addView(line, p);
            add(historyBox, item, 2);
        }
    }

    private void editAddress(DeliveryStore.Delivery d) {
        EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setText(d.address);
        input.setPadding(dp(18), dp(10), dp(18), dp(10));
        new AlertDialog.Builder(this).setTitle("修改配送結束完整地址")
            .setView(input).setNegativeButton("取消", null)
            .setPositiveButton("儲存", (dialog, which) -> {
                String address = input.getText().toString().trim();
                if (!address.isEmpty()) { store.setAddress(d.id, address); refreshHistory(); }
            }).show();
    }

    private void openMap(DeliveryStore.Delivery d) {
        String q = d.latitude + "," + d.longitude;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + q));
        try { startActivity(intent); } catch (Exception e) { toast("無法開啟地圖"); }
    }

    private void exportCsv() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, "外送收入紀錄.csv");
        startActivityForResult(intent, 200);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 200 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
                if (out == null) throw new Exception("檔案無法寫入");
                out.write(store.exportCsv().getBytes(StandardCharsets.UTF_8));
                toast("CSV 已匯出");
            } catch (Exception ex) { toast("匯出失敗：" + ex.getMessage()); }
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack() && watching) web.goBack();
        else super.onBackPressed();
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        if (store != null) store.close();
        super.onDestroy();
    }
}
