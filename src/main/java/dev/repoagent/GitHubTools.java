package dev.repoagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** GitHub REST only. Every URL is built from a validated owner/repo and an indexed relative path. */
public final class GitHubTools {
    private static final int MAX_TREE = 5000, MAX_FILE_CHARS = 6000, MAX_SEARCH_FILES = 12;
    private final RepoRef repo;
    private final HttpClient http = client();
    private final ObjectMapper json = new ObjectMapper();
    private final String token = System.getenv("GITHUB_TOKEN");
    private final List<Entry> entries;
    private final String defaultBranch;

    record Entry(String path, String type, long size) {}
    public static class GitHubFailure extends RuntimeException {
        private final int status;
        GitHubFailure(int status, String message) { super(message); this.status = status; }
        public int status() { return status; }
    }

    public GitHubTools(RepoRef repo) {
        this.repo = repo;
        JsonNode metadata = get("repos/" + repo.id());
        defaultBranch = metadata.path("default_branch").asText();
        if (defaultBranch.isBlank()) throw new GitHubFailure(502, "GitHub did not provide a default branch");
        JsonNode tree = get("repos/" + repo.id() + "/git/trees/" + enc(defaultBranch) + "?recursive=1");
        if (tree.path("truncated").asBoolean() || tree.path("tree").size() > MAX_TREE)
            throw new GitHubFailure(422, "Repository tree is too large for this first version");
        entries = new ArrayList<>();
        for (JsonNode item : tree.path("tree")) {
            String type = item.path("type").asText();
            if (type.equals("blob") || type.equals("tree"))
                entries.add(new Entry(item.path("path").asText(), type, item.path("size").asLong()));
        }
    }

    @Tool(description = "Show a bounded-depth directory tree; use for locating modules and entry points. Depth 1..4.")
    public String getFileTree(@ToolParam(description = "Maximum directory depth from repository root, 1 to 4") int depth) {
        if (depth < 1 || depth > 4) throw new IllegalArgumentException("depth must be 1..4");
        List<String> names = entries.stream().filter(e -> e.path().split("/").length <= depth)
                .map(e -> e.type().equals("tree") ? e.path() + "/" : e.path()).limit(100).toList();
        return String.join("\n", names) + (names.size() == 100 ? "\n[truncated at 100 entries]" : "");
    }

    @Tool(description = "List direct children of a known directory. Path is a relative directory, empty string for root.")
    public String listFiles(@ToolParam(description = "Relative directory path, empty string for root") String path) {
        String p = safePath(path, true);
        if (!p.isEmpty() && entries.stream().noneMatch(e -> e.path().equals(p) && e.type().equals("tree")))
            throw new IllegalArgumentException("Directory not found: " + p);
        String prefix = p.isEmpty() ? "" : p + "/";
        List<String> children = entries.stream().filter(e -> e.path().startsWith(prefix))
                .map(e -> e.path().substring(prefix.length()))
                .filter(s -> !s.isEmpty() && !s.contains("/"))
                .limit(100).toList();
        return String.join("\n", children) + (children.size() == 100 ? "\n[truncated]" : "");
    }

