import tw.local.ubertracker.FareCalculator;

public class FareCalculatorTest {
    private static void check(long millis, int expectedMinutes, long expectedCents) {
        int minutes = FareCalculator.billedMinutes(millis);
        long cents = FareCalculator.amountCents(minutes);
        if (minutes != expectedMinutes || cents != expectedCents) {
            throw new AssertionError(millis + "ms: " + minutes + "min " + cents +
                " cents, expected " + expectedMinutes + "min " + expectedCents + " cents");
        }
        System.out.println(millis + "ms -> " + minutes + " billed min, NT$" + (cents / 100.0));
    }
    public static void main(String[] args) {
        check(0, 0, 4500);
        check(29_000, 0, 4500);
        check(30_000, 1, 4500);
        check(10*60_000L+30_000, 11, 4500);
        check(11*60_000L+29_999, 11, 4500);
        check(11*60_000L, 11, 4500);
        check(11*60_000L+29_000, 11, 4500);
        check(11*60_000L+30_001, 12, 4910);
        check(11*60_000L+30_000, 12, 4910);
        check(12*60_000L+29_999, 12, 4910);
        check(12*60_000L+30_000, 13, 5320);
        check(15*60_000L+45_000, 16, 6550);
        check(20*60_000L+30_000, 21, 8600);
        System.out.println("ALL PASS");
    }
}
