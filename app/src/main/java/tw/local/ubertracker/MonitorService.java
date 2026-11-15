package tw.local.ubertracker;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Monitoring is owned by this foreground service, never by an Activity. */
public final class MonitorService extends Service {
    public static final String START = "tw.local.ubertracker.START_MONITOR";
    public static final String STOP = "tw.local.ubertracker.STOP_MONITOR";
    public static final String UPDATE_LOCATION = "tw.local.ubertracker.UPDATE_LOCATION";
    private static final String CHANNEL = "delivery_monitor";
    private static final int NOTICE = 70;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StatusGate gate = new StatusGate();
    private SharedPreferences settings;
    private static DeliveryStore processStore;
    private DeliveryStore store;
    private WebView web;
    private PowerManager.WakeLock wake;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private String url = "", message = "正在啟動背景監測…";
    private boolean running, loaded, loading, failed, polling, locationForeground;
    private long loadAt, reloadAt, nextRetry, notifiedAt;
    private int errors, generation;

    @Override public void onCreate() {
        super.onCreate();
        settings = getSharedPreferences("tracker", MODE_PRIVATE);
        if (processStore == null) processStore = new DeliveryStore(getApplicationContext());
        store = processStore;
        NotificationManager notices = getSystemService(NotificationManager.class);
        notices.createNotificationChannel(new NotificationChannel(CHANNEL, "外送背景監測", NotificationManager.IMPORTANCE_LOW));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":monitor");
        wake.setReferenceCounted(false);
        connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                handler.post(() -> { if (running && failed) nextRetry = 0L; });
            }
        };
        try { connectivity.registerDefaultNetworkCallback(networkCallback); } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? START : intent.getAction();
        if (STOP.equals(action)) { stopMonitoring(); return START_NOT_STICKY; }
        if (intent == null && !settings.getBoolean("watching", false)) { stopSelf(); return START_NOT_STICKY; }
        boolean visible = intent != null && intent.getBooleanExtra("activity_visible", false);
        // The location type is only added while our Activity is visible and permission is granted.
        if (visible && hasLocationPermission()) locationForeground = true;
        try { promote(); }
        catch (SecurityException ex) {
            locationForeground = false;
            try { promote(); } catch (Exception unavailable) {
                settings.edit().putBoolean("watching", false).putString("monitor_message", "背景服務權限不足，請回到 App 重新開始。").apply();
                stopSelf(); return START_NOT_STICKY;
            }
        }
        if (UPDATE_LOCATION.equals(action) && running) return START_STICKY;
        String requested = intent == null ? settings.getString("url", "") : intent.getStringExtra("url");
        if (requested == null) requested = settings.getString("url", "");
        if (UberShareLink.extract(requested) == null) { stopMonitoring(); return START_NOT_STICKY; }
        boolean changed = !url.equals(requested);
        url = requested;
        running = true;
        settings.edit().putBoolean("watching", true).putString("url", url).apply();
        if (web == null) createWebView();
        if (changed || (!loaded && !loading)) loadPage();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void promote() {
        Notification notice = notification();
        if (Build.VERSION.SDK_INT >= 34) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            if (locationForeground) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            startForeground(NOTICE, notice, types);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTICE, notice, locationForeground ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0);
        } else startForeground(NOTICE, notice);
    }

    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, MonitorService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean active = settings != null && settings.getBoolean("active", false);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(active ? "外送計時中 · 背景監測" : "外送背景監測")
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止監測", stop).build());
        if (active) b.setWhen(settings.getLong("started_at", System.currentTimeMillis())).setUsesChronometer(true);
        return b.build();
    }

    private void report(String text) {
        if (!message.equals(text)) {
            message = text;
            settings.edit().putString("monitor_message", text).apply();
            notifyNow();
        }
    }

    private void notifyNow() {
        try { getSystemService(NotificationManager.class).notify(NOTICE, notification()); } catch (SecurityException ignored) { }
        notifiedAt = SystemClock.elapsedRealtime();
    }

    private void createWebView() {
        WebView view = new WebView(getApplicationContext());
        web = view;
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setSupportMultipleWindows(false);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        // Give the off-screen page a real mobile layout for DOM text detection.
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 1920);
        view.onResume();
        view.resumeTimers();
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView current, String address, Bitmap icon) {
                if (current != web) return;
                generation++; polling = false; loaded = false; loading = true; failed = false;
                gate.resetCandidate(); loadAt = SystemClock.elapsedRealtime();
                settings.edit().putString("detected", "UNKNOWN").apply();
            }
            @Override public void onPageFinished(WebView current, String address) {
                if (current != web || !running || failed) return;
                loading = false; loaded = true; reloadAt = SystemClock.elapsedRealtime();
                report("背景監測中，正在確認上線中／正在途中；可切換司機端。");
            }
            @Override public void onReceivedError(WebView current, WebResourceRequest request, WebResourceError error) {
                if (current != web || !request.isForMainFrame()) return;
                pageFailure(error.getErrorCode() == ERROR_HOST_LOOKUP
                        ? "網頁網址解析失敗（DNS），請確認網路或重新分享有效連結；自動重試中。"
                        : "Uber 分享頁載入失敗，背景重試中；不會自動結算。");
            }
            @Override public boolean shouldOverrideUrlLoading(WebView current, WebResourceRequest request) {
                Uri u = request.getUrl();
                boolean allowed = "https".equalsIgnoreCase(u.getScheme()) && ("trip.uber.com".equalsIgnoreCase(u.getHost()) || "m.uber.com".equalsIgnoreCase(u.getHost()));
                if (!allowed && request.isForMainFrame()) pageFailure("分享頁要求其他網頁或登入，請回 App 檢查分享連結。");
                return !allowed;
            }
            @Override public boolean onRenderProcessGone(WebView current, RenderProcessGoneDetail detail) {
                if (current == web) {
                    web = null;
                    current.destroy();
                    pageFailure("網頁程序已停止，背景重新載入中。");
                }
                return true;
            }
        });
    }

    private void loadPage() {
        if (!running) return;
        if (web == null) createWebView();
        generation++; loaded = false; loading = true; failed = false; polling = false;
        gate.resetCandidate(); loadAt = SystemClock.elapsedRealtime(); reloadAt = loadAt;
        settings.edit().putString("detected", "UNKNOWN").apply();
        report("背景載入 Uber 分享頁面中；可切換司機端。");
        web.loadUrl(url);
    }

    private void pageFailure(String reason) {
        generation++; loaded = false; loading = false; failed = true; polling = false;
        gate.resetCandidate(); errors = Math.min(4, errors + 1);
        nextRetry = SystemClock.elapsedRealtime() + Math.min(120000L, 15000L << (errors - 1));
        settings.edit().putString("detected", "UNKNOWN").apply();
        report(reason);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            settings.edit().putLong("monitor_heartbeat", now).apply();
            if (!wake.isHeld()) wake.acquire(10 * 60_000L);
            if (now - notifiedAt >= 15000L) notifyNow();
            if (loading && now - loadAt > 30000L) {
                if (web != null) web.stopLoading();
                pageFailure("分享頁載入逾時，背景重試中；已開始的計時會保留。");
            }
            if ((failed && now >= nextRetry) || (loaded && now - reloadAt >= 45000L)) loadPage();
            if (loaded && !polling && web != null) inspect();
            handler.postDelayed(this, 2000L);
        }
    };

    private void inspect() {
        polling = true;
        int expectedGeneration = generation;
        WebView current = web;
        try {
            current.evaluateJavascript(MainActivity.statusScript(), value -> {
                if (!running || current != web || expectedGeneration != generation) return;
                polling = false;
                String status = value == null ? "UNKNOWN" : value.replace("\"", "").trim();
                settings.edit().putString("detected", status).apply();
                String transition = gate.offer(status);
                if (transition != null) processStatus(transition);
                else if (!"TRIP".equals(status) && !"ONLINE".equals(status)) report("分享頁尚未顯示可確認的狀態文字；保持計時，不會自行結算。");
                else report("背景已讀到" + ("TRIP".equals(status) ? "正在途中" : "上線中") + "，可繼續使用司機端。");
            });
        } catch (Exception ex) { polling = false; pageFailure("狀態讀取失敗，背景重新載入中。"); }
    }

    private void processStatus(String status) {
        errors = 0;
        boolean active = settings.getBoolean("active", false);
        settings.edit().putString("observed", status).apply();
        if ("TRIP".equals(status) && !active) {
            settings.edit().putBoolean("active", true).putLong("started_at", System.currentTimeMillis())
                    .putLong("started_elapsed", SystemClock.elapsedRealtime()).putString("active_source", "UBER 網頁偵測").apply();
        } else if ("ONLINE".equals(status) && active && "UBER 網頁偵測".equals(settings.getString("active_source", ""))) {
            long start = settings.getLong("started_at", 0L);
            long base = settings.getLong("started_elapsed", -1L);
            long end = base >= 0 && SystemClock.elapsedRealtime() >= base ? start + SystemClock.elapsedRealtime() - base : System.currentTimeMillis();
            long id = store.save(start, end, "UBER 網頁偵測");
            settings.edit().putBoolean("active", false).remove("started_at").remove("started_elapsed").apply();
            markHistoryChanged();
            if (locationForeground && hasLocationPermission()) AddressResolver.capture(this, store, id, this::markHistoryChanged);
            else { store.setAddress(id, "此筆完成時無可用的定位授權，請手動補上地址"); markHistoryChanged(); }
        }
        notifyNow();
    }

    private void markHistoryChanged() { settings.edit().putLong("history_revision", settings.getLong("history_revision", 0L) + 1L).apply(); }

    private void stopMonitoring() {
        running = false;
        settings.edit().putBoolean("watching", false).putString("monitor_message", "監測已停止；已開始的計時保留，可回 App 手動結束。").apply();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        running = false; generation++;
        handler.removeCallbacksAndMessages(null);
        settings.edit().remove("monitor_heartbeat").apply();
        if (wake != null && wake.isHeld()) wake.release();
        try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) { }
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        // Address callbacks may still finish after a user stops monitoring.
        // Their SQLite helper stays open until that callback completes/process ends.
        super.onDestroy();
    }

    final class LocalBinder extends Binder { MonitorService service() { return MonitorService.this; } }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
}
