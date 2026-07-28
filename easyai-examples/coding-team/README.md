# Coding Team Agent — AI Expert Team Collaborative Coding

[🇨🇳 中文版](README_CN.md)

## Scenario

Create a TEAM Agent of technical experts where the Leader automatically analyzes user problems and coordinates 6 specialized members working in parallel.

Use cases:
- Complex coding tasks requiring multi-role collaboration (Research → Implement → Test → Review)
- Cross-stack development (frontend + backend + database)
- Bug diagnosis and fixing (reproduce → locate → fix → verify)

## Team Members

| Member | Responsibilities | Tool Permissions |
|--------|-----------------|------------------|
| Researcher | Research analysis, code location, dependency mapping, environment checks, report generation | read, grep, glob, ls, bash, webfetch, websearch |
| Full-Stack Engineer | Frontend/backend code implementation and modification, cross-stack coding | read, write, edit, grep, glob, ls, bash |
| QA | Run tests and builds, collect verification evidence, report pass/fail | read, bash, grep, glob, ls |
| Code Reviewer | Code review, identify potential risks, provide improvement suggestions (read-only) | read, grep, glob, ls |
| UI Operator | Browser UI end-to-end verification, visual bug reproduction | read, bash + MCP: browser-use |
| Debug Engineer | Fault reproduction, root cause analysis, defect diagnosis, fix recommendations | read, grep, glob, ls, bash |

## Prerequisites

- EasyAI backend is running (easyai-web-server or desktop)
- LLM Model Provider configured (Claude Sonnet or GPT-4o+ recommended)
- (Optional) UI Operator requires a connected `browser-use` MCP Server

## AI Generation Prompt

Paste the following into **Agents → Create Agent → AI Panel (✨ button)**:

---

Create a TEAM-type Agent named "Experts" that acts as a technical expert team Leader coordinating members to solve coding problems.

Leader responsibilities: analyze user problem → decompose subtasks → assign appropriate members → wait for results → synthesize output.
The Leader itself only has read-only tools (read, grep, glob, ls) and never writes code directly.

The following 6 members (customMembers) are needed:

1. **Researcher** — Research analysis, code location, dependency mapping, environment checks, report generation
   Tools: read, grep, glob, ls, bash, webfetch, websearch

2. **Full-Stack Engineer** — Frontend/backend code implementation and modification, cross-stack coding
   Tools: read, write, edit, grep, glob, ls, bash

3. **QA** — Run tests and builds, collect verification evidence, report pass/fail
   Tools: read, bash, grep, glob, ls

4. **Code Reviewer** — Code review, identify potential risks, provide improvement suggestions (read-only)
   Tools: read, grep, glob, ls

5. **UI Operator** — Browser UI end-to-end verification, visual bug reproduction
   Tools: read, bash
   MCP: browser-use (all tools)

6. **Debug Engineer** — Fault reproduction, root cause analysis, defect diagnosis, fix recommendations
   Tools: read, grep, glob, ls, bash

Coordination strategy requirements:
- First dispatch Researcher to locate the problem scope and context
- Then dispatch Full-Stack Engineer to implement code changes
- QA verification + Code Reviewer review can run in parallel
- Add UI Operator when encountering UI issues
- Add Debug Engineer for tricky bugs
- Dispatch independent subtasks in parallel whenever possible
- Finally synthesize all member results into a complete solution

---

## Expected Generation Result

AI will generate:
- `agentType`: TEAM
- `promptTemplate`: Contains coordination strategy and member list rendering (Jinja2)
- `customMembers`: 6 members, each with systemPrompt + toolNames
- `toolNames`: [read, grep, glob, ls] (Leader is read-only)

After generation, click "Apply to Form" to inspect the config, then Save once confirmed.

## Usage Examples

After creation, select the "Experts" agent in Chat and type:

- "Add paginated queries to UserService, including unit tests"
- "How do I fix this NPE? Stack trace is at logs/app.log line 42"
- "Refactor the Dashboard page from Class components to Hooks"
- "Analyze this project's dependencies and find circular dependencies"

The Leader will automatically:
1. Analyze the problem type and complexity
2. Select the appropriate member combination
3. Decompose parallel subtasks and dispatch
4. Wait for members to complete, then synthesize the final solution

## Customization Tips

After generation, adjust in the form or give additional instructions to the AI Panel:

- "Change the Code Reviewer's systemPrompt to follow our team's review checklist"
- "Add a DBA member responsible for database schema design and SQL optimization"
- "Remove UI Operator, our project has no frontend"
- "Set maxIterations to 80, the tasks are complex"

## Prompt Design Principles

This prompt follows these design principles (for reference when writing your own):

1. **Explicit type**: State "TEAM-type Agent" at the very beginning
2. **Role separation**: Leader only coordinates, never executes (read-only tools)
3. **Least privilege**: Each member gets only the tools they need
4. **Behavioral constraints**: Explicit coordination strategy (research first, then implement; verification and review in parallel)
5. **Elastic scheduling**: UI Operator and Debug Engineer join on demand
