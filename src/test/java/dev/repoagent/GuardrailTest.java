package dev.repoagent;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GuardrailTest {
    @Test void repositoryUrlIsStrict() {
        assertEquals("spring-projects/spring-ai", RepoRef.parse("https://github.com/spring-projects/spring-ai").id());
        for (String url : new String[]{"http://github.com/a/b", "https://github.com/a/b/tree/main", "https://evil.com/a/b", "https://github.com/a/.."})
            assertThrows(IllegalArgumentException.class, () -> RepoRef.parse(url));
    }
    @Test void pathCannotEscapeOrBeDoubleDecoded() {
        for (String path : new String[]{"../x", "a/../../x", "/tmp/x", "a\\b", "a//b", "a/%2e%2e/x", "a/./b"})
            assertThrows(IllegalArgumentException.class, () -> GitHubTools.safePath(path, false), path);
        assertEquals("src/main/A.java", GitHubTools.safePath("src/main/A.java", false));
    }
    @Test void outputIsBounded() { assertEquals(12, GitHubTools.shorten("x".repeat(50), 12).indexOf('\n')); }
}
