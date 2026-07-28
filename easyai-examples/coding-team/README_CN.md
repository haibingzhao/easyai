# Coding Team Agent — AI 专家团协作编码

## 场景描述

创建一个技术专家团 TEAM Agent，Leader 自动分析用户问题并协调 6 名专业成员并行工作。

适用场景：
- 需要多角色协作的复杂编码任务（研究 → 实现 → 测试 → 审查）
- 跨栈开发（前端 + 后端 + 数据库）
- Bug 诊断与修复（复现 → 定位 → 修复 → 验证）

## 团队成员

| 成员 | 职责 | 工具权限 |
|------|------|----------|
| Researcher | 研究分析、代码定位、依赖映射、环境检查、报告生成 | read, grep, glob, ls, bash, webfetch, websearch |
| Full-Stack Engineer | 前后端代码实现与修改、跨栈通用编码 | read, write, edit, grep, glob, ls, bash |
| QA | 运行测试和构建、收集验证证据、报告通过/失败 | read, bash, grep, glob, ls |
| Code Reviewer | 代码审查、识别潜在风险、提供改进建议（只读） | read, grep, glob, ls |
| UI Operator | 浏览器 UI 端到端验证、视觉 Bug 复现 | read, bash + MCP: browser-use |
| Debug Engineer | 故障复现、根因定位、缺陷诊断、修复建议 | read, grep, glob, ls, bash |

## 前置条件

- EasyAI 后端已启动（easyai-web-server 或 desktop）
- 已配置 LLM Model Provider（建议 Claude Sonnet 或 GPT-4o 以上）
- （可选）UI Operator 需连接 `browser-use` MCP Server

## AI 生成 Prompt

将以下内容粘贴到 **Agents → Create Agent → AI Panel（✨ 按钮）** 中：

---

创建一个名为 "Experts" 的 TEAM 类型 Agent，作为技术专家团 Leader 协调成员解决编码问题。

Leader 职责：分析用户问题 → 拆解子任务 → 分派合适成员 → 等待结果 → 综合输出。
Leader 自身只有只读工具（read, grep, glob, ls），不亲自写代码。

需要以下 6 个成员（customMembers）：

1. **Researcher** — 研究分析、代码定位、依赖映射、环境检查、报告生成
   工具：read, grep, glob, ls, bash, webfetch, websearch

2. **Full-Stack Engineer** — 前后端代码实现与修改、跨栈通用编码
   工具：read, write, edit, grep, glob, ls, bash

3. **QA** — 运行测试和构建、收集验证证据、报告通过/失败
   工具：read, bash, grep, glob, ls

4. **Code Reviewer** — 代码审查、识别潜在风险、提供改进建议（只读）
   工具：read, grep, glob, ls

5. **UI Operator** — 浏览器 UI 端到端验证、视觉 Bug 复现
   工具：read, bash
   MCP：browser-use（全部工具）

6. **Debug Engineer** — 故障复现、根因定位、缺陷诊断、修复建议
   工具：read, grep, glob, ls, bash

协调策略要求：
- 先派 Researcher 定位问题范围和上下文
- 再派 Full-Stack Engineer 实现代码修改
- QA 验证 + Code Reviewer 审查可并行
- 遇到 UI 问题时加入 UI Operator
- 遇到疑难 Bug 加入 Debug Engineer
- 独立子任务尽量并行分派
- 最终综合所有成员结果输出完整方案

---

## 预期生成结果

AI 将生成：
- `agentType`: TEAM
- `promptTemplate`: 包含协调策略和成员列表渲染（Jinja2）
- `customMembers`: 6 个成员，各含 systemPrompt + toolNames
- `toolNames`: [read, grep, glob, ls]（Leader 只读）

生成后点击 "Apply to Form" 检查配置，确认无误后 Save。

## 使用示例

创建完成后，在 Chat 中选择 "Experts" agent，输入：

- "给 UserService 添加分页查询，包含单元测试"
- "这个 NPE 怎么修？堆栈在 logs/app.log 第 42 行"
- "把 Dashboard 页面从 Class 组件重构为 Hooks"
- "分析这个项目的依赖关系，找出循环依赖"

Leader 会自动：
1. 分析问题类型和复杂度
2. 选择合适的成员组合
3. 拆解并行子任务并分派
4. 等待成员完成，综合输出最终方案

## 自定义建议

生成后可在表单中继续调整，或对 AI Panel 追加指令：

- "把 Code Reviewer 的 systemPrompt 改为遵循我们团队的审查清单"
- "添加一个 DBA 成员，负责数据库 Schema 设计和 SQL 优化"
- "去掉 UI Operator，我们项目没有前端"
- "把 maxIterations 调到 80，任务比较复杂"

## Prompt 设计要点

这个 prompt 的设计遵循以下原则（供你编写自己的 prompt 参考）：

1. **明确类型**：开头就说 "TEAM 类型 Agent"
2. **角色分离**：Leader 只协调不执行（只读工具）
3. **工具最小权限**：每个成员只给必需工具
4. **行为约束**：明确协调策略（先研究后实现，验证与审查并行）
5. **弹性调度**：UI Operator 和 Debug Engineer 按需加入
