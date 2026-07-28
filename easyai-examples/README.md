# EasyAI Examples

Learn EasyAI's core capability through real-world scenarios — **Use AI to Create AI**.

[🇨🇳 中文版](README_CN.md)

## Core Concept

EasyAI has a built-in AI Config Generator: describe your needs in natural language, and AI automatically generates complete Agent or Swarm Workflow configurations. Each example provides a carefully crafted prompt — just copy, paste, and generate.

## Available Examples

| Example | Scenario | Core Capabilities | Generation Method |
|---------|----------|-------------------|-------------------|
| [Coding Team](./coding-team/) | Expert team collaborative coding | TEAM Agent, member coordination, parallel execution | Agent AI Panel |
| [Investment Analysis](./investment-analysis/) | Full-pipeline investment analysis | Swarm DAG, DELIBERATION debates, MCP | Workflow AI Panel |

## Quick Start

1. Open the README of the example you want to try
2. Verify the prerequisites are met
3. Copy the content from the "AI Generation Prompt" section
4. Open the AI Panel (✨ button) on the corresponding Console page
5. Paste → Generate → Apply → Save

## Why AI Generation Instead of JSON Import?

- **Zero barrier**: No need to understand JSON Schema or field semantics
- **Conversational**: After generation, keep talking — "remove the QA member", "add a DBA member"
- **Auto-validation**: Built-in validate → fix loop guarantees config correctness
- **Resource-aware**: AI automatically discovers your tools, MCP servers, and models, configuring as needed
