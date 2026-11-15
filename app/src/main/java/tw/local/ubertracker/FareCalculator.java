package tw.local.ubertracker;

/**
 * 每筆配送：前 11 計費分鐘固定 NT$45，之後每增加一計費分鐘加 NT$4.10。
 * 僅在結束結算時，依剩餘秒數 30 秒（含）進位，不足 30 秒捨去。
 */
public final class FareCalculator {
    private static final int INCLUDED_MINUTES = 11;
    private static final long BASE_CENTS = 4500L;
    private static final long EXTRA_MINUTE_CENTS = 410L;

    private FareCalculator() {}

    public static int billedMinutes(long durationMillis) {
        if (durationMillis <= 0) return 0;
        // 11m29.999s -> 11m; 11m30.000s -> 12m.
        long rounded = durationMillis > Long.MAX_VALUE - 30_000L
                ? Long.MAX_VALUE / 60_000L
                : (durationMillis + 30_000L) / 60_000L;
        if (rounded > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) rounded;
    }

    /** 金額以「分」儲存，避免 4.10 元的浮點誤差。每筆完成單至少 NT$45。 */
    public static long amountCents(int billedMinutes) {
        return BASE_CENTS + Math.max(0L, (long) billedMinutes - INCLUDED_MINUTES) * EXTRA_MINUTE_CENTS;
    }

    public static String money(long cents) {
        return String.format(java.util.Locale.TAIWAN, "NT$%,.2f", cents / 100.0);
    }
}
