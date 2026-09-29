package dev.repoagent;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
public class AgentApi {
    private final AgentLoop loop;
    public AgentApi(AgentLoop loop) { this.loop = loop; }

    public record AnalyzeRequest(String repoUrl, String question) {}

    @PostMapping("/analyze")
    public AgentLoop.Result analyze(@RequestBody AnalyzeRequest request) {
        if (request == null || request.question() == null || request.question().isBlank()
                || request.question().length() > 1000) throw new IllegalArgumentException("question must be 1..1000 characters");
        return loop.run(RepoRef.parse(request.repoUrl()), request.question());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> invalid(IllegalArgumentException e) { return ResponseEntity.badRequest().body(e.getMessage()); }

    @ExceptionHandler(GitHubTools.GitHubFailure.class)
    ResponseEntity<String> github(GitHubTools.GitHubFailure e) {
        return ResponseEntity.status(e.status() == 404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_GATEWAY).body(e.getMessage());
    }
}
