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
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.ArrayList;
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

/** 背景監測版：由前景服務獨立監測 Uber 分享頁面文字。 */
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
    private long historyRevision = -1L;
    private boolean resumed = false;
    private boolean watching = false, active = false;
    private long startedAt = 0L;
    private String activeSource = "UBER", lastObserved = "";
    private long pendingLocationId = -1L;

    static String statusScript() { return POLL_SCRIPT; }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            syncMonitorState();
            handler.postDelayed(this, 1000L);
        }
    };

    private void syncMonitorState() {
        active = settings.getBoolean("active", false);
        startedAt = settings.getLong("started_at", 0L);
        activeSource = settings.getString("active_source", "UBER");
        lastObserved = settings.getString("observed", "");
        watching = settings.getBoolean("watching", false);
        long heartbeat = settings.getLong("monitor_heartbeat", 0L);
        boolean healthy = heartbeat > 0 && SystemClock.elapsedRealtime() >= heartbeat
                && SystemClock.elapsedRealtime() - heartbeat < 20000L;
        watcherView.setText(watching ? (healthy ? "● 背景服務執行中，可切換司機端" : "● 背景服務暫無回應，請重新開始監測") : "監測已暫停");
        String detected = settings.getString("detected", "UNKNOWN");
        statusTextView.setText("TRIP".equals(detected) ? "狀態文字：正在途中"
                : "ONLINE".equals(detected) ? "狀態文字：上線中" : "狀態文字：等待確認");
        debugView.setText(settings.getString("monitor_message", "請貼上 Uber 分享網址後按開始背景監測。"));
        long rev = settings.getLong("history_revision", 0L);
        if (historyRevision != rev) { historyRevision = rev; refreshHistory(); }
        refreshLive();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(tick);
        super.onPause();
    }

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

        refreshHistory();
        refreshLive();
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
        Button begin = btn("開始背景監測", GREEN);
        Button stop = btn("暫停監測", DARK);
        begin.setOnClickListener(v -> beginWatching());
        stop.setOnClickListener(v -> stopWatching());
        addButtons(observe, begin, stop);
        watcherView = txt("等待 Uber 分享網址", 13, GREEN, true);
        add(observe, watcherView, 12);
        statusTextView = txt("狀態文字：等待讀取", 20, DARK, true);
        add(observe, statusTextView, 10);
        debugView = txt("開始後可切換司機端；下拉通知欄可查看監測狀態或停止。", 12, MUTED, false);
        add(observe, debugView, 4);
        add(observe, txt("開始監測後可切到司機端，不必停留本畫面。請允許通知及背景執行；勿強制停止本 App。分享頁載入失敗時會重試，不會誤判送達。", 12, Color.rgb(158, 106, 29), false), 10);
        Button browser = btn("瀏覽器檢查分享頁", MUTED);
        browser.setOnClickListener(v -> {
            String value = urlInput.getText().toString().trim();
            if (isUberUrl(Uri.parse(value))) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value))); }
                catch (Exception e) { toast("無法開啟瀏覽器"); }
            }
        });
        Button background = btn("背景執行設定", MUTED);
        background.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (Exception e) { toast("請到手機設定允許本 App 背景執行"); }
        });
        addButtons(observe, browser, background);
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
        if (!isUberUrl(Uri.parse(url))) { toast("請使用有效的 HTTPS Uber 分享網址"); return; }
        try {
            Intent intent = new Intent(this, MonitorService.class).setAction(MonitorService.START)
                    .putExtra("url", url).putExtra("activity_visible", true);
            startForegroundService(intent);
            ensureLocationPermission();
            debugView.setText("正在啟動背景服務，可從通知欄查看狀態…");
        } catch (Exception e) { toast("背景服務無法啟動，請允許背景執行後重試"); }
    }

    private void stopWatching() {
        settings.edit().putBoolean("watching", false).apply();
        stopService(new Intent(this, MonitorService.class));
        syncMonitorState();
    }

    private void startDelivery(String source) {
        syncMonitorState();
        if (active) { toast("已有一筆配送正在計時，請先完成配送"); return; }
        startedAt = System.currentTimeMillis();
        activeSource = source;
        active = true;
        settings.edit().putBoolean("active", true).putLong("started_at", startedAt)
                .putString("active_source", activeSource).putLong("started_elapsed", SystemClock.elapsedRealtime()).apply();
        ensureLocationPermission();
        refreshLive();
        toast("開始計時：" + when.format(new Date(startedAt)));
    }

    private void finishDelivery(boolean manuallyEnded) {
        syncMonitorState();
        if (!active) { toast("目前沒有配送在計時"); return; }
        // 先把時間固定，再查 GPS；定位所花時間不計入配送。
        long startedElapsed = settings.getLong("started_elapsed", -1L);
        long endedAt = startedElapsed >= 0L && SystemClock.elapsedRealtime() >= startedElapsed
                ? startedAt + SystemClock.elapsedRealtime() - startedElapsed : System.currentTimeMillis();
        String source = activeSource + (manuallyEnded ? "／手動完成" : "");
        long id = store.save(startedAt, endedAt, source);
        active = false;
        startedAt = 0L;
        settings.edit().putBoolean("active", false).remove("started_at").remove("started_elapsed").apply();
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
        if (requestCode == 101 && settings.getBoolean("watching", false) && resumed) {
            try { startService(new Intent(this, MonitorService.class).setAction(MonitorService.UPDATE_LOCATION).putExtra("activity_visible", true)); } catch (Exception ignored) { }
        }
        if (requestCode == 101 && pendingLocationId >= 0L) {
            long id = pendingLocationId;
            pendingLocationId = -1L;
            AddressResolver.capture(this, store, id, () -> runOnUiThread(this::refreshHistory));
        }
    }

    private void ensureLocationPermission() {
        ArrayList<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), 101);
    }

    private void refreshLive() {
        if (timerView == null) return;
        if (active) {
            long base = settings.getLong("started_elapsed", -1L);
            long elapsed = base >= 0 && SystemClock.elapsedRealtime() >= base
                    ? SystemClock.elapsedRealtime() - base : Math.max(0L, System.currentTimeMillis() - startedAt);
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

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (store != null) store.close();
        super.onDestroy();
    }
}
