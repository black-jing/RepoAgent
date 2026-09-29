package dev.repoagent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class AgentLoop {
    public static final int MAX_STEPS = 10;
    private final DecisionModel model;
    private final Function<RepoRef, List<ToolCallback>> source;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Trace(int step, String action, String tool, String arguments, String observation,
                        long elapsedMs, boolean continues, String reason) {}
    public record Result(String answer, int steps, int toolCalls, int retries, String stopReason, List<Trace> trace) {}

    @Autowired public AgentLoop(DecisionModel model) { this(model, repo -> Arrays.asList(MethodToolCallbackProvider.builder()
            .toolObjects(new GitHubTools(repo)).build().getToolCallbacks())); }
    AgentLoop(DecisionModel model, Function<RepoRef, List<ToolCallback>> source) { this.model = model; this.source = source; }

    public Result run(RepoRef repo, String question) {
        List<ToolCallback> callbacks = source.apply(repo); // validates repo exists before any model cost
        Map<String, ToolCallback> registry = new LinkedHashMap<>();
        for (var cb : callbacks) registry.put(cb.getToolDefinition().name(), cb);
        AgentState state = new AgentState(repo, question);
        List<Trace> trace = new ArrayList<>();
        int retries = 0, toolCalls = 0, failures = 0;
        for (int step = 1; step <= MAX_STEPS; step++) {
            state.step = step;
            long started = System.nanoTime();
            DecisionModel.Decision decision;
            try {
                try { decision = model.decide(state.context(), callbacks); }
                catch (RuntimeException first) {
                    if (!retryable(first)) throw first;
                    retries++;
                    decision = model.decide(state.context(), callbacks);
                }
            } catch (RuntimeException e) {
                trace.add(new Trace(step, "ERROR", null, null, "LLM unavailable: " + e.getClass().getSimpleName(), elapsed(started), false, "LLM_FAILURE"));
                return result("LLM 调用失败，无法完成分析。", step, toolCalls, retries, "LLM_FAILURE", trace);
            }
            if (decision == null) {
                trace.add(new Trace(step, "ERROR", null, null, "Empty decision", elapsed(started), false, "LLM_FAILURE"));
                return result("模型未返回有效决策。", step, toolCalls, retries, "LLM_FAILURE", trace);
            }
            if (decision.finished()) {
                String answer = state.readFiles.isEmpty() ? "尚未读取到可引用的文件，无法给出有证据的结论。" : decision.answer();
                if (!state.readFiles.isEmpty() && state.readFiles.stream().noneMatch(answer::contains))
                    answer += "\n已读取的证据文件：" + state.readFiles;
                trace.add(new Trace(step, "FINISH", null, null, "Final answer", elapsed(started), false, "MODEL_FINISH"));
                return result(answer, step, toolCalls, retries, "MODEL_FINISH", trace);
            }
            String name = decision.tool(), args = decision.args();
            ToolCallback tool = registry.get(name);
            String key = name + ":" + args;
            String observation;
            if (tool == null || args == null || args.length() > 1000) {
                observation = "Invalid tool name or arguments";
                failures++;
            } else if (!state.calls.add(key)) {
                observation = "Repeated identical tool call rejected";
                failures++;
            } else {
                toolCalls++;
                try {
                    observation = unwrap(tool.call(args));
                } catch (RuntimeException e) {
                    observation = "Tool failed: " + e.getClass().getSimpleName() + ": " + safeError(e);
                    failures++;
                }
            }
            String brief = GitHubTools.shorten(observation, 1200);
            state.observations.add(new AgentState.Observation(name, GitHubTools.shorten(String.valueOf(args), 160), brief));
            if ((name.equals("readFile") || name.equals("getReadme")) && observation.startsWith("FILE ")) {
                int newline = observation.indexOf('\n');
                if (newline > 5) {
                    String path = observation.substring(5, newline);
                    state.readFiles.add(path);
                    if (state.evidence.size() < 6) state.evidence.put(path, GitHubTools.shorten(observation.substring(newline + 1), 450));
                }
            }
            boolean continues = failures < 3 && step < MAX_STEPS;
            String reason = failures >= 3 ? "TOOL_FAILURE_LIMIT" : step == MAX_STEPS ? "MAX_STEPS" : "CONTINUE";
            trace.add(new Trace(step, "CALL_TOOL", name, GitHubTools.shorten(String.valueOf(args), 160),
                    GitHubTools.shorten(observation.replace('\n', ' '), 240), elapsed(started), continues, reason));
            if (!continues) return result("步骤或工具失败次数达到上限；根据当前证据无法确认完整结论。已读取：" + state.readFiles,
                    step, toolCalls, retries, reason, trace);
        }
        throw new IllegalStateException("Unreachable");
    }

    private static boolean retryable(RuntimeException e) {
        String s = String.valueOf(e.getMessage()).toLowerCase();
        return !(s.contains("401") || s.contains("403") || s.contains("invalid api key"));
    }
    private static String unwrap(String raw) {
        if (raw == null) return "Tool returned no data";
        if (raw.startsWith("\"")) try { return JSON.readValue(raw, String.class); }
        catch (Exception ignored) { /* custom callback might return plain quoted text */ }
        return raw;
    }
    private static String safeError(RuntimeException e) {
        return e instanceof IllegalArgumentException || e instanceof GitHubTools.GitHubFailure
                ? GitHubTools.shorten(String.valueOf(e.getMessage()), 100) : "execution error";
    }
    private static long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private static Result result(String answer, int steps, int calls, int retries, String reason, List<Trace> trace) {
        return new Result(answer, steps, calls, retries, reason, List.copyOf(trace));
    }
}
