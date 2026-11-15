import tw.local.ubertracker.UberShareLink;

public class UberShareLinkTest {
    private static int tested=0;
    private static void expect(String shared, String expected) {
        String got=UberShareLink.extract(shared);
        if (expected == null ? got != null : !expected.equals(got)) {
            throw new AssertionError("For " + shared + ", got=" + got + " expected=" + expected);
        }
        tested++;
    }
    public static void main(String[] args) {
        expect("透過 Uber 查看我所在的位置：https://trip.uber.com/QD85LqIO", "https://trip.uber.com/QD85LqIO");
        expect("https://m.uber.com/go/share?share_token=QD85LqIO", "https://m.uber.com/go/share?share_token=QD85LqIO");
        expect("這個網址（https://trip.uber.com/QD85LqIO）", "https://trip.uber.com/QD85LqIO");
        expect("先 https://example.com/a 再 https://trip.uber.com/ok", "https://trip.uber.com/ok");
        expect("https://trip.uber.com.evil.test/QD85LqIO", null);
        expect("http://trip.uber.com/QD85LqIO", null);
        expect("", null);
        expect("https://m.uber.com/", null);
        System.out.println("SHARE LINK TESTS PASSED: " + tested);
    }
}
