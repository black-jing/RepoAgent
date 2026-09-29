# Repository Agent 参考项目设计借鉴表

核对日期：2026-09-29。主要阅读各项目官方仓库和框架文档；此表只提取与第一版相关的机制，不声称逐类审计全部源码。

| 参考项目 | 值得借鉴的设计 | 为什么 | 本项目简化采用 | 明确不采用 |
|---|---|---|---|---|
| [Spring AI](https://docs.spring.io/spring-ai/reference/api/tools.html) | 模型返回原生 Tool Call，Java 控制执行轮次；工具定义带参数 schema | Tool 的选择来自 LLM，每轮可观察 | `ChatModel` + `ToolCallingChatOptions`，Java 自己循环，每轮仅一项动作 | 自动运行的 `ToolCallingAdvisor`、记忆与向量组件 |
| [LangChain4j](https://docs.langchain4j.dev/tutorials/tools/) | Java 方法作为工具，模型请求与执行结果分离 | 适合解释 Java 与 LLM 职责 | 借鉴概念与测试思路 | 不同时引入第二套 AI 框架或 agentic 模块 |
| [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba) | Agent state、Tool 与 graph 分层 | 状态与执行分开便于追踪 | 一个请求内的 `AgentState` | Graph、持久化、MCP、多 Agent |
| [Lynxe（原 JManus）](https://github.com/spring-ai-alibaba/Lynxe) | 有计划与执行反馈的 Java Agent | 说明复杂 Agent 的完整边界 | 只读仓库探索这一单一目标 | 可视化工作台、Func Agent 协作、通用平台 |
| [Cline](https://github.com/cline/cline/blob/main/docs/tools-reference/all-cline-tools.mdx) | 模型选工具，执行结果返回模型；任务结束动作 | 可用 Trace 区分路径 | 单步 Tool/Observation/FINISH | Shell、编辑、浏览器、审批流 |
| [OpenHands](https://github.com/OpenHands/OpenHands) | Action/Observation 与 controller/state 概念 | 清楚地划分语义决策和执行环境 | 小型 `AgentLoop` + `AgentState` | runtime 沙箱、代码修改和多 Agent |

## 选择与边界

Java 21、Spring Boot 4.0.5、Spring AI 2.0.1。Spring AI 2.x 的低层 `ChatModel` 只返回 Tool Call，执行由 Java 控制，符合“Loop 必须能直接讲清楚”的要求。[框架文档](https://docs.spring.io/spring-ai/reference/api/tools.html)明确展示 user-controlled Tool execution；[版本兼容文档](https://docs.spring.io/spring-ai/reference/getting-started.html)说明 2.0.x 对应 Boot 4.0/4.1。LangChain4j 同样可做 Tool Calling，但这里不引入两套框架。

**调用协议**：每一步向模型提供目标、精简状态、最近 5 条 Observation、最多 6 份短证据片段及 5 个 Tool schema。模型选择一个原生 Tool Call，Java 校验并执行，结果被放入下一轮用户上下文；模型输出普通文本即 FINISH。这是逐轮重建的有界上下文，不依赖一个无限增长的原生 tool-message history。此取舍降低代码和 token 复杂度，但不能保留完整对话语义。

**工具**：`getFileTree`、`listFiles`、`readFile`、`searchCode`、`getReadme`。初始化时读取 GitHub 元数据及 Git tree 的**路径和文件大小**，没有下载整个仓库的文件内容。`searchCode` 仅检查最多 12 个候选文件，返回“部分搜索”；不能用零匹配证明功能不存在。

**终止**：主动 FINISH、10 步、连续 3 次工具错误、重复调用拦截、LLM 失败；每轮 Trace 记录动作、参数、观察摘要、耗时及终止原因。LLM 临时异常最多再试 1 次，GitHub 5xx 或 I/O 故障最多请求 2 次；认证和限额错误不立即重试。GitHub 429 应遵守限额，Token 可提升额度。

**限制**：当前还没有真实模型运行证据；Mock 决策测试只验证控制链，不验证模型的自主选路能力。没有可用 Key 时，不应把脚本化路径当作 Agent 成功率。复杂仓库树超过 5000 项或 API 返回被截断会明确拒绝。
