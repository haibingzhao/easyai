# Investment Analysis — AI Full-Pipeline Investment Analysis Workflow

[🇨🇳 中文版](README_CN.md)

## Scenario

14 AI analysts form an investment committee to execute a complete investment analysis pipeline on a specified instrument:

```
Data Collection → 4D Parallel Analysis → Bull/Bear Debate → Trade Plan → Risk Debate → Final Decision
```

Use cases:
- Deep investment research on stocks / ETFs / cryptocurrencies
- Multi-dimensional cross-validation (technical + news + sentiment + fundamentals)
- Reduce analysis bias through adversarial debates (Bull vs Bear)
- Generate structured trade plans (Action / Entry / StopLoss / PositionSize)

## Workflow DAG Topology

```
[AnalystStater] ─── Fetch instrument context, update variables
       │
       ▼
┌──────────────────────────────────────────┐
│  [Market]  [News]  [Sentiment]  [Fundamentals]  │  ← 4-way parallel
└──────────────────────────────────────────┘
       │
       ▼
[BullBearDebate] ─── Bull vs Bear debate, Research Manager judges
       │
       ▼
[Trader] ─── Formulate structured trade plan
       │
       ▼
[RiskDebate] ─── Aggressive vs Conservative vs Neutral, Portfolio Manager judges
```

## Prerequisites

1. **Enable Swarm**: Set `easyai.swarm.enabled=true` in `application.properties`
2. **Configure trading MCP Server**: Add a server named `trading` in Console → MCP page, providing the following tools:

   | Tool | Purpose | Used By |
   |------|---------|---------|
   | `instrument_context` | Instrument identity info | Analyst (Stater) |
   | `market_data` | Historical market data | Market Analyst |
   | `market_snapshot` | Real-time market snapshot | Market Analyst |
   | `technical_indicators` | Technical indicator calculation | Market Analyst |
   | `company_news` | Company-specific news | News Analyst, Sentiment Analyst |
   | `global_news` | Global macro news | News Analyst |
   | `fred_data` | FRED macroeconomic data | News Analyst |
   | `prediction_market` | Prediction market probabilities | News Analyst |
   | `social_sentiment` | Social media sentiment | Sentiment Analyst |
   | `stock_profile` | Company overview | Fundamentals Analyst |
   | `sector_info` | Sector information | Fundamentals Analyst |
   | `financial_statements` | Financial statements | Fundamentals Analyst |

3. **LLM Model Provider configured** (Claude Sonnet or GPT-4o+ recommended)

## AI Generation Prompt

Paste the following into **Workflow → Create Preset → AI Panel (✨ button)**:

---

Create an investment analysis Swarm Workflow named "easy-trading", titled "Trading Pipeline", language en-US.

## Workflow DAG Design

**Layer 0 — Instrument Initialization (SINGLE)**
- Agent: "Analyst" (Chief Analyst)
- Responsibility: Call instrument_context to fetch instrument identity info, update variables symbol, analyze_date, instrument_context
- MCP tools: instrument_context
- Set updatableVariables: [instrument_context, symbol, analyze_date] for this task

**Layer 1 — 4D Parallel Analysis (4 SINGLE tasks, dependsOn Layer 0)**

1. Market Analyst — Technical analysis
   MCP tools: market_data, market_snapshot, technical_indicators
   Built-in tools: calc
   Output: Detailed technical analysis report (support/resistance levels, indicator signals, Markdown tables)

2. News Analyst — Macro news analysis
   MCP tools: company_news, global_news, fred_data, prediction_market
   Built-in tools: calc
   Output: Macro event impact assessment report

3. Sentiment Analyst — Market sentiment analysis
   MCP tools: company_news, social_sentiment
   Built-in tools: calc
   Output: Multi-source sentiment scores (Bullish/Bearish) + narrative analysis

4. Fundamentals Analyst — Financial fundamentals analysis
   MCP tools: stock_profile, sector_info, financial_statements
   Built-in tools: calc
   Output: Three-statement analysis + valuation assessment

**Layer 2 — Bull/Bear Debate (DELIBERATION, dependsOn all of Layer 1)**
- participants: Bull Researcher (bullish), Bear Researcher (bearish)
- judge: Research Manager
- maxRounds: 3
- contextTemplate: Inject 4 analysis reports + instrument_context as debate material
- Judge output: Investment plan (with rating Buy/Overweight/Hold/Underweight/Sell)

**Layer 3 — Trade Plan (SINGLE, dependsOn Layer 2)**
- Agent: Trader
- Input: Debate verdict (inputFrom: BullBearDebate)
- Output: Structured trade plan (Action, Reasoning, Entry Price, Stop Loss, Position Sizing)

**Layer 4 — Risk Debate (DELIBERATION, dependsOn Layer 3)**
- participants: Aggressive Analyst, Conservative Analyst, Neutral Analyst
- judge: Portfolio Manager
- maxRounds: 3
- contextTemplate: Inject Trader decision + 4 analysis reports
- Judge output: Final trade decision

## Variables
- user_input (required): User's analysis request
- symbol (updatable): Instrument ticker
- analyze_date (updatable): Analysis date
- instrument_context (updatable): Instrument context information

## All agents use inline mode (agentDefinitionId is empty), each agent's timeoutSeconds=900, maxRetries=2

---

## Expected Generation Result

AI will generate a complete Swarm Preset:
- 14 inline agents (each with systemPrompt + toolNames + mcpConfigs)
- 9 tasks (with dependsOn dependencies, DELIBERATION configs, inputFrom routing)
- 4 variables (with updatable flags)
- DAG topology auto-validation passes

After generation, click "Apply to Form" to inspect the config, then Save once confirmed.

## Usage Example

After saving, select the "Easy Trading" preset on the Workflow page and enter variables:

| Variable | Example Value | Description |
|----------|---------------|-------------|
| user_input | "Analyze Apple's recent investment value" | Required, analysis request |
| symbol | "AAPL.US" | Optional, Stater will auto-resolve |
| analyze_date | "2026-07-25" | Optional, defaults to today |

Click Run — expected duration is 10-20 minutes. Final output includes:
- 4D analysis reports (Technical / News / Sentiment / Fundamentals)
- Bull/Bear debate transcript (3 rounds of adversarial debate)
- Structured trade plan (BUY/HOLD/SELL + entry price + stop loss + position sizing)
- Risk debate + Portfolio Manager final decision

## Customization Tips

After generation, adjust in the form or give additional instructions to the AI Panel:

- "Change maxRounds to 2, the debates are too long"
- "Add an Options Analyst to analyze implied volatility from the options market"
- "Change language to zh-CN"
- "Set Market Analyst's timeoutSeconds to 1200, technical analysis is slow"
- "Remove the RiskDebate stage, output Trader results directly"

## Prompt Design Principles

This prompt follows these design principles (for reference when writing your own):

1. **Layered DAG description**: Use Layer 0/1/2/3/4 to clarify topology levels and parallelism
2. **Explicit dependsOn**: Each layer specifies which tasks from the previous layer it depends on
3. **Tool whitelist**: Each agent precisely lists MCP tools — don't let AI guess
4. **DELIBERATION essentials**: participants + judge + maxRounds
5. **Variable routing**: Clearly define updatable and inputFrom data flow
6. **Global constraints**: Inline mode, timeout, maxRetries specified uniformly
