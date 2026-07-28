# Investment Analysis — AI 全流程投资分析工作流

## 场景描述

14 个 AI 分析师组成投资委员会，对指定标的执行完整的投资分析流程：

```
数据采集 → 四维并行分析 → 多空辩论 → 交易方案 → 风控辩论 → 最终决策
```

适用场景：
- 个股/ETF/加密货币的深度投资研究
- 多维度（技术面 + 新闻面 + 情绪面 + 基本面）交叉验证
- 通过对抗式辩论（Bull vs Bear）减少分析偏见
- 生成结构化交易方案（Action / Entry / StopLoss / PositionSize）

## 工作流 DAG 拓扑

```
[AnalystStater] ─── 获取标的上下文，更新变量
       │
       ▼
┌──────────────────────────────────────────┐
│  [Market]  [News]  [Sentiment]  [Fundamentals]  │  ← 4 路并行
└──────────────────────────────────────────┘
       │
       ▼
[BullBearDebate] ─── Bull vs Bear 辩论, Research Manager 裁决
       │
       ▼
[Trader] ─── 制定结构化交易方案
       │
       ▼
[RiskDebate] ─── Aggressive vs Conservative vs Neutral, Portfolio Manager 裁决
```

## 前置条件

1. **启用 Swarm**：`application.properties` 中设置 `easyai.swarm.enabled=true`
2. **配置 trading MCP Server**：在 Console → MCP 页面添加名为 `trading` 的 Server，需提供以下工具：

   | 工具 | 用途 | 使用者 |
   |------|------|--------|
   | `instrument_context` | 标的身份信息 | Analyst (Stater) |
   | `market_data` | 历史行情数据 | Market Analyst |
   | `market_snapshot` | 实时行情快照 | Market Analyst |
   | `technical_indicators` | 技术指标计算 | Market Analyst |
   | `company_news` | 个股新闻 | News Analyst, Sentiment Analyst |
   | `global_news` | 全球宏观新闻 | News Analyst |
   | `fred_data` | FRED 宏观经济数据 | News Analyst |
   | `prediction_market` | 预测市场概率 | News Analyst |
   | `social_sentiment` | 社交媒体情绪 | Sentiment Analyst |
   | `stock_profile` | 公司概况 | Fundamentals Analyst |
   | `sector_info` | 行业信息 | Fundamentals Analyst |
   | `financial_statements` | 财务报表 | Fundamentals Analyst |

3. **已配置 LLM Model Provider**（建议 Claude Sonnet 或 GPT-4o 以上）

## AI 生成 Prompt

将以下内容粘贴到 **Workflow → Create Preset → AI Panel（✨ 按钮）** 中：

---

创建一个投资分析 Swarm Workflow，名称 "easy-trading"，标题 "Trading Pipeline"，语言 zh-CN。

## 工作流 DAG 设计

**Layer 0 — 标的初始化（SINGLE）**
- Agent: "Analyst"（首席分析师）
- 职责：调用 instrument_context 获取标的身份信息，更新变量 symbol、analyze_date、instrument_context
- MCP 工具：instrument_context
- 该 task 设置 updatableVariables: [instrument_context, symbol, analyze_date]

**Layer 1 — 四维并行分析（4 个 SINGLE task，dependsOn Layer 0）**

1. Market Analyst — 技术面分析
   MCP 工具：market_data, market_snapshot, technical_indicators
   内置工具：calc
   输出：详细技术分析报告（含支撑/阻力位、指标信号、Markdown 表格）

2. News Analyst — 宏观新闻分析
   MCP 工具：company_news, global_news, fred_data, prediction_market
   内置工具：calc
   输出：宏观事件影响评估报告

3. Sentiment Analyst — 市场情绪分析
   MCP 工具：company_news, social_sentiment
   内置工具：calc
   输出：多源情绪评分（Bullish/Bearish）+ 叙事分析

4. Fundamentals Analyst — 财务基本面分析
   MCP 工具：stock_profile, sector_info, financial_statements
   内置工具：calc
   输出：三大报表分析 + 估值评估

**Layer 2 — 多空辩论（DELIBERATION，dependsOn Layer 1 全部）**
- participants: Bull Researcher（看多）, Bear Researcher（看空）
- judge: Research Manager
- maxRounds: 3
- contextTemplate：注入 4 份分析报告 + instrument_context 作为辩论素材
- Judge 输出：投资计划（含评级 Buy/Overweight/Hold/Underweight/Sell）

**Layer 3 — 交易方案（SINGLE，dependsOn Layer 2）**
- Agent: Trader
- 输入：辩论裁决结果（inputFrom: BullBearDebate）
- 输出：结构化交易方案（Action, Reasoning, Entry Price, Stop Loss, Position Sizing）

**Layer 4 — 风控辩论（DELIBERATION，dependsOn Layer 3）**
- participants: Aggressive Analyst, Conservative Analyst, Neutral Analyst
- judge: Portfolio Manager
- maxRounds: 3
- contextTemplate：注入 Trader 决策 + 4 份分析报告
- Judge 输出：最终交易决策

## Variables
- user_input（required）：用户分析请求
- symbol（updatable）：标的代码
- analyze_date（updatable）：分析日期
- instrument_context（updatable）：标的上下文信息

## 所有 agent 使用 inline 模式（agentDefinitionId 为空），每个 agent 的 timeoutSeconds=900, maxRetries=2

---

## 预期生成结果

AI 将生成完整的 Swarm Preset：
- 14 个 inline agents（各含 systemPrompt + toolNames + mcpConfigs）
- 9 个 tasks（含 dependsOn 依赖关系、DELIBERATION 配置、inputFrom 路由）
- 4 个 variables（含 updatable 标记）
- DAG 拓扑自动验证通过

生成后点击 "Apply to Form" 检查配置，确认无误后 Save。

## 使用示例

保存后在 Workflow 页面选择 "Easy Trading" preset，输入变量：

| 变量 | 示例值 | 说明 |
|------|--------|------|
| user_input | "分析苹果公司近期投资价值" | 必填，分析请求 |
| symbol | "AAPL.US" | 可选，Stater 会自动解析 |
| analyze_date | "2026-07-25" | 可选，默认今天 |

点击 Run 后预期耗时 10-20 分钟，最终输出包含：
- 四维分析报告（技术面 / 新闻面 / 情绪面 / 基本面）
- 多空辩论记录（3 轮 Bull vs Bear 对抗）
- 结构化交易方案（BUY/HOLD/SELL + 入场价 + 止损位 + 仓位建议）
- 风控辩论 + Portfolio Manager 最终决策

## 自定义建议

生成后可在表单中调整，或对 AI Panel 追加指令：

- "把 maxRounds 改为 2，辩论太长了"
- "添加一个 Options Analyst，分析期权市场隐含波动率"
- "把 language 改为 en-US"
- "给 Market Analyst 的 timeoutSeconds 改为 1200，技术分析比较慢"
- "去掉 RiskDebate 阶段，直接输出 Trader 结果"

## Prompt 设计要点

这个 prompt 的设计遵循以下原则（供你编写自己的 prompt 参考）：

1. **分层描述 DAG**：用 Layer 0/1/2/3/4 明确拓扑层级和并行关系
2. **指定 dependsOn**：每层明确依赖上一层的哪些 task
3. **工具白名单**：每个 agent 精确列出 MCP 工具，不让 AI 猜测
4. **DELIBERATION 三要素**：participants + judge + maxRounds
5. **变量路由**：明确 updatable 和 inputFrom 的数据流向
6. **全局约束**：inline 模式、timeout、maxRetries 统一指定
