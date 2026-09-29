package dev.repoagent;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

class AgentLoopTest {
    private static final RepoRef REPO = RepoRef.parse("https://github.com/example/demo");

    private ToolCallback fake(String name, Function<String, String> action) {
        return new ToolCallback() {
            final ToolDefinition definition = ToolDefinition.builder().name(name).description(name)
                    .inputSchema("{\"type\":\"object\"}").build();
            public ToolDefinition getToolDefinition() { return definition; }
            public ToolMetadata getToolMetadata() { return ToolMetadata.builder().build(); }
            public String call(String input) { return action.apply(input); }
            public String call(String input, ToolContext context) { return call(input); }
        };
    }
    private AgentLoop loop(DecisionModel model) {
        List<ToolCallback> tools = List.of(
                fake("getReadme", arg -> "FILE README.md\nA sample service"),
                fake("getFileTree", arg -> "README.md\nsrc/\nsrc/App.java"),
                fake("readFile", arg -> {
                    if (arg.contains("..")) throw new IllegalArgumentException("Invalid relative path");
                    return "FILE src/App.java\nclass App {}";
                }),
                fake("listFiles", arg -> "a\nb"));
        return new AgentLoop(model, r -> tools);
    }

    @Test void observationChangesNextDecisionAndStops() {
        DecisionModel model = (context, available) -> context.contains("A sample service")
                ? DecisionModel.Decision.finish("Service: README.md") : DecisionModel.Decision.call("getReadme", "{}");
        var result = loop(model).run(REPO, "What does it do?");
        assertEquals("MODEL_FINISH", result.stopReason());
        assertEquals(2, result.steps());
        assertEquals(1, result.toolCalls());
        assertEquals(Arrays.asList("getReadme", null), result.trace().stream().map(AgentLoop.Trace::tool).toList());
    }

    @Test void differentGoalCanTakeDifferentPath() {
        DecisionModel model = (context, available) -> {
            if (!context.contains("src/App.java")) return DecisionModel.Decision.call("getFileTree", "{\"depth\":2}");
            if (!context.contains("class App")) return DecisionModel.Decision.call("readFile", "{\"path\":\"src/App.java\"}");
            return DecisionModel.Decision.finish("Entry: src/App.java");
        };
        var result = loop(model).run(REPO, "Where is the entry?");
        assertEquals(Arrays.asList("getFileTree", "readFile", null), result.trace().stream().map(AgentLoop.Trace::tool).toList());
        assertEquals(3, result.steps());
    }

    @Test void rejectsRepeatedCalls() {
        var result = loop((context, available) -> DecisionModel.Decision.call("getReadme", "{}"))
                .run(REPO, "Loop forever");
        assertEquals("TOOL_FAILURE_LIMIT", result.stopReason());
        assertEquals(1, result.toolCalls());
        assertTrue(result.trace().stream().anyMatch(t -> t.observation().contains("Repeated")));
    }

    @Test void maxStepsStopsDistinctCalls() {
        int[] n = {0};
        DecisionModel model = (context, available) -> DecisionModel.Decision.call("listFiles", "{\"path\":\"dir" + (++n[0]) + "\"}");
        var result = loop(model).run(REPO, "Never finish");
        assertEquals("MAX_STEPS", result.stopReason());
        assertEquals(AgentLoop.MAX_STEPS, result.steps());
    }

    @Test void retriesOneTransientLlmFailure() {
        int[] n = {0};
        DecisionModel model = (context, available) -> {
            if (n[0]++ == 0) throw new IllegalStateException("temporary unavailable");
            return context.contains("A sample service") ? DecisionModel.Decision.finish("README.md") : DecisionModel.Decision.call("getReadme", "{}");
        };
        var result = loop(model).run(REPO, "What?");
        assertEquals(1, result.retries());
        assertEquals("MODEL_FINISH", result.stopReason());
    }

    @Test void invalidPathIsObservedAndCanRecover() {
        DecisionModel model = (context, available) -> context.contains("A sample service")
                ? DecisionModel.Decision.finish("README.md") : context.contains("Tool failed")
                ? DecisionModel.Decision.call("getReadme", "{}") : DecisionModel.Decision.call("readFile", "{\"path\":\"../../secret\"}");
        var result = loop(model).run(REPO, "Question");
        assertTrue(result.trace().getFirst().observation().contains("Invalid relative path"));
        assertEquals("MODEL_FINISH", result.stopReason());
    }

    @Test void llmFailureReturnsTraceWithoutInventingAnswer() {
        var result = loop((context, available) -> { throw new IllegalStateException("temporary unavailable"); })
                .run(REPO, "Question");
        assertEquals("LLM_FAILURE", result.stopReason());
        assertEquals(1, result.retries());
        assertEquals("ERROR", result.trace().getFirst().action());
    }
}
