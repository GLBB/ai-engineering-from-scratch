# Agent Loop 学习指南

这组文档带你从浏览器输入出发，找到模型调用、工具执行和轨迹展示对应的代码。阅读时不需要先了解 Spring Boot 的全部细节，重点是看清数据怎样在各部分之间流动。

## 建议阅读顺序

1. [项目全貌](01-architecture.md)：这个项目能做什么，各个包负责什么。
2. [一次对话如何运行](02-agent-loop.md)：沿着一条需要计算的消息，理解会话、模型、工具和循环。
3. [协议、流式输出和轨迹](03-protocols-and-streaming.md)：理解三种 DeepSeek 接口如何接入，以及页面怎样收到实时结果。
4. [动手运行和调试](04-run-and-debug.md)：启动项目、查看轨迹、设置断点和运行测试。
5. [工具调用上限、终止条件和对话记录](05-tool-calls-and-transcripts.md)：运行离线实验，对比四种 API 的调用与结果格式。
6. [用同一段对话理解三种协议](06-three-protocols-by-example.md)：用固定颜色标出所有内容来源，对照完整请求、响应、角色和思考字段。
7. [通过 Trace 理解项目](07-reading-agent-traces.md)：对照三种模式的示意树与已采集的真实 Trace，理解天气请求、调用计数与结束原因。

已经熟悉 Agent 基本概念的话，可以直接从 [ReActRunner.java](../src/main/java/dev/learning/agent/core/react/ReActRunner.java) 开始读，再根据调用关系返回文档。

## 开发设计与研究

[规范目录](standards/README.md)中的 [Java 开发规范](standards/java-development-standard.md) 指定采用的阿里 Java 手册版本与 Google 评审方法，仅补充项目约束、工具覆盖范围和执行命令；开始修改前还需阅读项目根目录的 [AGENTS.md](../AGENTS.md)。

[三模式技术方案](design/agent-execution-technical-design.md) 是开发时的统一依据，确定接口、算法、逐轮模式选择与共享记忆、异常、文件改造和测试；[研究记录](design/agent-execution-research.md)只保存来源与事实依据。上述 01–07 篇解释当前已实现代码，复习总结见 [review-notes](../../review-notes/README.md)。三模式已实现，具体测试和真实接口记录见研究记录。

[天气能力实现方案](design/weather-capability-implementation-plan.md) 定义当前天气与逐日预报两个工具，说明城市名如何转换为 API 经纬度、同名地点如何确认，以及三模式的接入与验收；天气工具及澄清流程已接入，实际验证范围记录在方案末尾。

[OpenTelemetry Trace 接入技术方案](design/observability-trace-implementation-plan.md) 记录本机 Langfuse 检查、官方 GenAI 规范快照、span 设计、异步 Context、字段映射、内容边界与实施验收；代码已接入独立 Langfuse 项目，真实样例及回读结果见方案第 11 节。

## 先记住三个概念

| 概念 | 在本项目里的含义 |
| --- | --- |
| 一次用户轮次 | 用户发送一条消息，到 Agent 给出最终回答或报错为止。 |
| 一次模型请求 | Agent 向 DeepSeek 发送当前历史并接收一个结果。一个用户轮次可能包含多次模型请求。 |
| 工具调用 | 模型给出工具名称和参数，Java 程序实际执行工具，并把结果放入历史，再请求模型。 |

示例：用户要求计算 `(120+18)/3`，模型可以先请求调用 `calculator`；程序得到 `46` 后再问模型，模型最后回答用户。在这个例子里，一次用户轮次包含两次模型请求和一次工具调用。模型也可能直接回答，因此实际轨迹不一定相同。
