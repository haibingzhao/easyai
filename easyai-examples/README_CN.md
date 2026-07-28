# EasyAI Examples

通过真实场景学习 EasyAI 的核心能力——用 AI 创建 AI。

## 核心理念

EasyAI 内置 AI 配置生成器：你只需用自然语言描述需求，AI 自动生成完整的 Agent 或 Swarm Workflow 配置。
每个 Example 提供精心设计的 prompt，复制粘贴即可一键生成。

## 可用示例

| Example | 场景 | 核心能力 | 生成方式 |
|---------|------|----------|----------|
| [Coding Team](./coding-team/) | 专家团协作编码 | TEAM Agent、成员协调、并行执行 | Agent AI Panel |
| [Investment Analysis](./investment-analysis/) | 全流程投资分析 | Swarm DAG、DELIBERATION 辩论、MCP | Workflow AI Panel |

## 快速上手

1. 打开对应 Example 的 README
2. 确认前置条件已满足
3. 复制 "AI 生成 Prompt" 部分的内容
4. 在 Console 对应页面打开 AI Panel（✨ 按钮）
5. 粘贴 → Generate → Apply → Save

## 为什么用 AI 生成而不是导入 JSON？

- **零门槛**：不需要理解 JSON Schema、字段含义
- **可对话**：生成后可以继续对 AI 说"把 QA 成员去掉"、"加一个 DBA 成员"
- **自动验证**：AI 生成器内置 validate → fix 循环，保证配置正确性
- **资源感知**：AI 会自动发现你环境中的工具、MCP Server、模型，按需配置
