# 我必须真正理解的核心代码

建议按下面的顺序读，不用一开始记住 Spring AI 的所有类。

| 文件 | 解决的问题 | 核心数据流 | 面试要会讲 | 框架 API 只需看懂 |
|---|---|---|---|---|
| `AgentLoop.java` | 控制每轮动作与终止 | State → LLM Decision → ToolCallback → Observation → State/Trace | 为什么只执行一个工具；maxSteps、重复调用和失败阈值；主动 FINISH；原始工具结果解码 | `ToolCallback.call`、`MethodToolCallbackProvider` |
| `AgentState.java` | 防止上下文无限增长 | 保存用户目标、已读路径、短证据、最近 5 个 Observation | 为什么完整文件不能一直进 Prompt；旧证据保留短片段的取舍 | 无 |
| `SpringAiDecisionModel.java` | 把模型输出解析成 Call 或 FINISH | `Prompt + Tool schema` → `ChatResponse` → `ToolCall/answer` | 原生 Tool Calling 与 Java 执行的职责分界；纯文本代表 FINISH | `ChatModel`、`ToolCallingChatOptions`、`AssistantMessage` |
| `GitHubTools.java` | 对外界资源做只读访问 | 严格 repo URL → Git tree 索引 → 文件读取/有限搜索 | 不能遍历本机路径；输出、搜索、仓库规模限制；部分搜索不能证明不存在 | `@Tool`、`@ToolParam` |
| `RepoRef.java` | 输入与范围边界 | URL 校验 → 只允许 GitHub owner/repo | SSRF 边界和为什么不接受任意 URL | 无 |

## 一次调用怎么走

1. `AgentApi` 验证问题和 URL，`AgentLoop` 初始化该仓库的工具实例。
2. `AgentState.context()` 构造有限上下文。`SpringAiDecisionModel` 用模型判断下一步。
3. 如果模型返回 Tool Call，`AgentLoop` 在白名单里找对应 ToolCallback，执行并将字符串结果解码成 Observation。
4. Observation 的短摘要进入 State；读到文件时再留一个最多 450 字符的证据片段。Trace 记录这步参数、摘要、耗时和继续原因。
5. 下一轮重新让模型选择 Tool 或主动给答案。Java 负责 10 步上限与错误退出。

## 需要诚实解释的实现取舍

- 工具 schema 由 Spring AI 从 Java 注解生成；不需要手写 JSON Schema，但要知道模型给出的参数不可信，仍由 Java 路径检查。
- 每一步重建有界 Prompt，前一轮 Tool Call 不作为原生 `ToolResponseMessage` 的完整历史传入。Observation 作为上下文文本进入下一轮。对于教学项目很直观；多轮复杂推理可能损失信息。
- `searchCode` 是候选文件的部分扫描，最多 12 个；没有全仓库语义检索，也不支持大文件分页。零结果必须说“扫描范围内没有”，不能说“仓库没有”。
- 目前回答路径只做弱约束：没有读到文件就拒绝有依据的结论；有文件但回答没提路径时附上已读路径。**并未自动核验每个结论是否真的由代码证明**，真实 LLM 验收需要人工检查。
