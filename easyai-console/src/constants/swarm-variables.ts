/**
 * Swarm prompt template variable definitions.
 * These reflect the variables actually populated during Swarm task prompt rendering
 * (SwarmWorkerExecutor.renderPrompt → PromptContext.toModel()).
 *
 * Variables NOT listed here (tools, skills, sub_agents, instructions, project, memory, cwd, protocol, input)
 * are always empty in the Swarm context and should NOT be shown in variable dropdowns.
 */

/** Variables available in task promptTemplate (UserMessage rendering). */
export const SWARM_PROMPT_VARIABLES: { name: string; description: string }[] = [
  { name: 'agent', description: 'Agent info (access: agent.id, agent.name, agent.description)' },
  { name: 'custom_instructions', description: 'Agent custom instructions text' },
  { name: 'model_id', description: 'Active model identifier' },
  { name: 'os', description: 'Operating system name' },
  { name: 'current_date_time', description: 'Current date/time (yyyy-MM-dd HH:mm:ss z)' },
];

/**
 * Variables available in systemPromptTemplate (pre-rendered with only userVars + inputFromVars).
 * The systemPromptTemplate does NOT have access to PromptContext variables —
 * only workflow variables and inputFrom aliases are available (shown dynamically).
 */
export const SWARM_SYSTEM_PROMPT_VARIABLES: { name: string; description: string }[] = [
  // systemPromptTemplate only supports workflow variables and inputFrom aliases.
  // These are shown dynamically from the preset's variable definitions and inputFrom config.
  // No static PromptContext variables are available here.
];
