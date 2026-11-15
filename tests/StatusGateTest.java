import tw.local.ubertracker.StatusGate;

public final class StatusGateTest {
    private static void expect(String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    public static void main(String[] args) {
        StatusGate gate = new StatusGate();
        expect(null, gate.offer("TRIP"));
        expect(null, gate.offer("UNKNOWN"));
        expect(null, gate.offer("TRIP"));
        expect("TRIP", gate.offer("TRIP"));
        for (int i = 0; i < 100; i++) expect(null, gate.offer("TRIP"));
        expect(null, gate.offer("ONLINE"));
        expect(null, gate.offer("AMBIGUOUS"));
        expect(null, gate.offer("ONLINE"));
        expect("ONLINE", gate.offer("ONLINE"));
        gate.resetCandidate();
        expect(null, gate.offer("ONLINE"));
        expect(null, gate.offer("ONLINE"));
        expect(null, gate.offer("TRIP"));
        expect("TRIP", gate.offer("TRIP"));
        System.out.println("STATUS GATE TESTS PASSED");
    }
}
