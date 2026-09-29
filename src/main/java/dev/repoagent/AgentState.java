package dev.repoagent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per request; deliberately no durable memory. Raw file bodies never enter the state. */
public final class AgentState {
    final RepoRef repo;
    final String question;
    int step;
    final List<Observation> observations = new ArrayList<>();
    final Set<String> readFiles = new LinkedHashSet<>();
    final Map<String, String> evidence = new LinkedHashMap<>();
    final Set<String> calls = new LinkedHashSet<>();

    AgentState(RepoRef repo, String question) { this.repo = repo; this.question = question; }
    record Observation(String tool, String args, String summary) {}

    String context() {
        StringBuilder b = new StringBuilder("Repository: ").append(repo.id()).append("\nQuestion: ").append(question)
                .append("\nStep: ").append(step).append("\nRead files: ").append(readFiles)
                .append("\nPreviously called: ").append(calls).append("\nObservations:\n");
        b.append("Evidence excerpts (truncated):\n");
        evidence.forEach((path, excerpt) -> b.append(path).append(": ").append(excerpt).append('\n'));
        int start = Math.max(0, observations.size() - 5);
        for (int i = start; i < observations.size(); i++) {
            Observation o = observations.get(i);
            b.append(i + 1).append(". ").append(o.tool()).append("(").append(o.args()).append(") => ")
                    .append(o.summary()).append('\n');
        }
        return b.toString();
    }
}
