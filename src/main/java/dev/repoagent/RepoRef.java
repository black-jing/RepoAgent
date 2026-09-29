package dev.repoagent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record RepoRef(String owner, String name) {
    private static final Pattern URL = Pattern.compile("https://github\\.com/([A-Za-z0-9-]{1,39})/([A-Za-z0-9_.-]{1,100})/?");

    public static RepoRef parse(String url) {
        if (url == null) throw new IllegalArgumentException("A public https://github.com/owner/repo URL is required");
        Matcher m = URL.matcher(url.trim());
        if (!m.matches() || m.group(2).equals(".") || m.group(2).equals(".."))
            throw new IllegalArgumentException("Use only a public https://github.com/owner/repo URL");
        return new RepoRef(m.group(1), m.group(2));
    }
    public String id() { return owner + "/" + name; }
}
