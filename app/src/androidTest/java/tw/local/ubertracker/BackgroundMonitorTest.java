package tw.local.ubertracker;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.SystemClock;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BackgroundMonitorTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final SharedPreferences preferences = context.getSharedPreferences("tracker", Context.MODE_PRIVATE);
    private MonitorService service;
    private void await(java.util.function.BooleanSupplier ready) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        while (!ready.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(200);
        assertTrue("Timed out waiting for background state", ready.getAsBoolean());
    }
    @Test public void monitorsAndSettlesAfterActivityDestroyed() throws Exception {
        preferences.edit().clear().commit();
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service = ((MonitorService.LocalBinder) binder).service(); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        instrumentation.runOnMainSync(() -> activity.startForegroundService(new Intent(context, MonitorService.class)
                .setAction(MonitorService.START).putExtra("url", "https://trip.uber.com/background-test")
                .putExtra("activity_visible", true)));
        context.bindService(new Intent(context, MonitorService.class), connection, Context.BIND_AUTO_CREATE);
        assertTrue(connected.await(15, TimeUnit.SECONDS));
        DeliveryStore store = new DeliveryStore(context);
        try {
            Field field = MonitorService.class.getDeclaredField("web"); field.setAccessible(true);
            WebView web = (WebView) field.get(service);
            instrumentation.runOnMainSync(() -> {
                web.stopLoading();
                web.loadDataWithBaseURL("https://trip.uber.com/", "<html><body><p>測試員 正在途中</p></body></html>", "text/html", "UTF-8", null);
                activity.moveTaskToBack(true);
                activity.finish();
            });
            await(() -> preferences.getBoolean("active", false));
            assertTrue(activity.isFinishing());
            assertEquals("TRIP", preferences.getString("observed", ""));
            long started = preferences.getLong("started_at", 0L);
            // Simulate a transient page failure; it must not end the active delivery.
            instrumentation.runOnMainSync(() -> web.evaluateJavascript("document.body.innerHTML='<p>網頁無法使用</p>'", null));
            Thread.sleep(5000);
            assertTrue(preferences.getBoolean("active", false));
            assertEquals(0, store.all().size());
            instrumentation.runOnMainSync(() -> web.evaluateJavascript("document.body.innerHTML='<p>測試員 上線中</p>'", null));
            await(() -> !preferences.getBoolean("active", true));
            assertEquals(1, store.all().size());
            assertEquals(started, store.all().get(0).startMs);
            assertTrue(store.all().get(0).durationMs >= 5000);
            Thread.sleep(5000);
            assertEquals("Repeated ONLINE must not create duplicate deliveries", 1, store.all().size());
            assertTrue(preferences.getBoolean("watching", false));
        } finally {
            context.unbindService(connection);
            context.startService(new Intent(context, MonitorService.class).setAction(MonitorService.STOP));
            store.close();
        }
    }
}
