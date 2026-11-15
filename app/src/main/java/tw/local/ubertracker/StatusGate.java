package tw.local.ubertracker;

/** Require two consecutive readings, and emit each transition once. */
public final class StatusGate {
    private String candidate = "", accepted = "";
    private int count;
    public String offer(String status) {
        if (!"ONLINE".equals(status) && !"TRIP".equals(status)) {
            resetCandidate();
            return null;
        }
        if (candidate.equals(status)) count = Math.min(2, count + 1);
        else { candidate = status; count = 1; }
        if (count < 2 || accepted.equals(status)) return null;
        accepted = status;
        return status;
    }
    public void resetCandidate() { candidate = ""; count = 0; }
}
