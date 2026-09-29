package dev.repoagent;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/** The model returns exactly one action per turn, or FINISH. */
public interface DecisionModel {
    record Decision(String tool, String args, String answer) {
        static Decision call(String name, String json) { return new Decision(name, json, null); }
        static Decision finish(String answer) { return new Decision(null, null, answer); }
        boolean finished() { return tool == null; }
    }
    Decision decide(String context, List<ToolCallback> tools);
}
