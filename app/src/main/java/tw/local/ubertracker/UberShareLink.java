package tw.local.ubertracker;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 從 Android ACTION_SEND 分享文字擷取 Uber 分享連結；拒絕相似網域。 */
public final class UberShareLink {
    private static final Pattern URL = Pattern.compile("https://[^\\s<>]+", Pattern.CASE_INSENSITIVE);
    private UberShareLink() { }

    public static String extract(String text) {
        if (text == null) return null;
        Matcher m = URL.matcher(text);
        while (m.find()) {
            String candidate = m.group().replaceAll("[\\)\\]\\}>,，。！!；;）]+$", "");
            try {
                URI u = new URI(candidate);
                String host = u.getHost();
                String path = u.getRawPath();
                if ("https".equalsIgnoreCase(u.getScheme()) && host != null
                        && ("trip.uber.com".equalsIgnoreCase(host) || "m.uber.com".equalsIgnoreCase(host))
                        && path != null && path.length() > 1) {
                    return candidate;
                }
            } catch (Exception ignored) { }
        }
        return null;
    }
}
