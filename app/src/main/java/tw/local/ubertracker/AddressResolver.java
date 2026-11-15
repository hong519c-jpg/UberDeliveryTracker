package tw.local.ubertracker;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 送達時抓當下位置並反向查詢地址。定位失敗會留明確標記，不杜撰門牌。 */
public final class AddressResolver {
    private AddressResolver() {}
    public static void capture(Context ctx, DeliveryStore store, long id, Runnable updated) {
        Handler ui = new Handler(Looper.getMainLooper());
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            store.setAddress(id, "未授權定位，請手動補上完整地址");
            updated.run();
            return;
        }
        LocationManager lm = (LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            store.setAddress(id, "無法使用手機定位服務，請手動補上地址");
            updated.run();
            return;
        }
        AtomicBoolean completed = new AtomicBoolean(false);
        String provider;
        try {
            boolean fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            provider = fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
        } catch (Exception ex) {
            provider = LocationManager.NETWORK_PROVIDER;
        }
        final String chosenProvider = provider;
        final CancellationSignal cancellation = new CancellationSignal();
        Runnable onTimeout = () -> {
            if (!completed.compareAndSet(false, true)) return;
            cancellation.cancel();
            Location last = findRecentFix(lm);
            if (last != null) reverseGeocode(ctx, store, id, last, updated);
            else {
                store.setAddress(id, "未取得當下 GPS 位置，請手動補上完整地址");
                updated.run();
            }
        };
        ui.postDelayed(onTimeout, 12000L);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(chosenProvider, cancellation, ctx.getMainExecutor(), fix -> {
                    if (fix == null) return; // 等待超時後嘗試近期定位
                    if (!completed.compareAndSet(false, true)) return;
                    ui.removeCallbacks(onTimeout);
                    reverseGeocode(ctx, store, id, fix, updated);
                });
            } else {
                lm.requestSingleUpdate(chosenProvider, fix -> {
                    if (!completed.compareAndSet(false, true)) return;
                    ui.removeCallbacks(onTimeout);
                    reverseGeocode(ctx, store, id, fix, updated);
                }, Looper.getMainLooper());
            }
        } catch (Exception ex) {
            ui.removeCallbacks(onTimeout);
            if (completed.compareAndSet(false, true)) {
                Location last = findRecentFix(lm);
                if (last != null) reverseGeocode(ctx, store, id, last, updated);
                else {
                    store.setAddress(id, "定位失敗，請手動補上完整地址");
                    updated.run();
                }
            }
        }
    }

    private static Location findRecentFix(LocationManager lm) {
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location loc = lm.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (Math.abs(System.currentTimeMillis() - loc.getTime()) > 120000L) continue;
                if (loc.hasAccuracy() && loc.getAccuracy() > 150f) continue;
                if (best == null || loc.getTime() > best.getTime()) best = loc;
            } catch (Exception ignored) { }
        }
        return best;
    }

    private static void reverseGeocode(Context ctx, DeliveryStore store, long id, Location loc, Runnable updated) {
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        float accuracy = loc.hasAccuracy() ? loc.getAccuracy() : -1;
        store.setPosition(id, lat, lon, accuracy, "已定位，正在查詢完整地址…");
        updated.run();
        Geocoder coder = new Geocoder(ctx, Locale.TRADITIONAL_CHINESE);
        if (!Geocoder.isPresent()) {
            store.setAddress(id, "地址服務無法使用，已保存 GPS 經緯度（可手動補充門牌）");
            updated.run();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                coder.getFromLocation(lat, lon, 1, new Geocoder.GeocodeListener() {
                    @Override public void onGeocode(List<Address> addresses) {
                        setResult(store, id, addresses, updated);
                    }
                    @Override public void onError(String message) {
                        store.setAddress(id, "無法查到完整門牌，已保存 GPS 座標（可手動補充）");
                        updated.run();
                    }
                });
            } catch (Exception ex) {
                store.setAddress(id, "地址查詢失敗，已保存 GPS 座標（可手動補充）");
                updated.run();
            }
        } else {
            new Thread(() -> {
                try {
                    @SuppressWarnings("deprecation") List<Address> addresses = coder.getFromLocation(lat, lon, 1);
                    setResult(store, id, addresses, updated);
                } catch (Exception ex) {
                    store.setAddress(id, "地址查詢失敗，已保存 GPS 座標（可手動補充）");
                    updated.run();
                }
            }).start();
        }
    }

    private static void setResult(DeliveryStore store, long id, List<Address> addresses, Runnable updated) {
        if (addresses != null && !addresses.isEmpty()) {
            Address a = addresses.get(0);
            String line = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : null;
            if (line == null || line.trim().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (String p : new String[]{a.getAdminArea(), a.getSubAdminArea(), a.getLocality(), a.getThoroughfare(), a.getSubThoroughfare()}) {
                    if (p != null && !p.trim().isEmpty()) sb.append(p);
                }
                line = sb.toString();
            }
            if (line != null && !line.trim().isEmpty()) store.setAddress(id, line.trim());
            else store.setAddress(id, "已保存 GPS 座標，但查無完整街道地址（可手動補充）");
        } else store.setAddress(id, "已保存 GPS 座標，但查無完整街道地址（可手動補充）");
        updated.run();
    }
}