    @Tool(description = "Read one existing repository file; output is limited to 6000 characters.")
    public String readFile(@ToolParam(description = "Exact relative path of a file found in the tree") String path) {
        String p = safePath(path, false);
        Entry file = entries.stream().filter(e -> e.path().equals(p) && e.type().equals("blob"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("File not found: " + p));
        if (file.size() > 100_000) return "[File exceeds 100 KB; choose a smaller file: " + p + "]";
        JsonNode value = get("repos/" + repo.id() + "/contents/" + encodePath(p) + "?ref=" + enc(defaultBranch));
        if (!value.path("encoding").asText().equals("base64")) return "[Non-text file unsupported: " + p + "]";
        byte[] data = java.util.Base64.getMimeDecoder().decode(value.path("content").asText());
        String content = new String(data, StandardCharsets.UTF_8);
        return "FILE " + p + "\n" + shorten(content, MAX_FILE_CHARS);
    }

    @Tool(description = "Bounded literal keyword search in up to 12 candidate code files; partial results, never proof of absence.")
    public String searchCode(@ToolParam(description = "Literal identifier or keyword, 2 to 80 characters") String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.length() < 2 || keyword.length() > 80)
            throw new IllegalArgumentException("keyword must be 2..80 characters");
        String needle = keyword.toLowerCase(Locale.ROOT);
        String stem = needle.replaceAll("[^a-z0-9]", "");
        List<Entry> candidates = entries.stream().filter(e -> e.type().equals("blob") && e.size() <= 50_000)
                .filter(e -> e.path().matches("(?i).*\\.(java|kt|py|ts|tsx|js|xml|yml|yaml|md)$"))
                .sorted(Comparator.comparingInt((Entry e) -> score(e.path(), stem)).reversed().thenComparing(Entry::path))
                .limit(MAX_SEARCH_FILES).toList();
        List<String> matches = new ArrayList<>();
        for (Entry e : candidates) {
            try {
                String content = readFile(e.path());
                int at = content.toLowerCase(Locale.ROOT).indexOf(needle);
                if (at >= 0) {
                    matches.add(e.path() + ": " + content.substring(Math.max(0, at - 80), Math.min(content.length(), at + 180))
                            .replace('\n', ' '));
                    if (matches.size() == 8) break;
                }
            } catch (GitHubFailure e1) {
                if (e1.status() != 404) throw e1;
            }
        }
        return "PARTIAL SEARCH: scanned " + candidates.size() + " of " + entries.stream().filter(e -> e.type().equals("blob")).count()
                + " files; " + matches.size() + " matches. " + (matches.isEmpty() ? "No matches in scanned files; cannot infer absence." : String.join("\n", matches));
    }

    @Tool(description = "Read the root README when available; at most 6000 characters.")
    public String getReadme() {
        return entries.stream().filter(e -> e.type().equals("blob") && e.path().matches("(?i)readme(\\.md|\\.adoc|\\.rst|\\.txt)?"))
                .findFirst().map(e -> readFile(e.path())).orElse("Root README not found");
    }

    private static int score(String path, String stem) {
        String lower = path.toLowerCase(Locale.ROOT);
        int n = (stem.length() >= 2 && lower.replaceAll("[^a-z0-9]", "").contains(stem)) ? 100 : 0;
        if (lower.contains("src/main")) n += 10;
        if (lower.endsWith(".java")) n += 5;
        if (lower.contains("test") || lower.contains("node_modules")) n -= 30;
        return n;
    }
    static String safePath(String path, boolean rootAllowed) {
        if (path == null || (!rootAllowed && path.isEmpty()) || path.length() > 500 || path.startsWith("/")
                || path.contains("\\") || path.contains("%") || path.chars().anyMatch(c -> c < 32))
            throw new IllegalArgumentException("Invalid relative path");
        if (!path.isEmpty()) for (String segment : path.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("Invalid relative path");
        return path;
    }
    static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "\n[truncated; " + value.length() + " chars total]";
    }
    private static String enc(String part) { return URLEncoder.encode(part, StandardCharsets.UTF_8); }
    private static String encodePath(String path) { return String.join("/", java.util.Arrays.stream(path.split("/")).map(GitHubTools::enc).toList()); }

    private static HttpClient client() {
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8));
        String proxy = System.getenv("HTTPS_PROXY");
        if (proxy == null || proxy.isBlank()) proxy = System.getenv("https_proxy");
        if (proxy != null && !proxy.isBlank()) {
            URI uri = URI.create(proxy);
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort())));
        }
        return builder.build();
    }

    private JsonNode get(String endpoint) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                var builder = HttpRequest.newBuilder(URI.create("https://api.github.com/" + endpoint))
                        .timeout(Duration.ofSeconds(12)).header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "repository-understanding-agent").GET();
                if (token != null && !token.isBlank()) builder.header("Authorization", "Bearer " + token);
                var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 200) {
                    if (response.body().length() > 2_000_000) throw new GitHubFailure(422, "GitHub response too large");
                    return json.readTree(response.body());
                }
                if (status >= 500) {
                    if (attempt == 0) continue;
                }
                if (status == 403 && response.headers().firstValue("x-ratelimit-remaining").orElse("1").equals("0"))
                    throw new GitHubFailure(429, "GitHub API rate limit reached; set GITHUB_TOKEN or wait for reset");
                throw new GitHubFailure(status, status == 404 ? "Repository or file not found" : "GitHub API returned " + status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GitHubFailure(502, "GitHub request interrupted");
            } catch (IOException e) {
                if (attempt == 0) continue;
                throw new GitHubFailure(502, "GitHub request failed: " + e.getClass().getSimpleName());
            }
        }
        throw new GitHubFailure(502, "GitHub request failed");
    }
}
