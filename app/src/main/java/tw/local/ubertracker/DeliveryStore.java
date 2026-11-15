package tw.local.ubertracker;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 所有訂單只保存在手機 App 私有 SQLite 資料庫。 */
public final class DeliveryStore extends SQLiteOpenHelper {
    public static class Delivery {
        public long id, startMs, endMs, durationMs, amountCents;
        public int minutes;
        public String address, source;
        public Double latitude, longitude, accuracy;
    }

    public DeliveryStore(Context ctx) { super(ctx, "deliveries.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE deliveries ("+
                "id INTEGER PRIMARY KEY AUTOINCREMENT,"+
                "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,"+
                "duration_ms INTEGER NOT NULL, billed_minutes INTEGER NOT NULL,"+
                "amount_cents INTEGER NOT NULL, source TEXT NOT NULL,"+
                "address TEXT NOT NULL, latitude REAL, longitude REAL, accuracy REAL)");
        db.execSQL("CREATE INDEX ix_delivery_end ON deliveries(end_ms)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int from, int to) { }

    public synchronized long save(long startMs, long endMs, String source) {
        long duration = Math.max(0L, endMs - startMs);
        int minutes = FareCalculator.billedMinutes(duration);
        ContentValues v = new ContentValues();
        v.put("start_ms", startMs);
        v.put("end_ms", endMs);
        v.put("duration_ms", duration);
        v.put("billed_minutes", minutes);
        v.put("amount_cents", FareCalculator.amountCents(minutes));
        v.put("source", source);
        v.put("address", "正在取得定位…");
        return getWritableDatabase().insertOrThrow("deliveries", null, v);
    }

    public synchronized int delete(long id) {
        return getWritableDatabase().delete("deliveries", "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void setPosition(long id, double lat, double lon, float accuracy, String address) {
        ContentValues v = new ContentValues();
        v.put("latitude", lat);
        v.put("longitude", lon);
        v.put("accuracy", accuracy);
        v.put("address", address);
        getWritableDatabase().update("deliveries", v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized void setAddress(long id, String address) {
        ContentValues v = new ContentValues();
        v.put("address", address);
        getWritableDatabase().update("deliveries", v, "id=?", new String[]{String.valueOf(id)});
    }

    public synchronized List<Delivery> all() {
        List<Delivery> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT id,start_ms,end_ms,duration_ms,billed_minutes,amount_cents,source,address,latitude,longitude,accuracy FROM deliveries ORDER BY end_ms DESC,id DESC", null)) {
            while (c.moveToNext()) {
                Delivery d = new Delivery();
                d.id = c.getLong(0);
                d.startMs = c.getLong(1);
                d.endMs = c.getLong(2);
                d.durationMs = c.getLong(3);
                d.minutes = c.getInt(4);
                d.amountCents = c.getLong(5);
                d.source = c.getString(6);
                d.address = c.getString(7);
                if (!c.isNull(8)) d.latitude = c.getDouble(8);
                if (!c.isNull(9)) d.longitude = c.getDouble(9);
                if (!c.isNull(10)) d.accuracy = c.getDouble(10);
                result.add(d);
            }
        }
        return result;
    }

    public synchronized long[] todayTotal() {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long since = cal.getTimeInMillis();
        long end = since + 86_400_000L; // local day end may differ on DST; Taiwan has no DST
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*),COALESCE(SUM(amount_cents),0) FROM deliveries WHERE end_ms>=? AND end_ms<? AND source NOT LIKE '測試範例%' AND source NOT LIKE '模擬／手動%' ", new String[]{String.valueOf(since), String.valueOf(end)})) {
            if (c.moveToFirst()) return new long[]{c.getLong(0), c.getLong(1)};
        }
        return new long[]{0L, 0L};
    }

    public synchronized String exportCsv() {
        StringBuilder s = new StringBuilder("\uFEFF序號,開始時間,結束時間,實際秒數,計費分鐘,預估金額(新台幣),完整結束地址,緯度,經度,精度(公尺),來源\r\n");
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN);
        for (Delivery d : all()) {
            s.append(d.id).append(',').append(csv(format.format(new Date(d.startMs)))).append(',')
            .append(csv(format.format(new Date(d.endMs)))).append(',')
            .append(d.durationMs / 1000).append(',').append(d.minutes).append(',')
            .append(String.format(Locale.US, "%.2f", d.amountCents / 100.0)).append(',')
            .append(csv(d.address)).append(',')
            .append(d.latitude == null ? "" : d.latitude).append(',')
            .append(d.longitude == null ? "" : d.longitude).append(',')
            .append(d.accuracy == null ? "" : d.accuracy).append(',')
            .append(csv(d.source)).append("\r\n");
        }
        return s.toString();
    }

    private static String csv(String value) {
        if (value == null) value = "";
        // Prevent spreadsheet formula injection in manually entered addresses.
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"", "\"\"").replace('\r', ' ').replace('\n', ' ') + "\"";
    }
}
