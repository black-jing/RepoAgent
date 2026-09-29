# 4 条公开仓库工具链 Trace

**证据级别**：真实 GitHub API 数据 + 脚本化 Mock 决策。决策顺序由测试代码预设；这些 Trace 证明 Tool → Observation → Loop 和多种工具顺序可运行，不能证明真实 LLM 自主选路。首次运行 2026-09-29。Trace 时间只计每轮，未计仓库 tree 初始化。

## spring-guides/gs-rest-service

用户：这个项目解决什么问题？

结果：See README.adoc；2 步，1 次 Tool，停止原因 MODEL_FINISH。

- Step 1: CALL_TOOL `getReadme({})`；Observation："FILE README.adoc\n:spring_version: current\n:toc:\n:project_id: gs-rest-service\n:icons: font\n:source-highlighter: prettify\n\nThis guide walks you through the process of creating a \"`Hello, World`\" RESTful web\nserv；214 ms；CONTINUE。
- Step 2: FINISH；Observation：Final answer；0 ms；MODEL_FINISH。

## black-jing/JobRadar

用户：大模型调用在哪里？

结果：See src/main/java/com/jobradar/analysis/deepseek/DeepSeekJobAnalyzer.java；3 步，2 次 Tool，停止原因 MODEL_FINISH。

- Step 1: CALL_TOOL `searchCode({"keyword":"DeepSeek"})`；Observation："PARTIAL SEARCH: scanned 12 of 93 files; 3 matches. src/main/java/com/jobradar/analysis/deepseek/DeepSeekJobAnalyzer.java: FILE src/main/java/com/jobradar/analysis/deepseek/DeepSeekJobAnalyzer.java package com.jobradar.a；2805 ms；CONTINUE。
- Step 2: CALL_TOOL `readFile({"path":"src/main/java/com/jobradar/analysis/deepseek/DeepSeekJobAnalyzer.java"})`；Observation："FILE src/main/java/com/jobradar/analysis/deepseek/DeepSeekJobAnalyzer.java\npackage com.jobradar.analysis.deepseek;\n\nimport com.fasterxml.jackson.core.JsonProcessingException;\nimport com.jobradar.analysis.JobAnalyzer；136 ms；CONTINUE。
- Step 3: FINISH；Observation：Final answer；0 ms；MODEL_FINISH。

## spring-projects/spring-petclinic

用户：OwnerController 到持久层如何连接？

结果：See owner/OwnerController.java and owner/OwnerRepository.java；3 步，2 次 Tool，停止原因 MODEL_FINISH。

- Step 1: CALL_TOOL `readFile({"path":"src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java"})`；Observation："FILE src/main/java/org/springframework/samples/petclinic/owner/OwnerController.java\n/*\n * Copyright 2012-2025 the original author or authors.\n *\n * Licensed under the Apache License, Version 2.0 (the \"License\");\n；253 ms；CONTINUE。
- Step 2: CALL_TOOL `readFile({"path":"src/main/java/org/springframework/samples/petclinic/owner/OwnerRepository.java"})`；Observation："FILE src/main/java/org/springframework/samples/petclinic/owner/OwnerRepository.java\n/*\n * Copyright 2012-2025 the original author or authors.\n *\n * Licensed under the Apache License, Version 2.0 (the \"License\");\n；255 ms；CONTINUE。
- Step 3: FINISH；Observation：Final answer；0 ms；MODEL_FINISH。

## langchain4j/langchain4j-examples

用户：Function Calling 示例在哪里？

结果：See open-ai-examples/src/main/java/OpenAiFunctionCallingExamples.java；3 步，2 次 Tool，停止原因 MODEL_FINISH。

- Step 1: CALL_TOOL `getFileTree({"depth":2})`；Observation：".github/\n.github/ISSUE_TEMPLATE/\n.github/workflows/\n.gitignore\n.mvn/\n.mvn/wrapper/\n.prettierrc\nLICENSE\nREADME.md\nagentic-tutorial/\nagentic-tutorial/pom.xml\nagentic-tutorial/src/\nanthropic-examples/\nanthropi；14 ms；CONTINUE。
- Step 2: CALL_TOOL `readFile({"path":"open-ai-examples/src/main/java/OpenAiFunctionCallingExamples.java"})`；Observation："FILE open-ai-examples/src/main/java/OpenAiFunctionCallingExamples.java\nimport dev.langchain4j.agent.tool.*;\nimport dev.langchain4j.data.message.AiMessage;\nimport dev.langchain4j.data.message.ChatMessage;\nimport dev.；197 ms；CONTINUE。
- Step 3: FINISH；Observation：Final answer；0 ms；MODEL_FINISH。

## 汇总（仅脚本化网络冒烟）

4 个仓库，4 条预设决策路径；4/4 到达预设文件并 FINISH；共 11 个 step、7 次 Tool Call。不能将此 4/4 写成真实 Agent 回答成功率。复测时匿名 GitHub API 返回 403，建议配置 `GITHUB_TOKEN` 再运行。
