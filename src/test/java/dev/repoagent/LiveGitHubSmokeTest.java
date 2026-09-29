package dev.repoagent;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Network smoke with scripted decisions: tests real GitHub tools, not real model behavior. */
@EnabledIfEnvironmentVariable(named = "LIVE_GITHUB_TESTS", matches = "true")
class LiveGitHubSmokeTest {
    record Scenario(String repository, String question, List<DecisionModel.Decision> decisions, String expectedPath) {}
    @Test void fourPublicRepositoriesAndFourDifferentPaths() throws Exception {
        List<Scenario> scenarios = List.of(
                new Scenario("spring-guides/gs-rest-service", "What does this project do?", List.of(
                        DecisionModel.Decision.call("getReadme", "{}"), DecisionModel.Decision.finish("See README.adoc")), "README.adoc"),
                new Scenario("black-jing/JobRadar", "Where is the LLM call?", List.of(
                        DecisionModel.Decision.call("searchCode", "{\"keyword\":\"DeepSeek\"}"),
                        DecisionModel.Decision.call("readFile", "{\"path\":\"src/main/java/com/jobradar/analysis/deepseek/DeepSeekJobAnalyzer.java\"}"),
                        DecisionModel.Decision.finish("See src/main/java/com/jobradar/analysis/deepseek/DeepSeekJobAnalyzer.java")), "DeepSeekJobAnalyzer.java"),
                new Scenario("spring-projects/spring-petclinic", "Trace controller to repository", List.of(
                        DecisionModel.Decision.call("readFile", "{\"path\":\"src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java\"}"),
                        DecisionModel.Decision.call("readFile", "{\"path\":\"src/main/java/org/springframework/samples/petclinic/owner/OwnerRepository.java\"}"),
                        DecisionModel.Decision.finish("See owner/OwnerController.java and owner/OwnerRepository.java")), "OwnerRepository.java"),
                new Scenario("langchain4j/langchain4j-examples", "Where is function calling?", List.of(
                        DecisionModel.Decision.call("getFileTree", "{\"depth\":2}"),
                        DecisionModel.Decision.call("readFile", "{\"path\":\"open-ai-examples/src/main/java/OpenAiFunctionCallingExamples.java\"}"),
                        DecisionModel.Decision.finish("See open-ai-examples/src/main/java/OpenAiFunctionCallingExamples.java")), "OpenAiFunctionCallingExamples.java"));
        List<AgentLoop.Result> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            int[] next = {0};
            DecisionModel script = (context, tools) -> scenario.decisions().get(next[0]++);
            var result = new AgentLoop(script).run(RepoRef.parse("https://github.com/" + scenario.repository()), scenario.question());
            assertEquals("MODEL_FINISH", result.stopReason(), scenario.repository());
            assertTrue(result.trace().stream().anyMatch(t -> t.observation().contains(scenario.expectedPath())), scenario.repository() + " " + result.trace());
            results.add(result);
        }
        Path output = Path.of("docs", "scripted-live-github-traces.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);
    }
}
