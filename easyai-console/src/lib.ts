/**
 * Library entry point for @easyai/console.
 *
 * This file exports the public API surface that external consumers
 * (e.g. trading-console) can import when using this package as a library.
 *
 * Usage:
 *   import { ChatPanel, useChatStore, authFetch } from '@easyai/console';
 *   import '@easyai/console/style.css';
 */

import './index.css';

// === Services ===
export {
  authFetch,
  fetchJson,
  fetchVoid,
  setAccessToken,
  getAccessToken,
  downloadBlob,
  JSON_HEADERS,
} from './services/api-client';

export {
  parseSSEStream,
  SSEConnectionError,
  type RawSSEEvent,
} from './services/sse-parser';

export {
  ChatService,
  sendMessageToBackend,
  resumeSession,
  answerQuestion,
  compactSession,
  watchSession,
  abortAllActiveStreams,
  cancelChat,
  replyPermission,
  getStreamingStatus,
  addQueueMessage,
  getQueueMessages,
  removeQueueMessage,
  type SendMessageParams,
  type QueuedMessageDto,
} from './services/chat-service';

export {
  SessionService,
  sessionService,
  type SessionListItem,
  type MessageSnapshot,
  type ContentBlock,
  type ToolResultSnapshot,
  type ReferencesSnapshot,
} from './services/session-service';

export { AgentService, agentService } from './services/agent-service';

export { storageService } from './services/storage-service';

// Group-sharing capability probe (`GET /api/capabilities`): warmed by auth-store on every auth
// transition, read by host apps to gate their group-configuration screens.
export { capabilityService } from './services/capability-service';
export type { AssetKind, AssetCapability, Capabilities } from './services/capability-service';

// === Stores ===
export { useChatStore, type SwarmRunTracking } from './services/stores/chat-store';
export { useAuthStore } from './services/stores/auth-store';
export { useSettingsStore } from './services/stores/settings-store';
export { useSessionStore } from './services/stores/session-store';
export { useAgentStore } from './services/stores/agent-store';
export { useCategoryStore } from './services/stores/category-store';
export { useCapabilityStore, selectCanManage } from './services/stores/capability-store';
export { convertSnapshot } from './services/stores/chat/message-converter';
export { mergeToolResults } from './services/stores/chat/session-loader';

// === Components ===
export { ChatPanel } from './components/chat/ChatPanel';
export { MessageList } from './components/chat/MessageList';
export { RenderVisualToolMessage } from './components/chat/tools/RenderVisualToolMessage';
export { AppLayout, SIDEBAR_COLLAPSED_WIDTH } from './components/layout/AppLayout';
export { Sidebar } from './components/layout/Sidebar';
export { ReadOnlyBanner } from './components/ui/ReadOnlyBanner';

// === Types ===
export type { ToolCallStatus, SocketEvent } from './types/socket-event';
export type {
  UserMessage,
  AssistantMessage,
  ToolCall,
  ToolResult,
  ToolResultMessage,
  SubAgentMessageGroup,
  Attachment,
  ContextReferences,
} from './types/message';

export type {
  Settings,
  ModelInfo,
  ModelProviderInfo,
  ModelProviderConfig,
  StorageConfig,
  StorageBackendType,
  StorageEffectiveSource,
  StorageTestResult,
} from './types/settings';

export type { NavItem } from './types/layout';

// === Navigation constants ===
export {
  NAV_ITEMS,
  EXTRA_NAV_ITEMS,
  TOPBAR_EXTRA_ITEMS,
  APP_CONFIG,
  ICON_REGISTRY,
  registerIcons,
  // Consumers that render their own nav (or highlight a nav entry elsewhere, e.g. a
  // breadcrumb) use these to agree with the Sidebar on what counts as "current".
  matchesNavPath,
  resolveActiveNavItem,
} from './constants/navigation';

// === Page components (for external routing) ===
export { WorkflowPage } from './pages/WorkflowPage';
export { AgentsPage } from './pages/AgentsPage';
export { AgentCreatePage } from './pages/AgentCreatePage';
export { ModelsPage } from './pages/ModelsPage';
export { McpPage } from './pages/McpPage';
export { MemoriesPage } from './pages/MemoriesPage';
export { KnowledgePage } from './pages/KnowledgePage';
export { CommandsPage } from './pages/CommandsPage';
export { SkillsPage } from './pages/SkillsPage';
export { SettingsPage } from './pages/SettingsPage';
export { SwarmPresetEditorPage } from './pages/SwarmPresetEditorPage';
export { WorkflowRunPage } from './pages/WorkflowRunPage';
export { LoginPage } from './pages/LoginPage';
export { ProjectSelectPage } from './components/project/ProjectSelectPage';
export { DatabaseSetupPage } from './pages/DatabaseSetupPage';

// === Utility ===
export { setTheme } from './utils/theme';
export { setupService } from './services/setup-service';
export { useProjectStore } from './services/stores/project-store';
export { useFeatureStore } from './services/stores/feature-store';
export { useNavStore } from './services/stores/nav-store';

// === Full App (for embedding the complete easyai-console experience) ===
export { default as EasyAiApp } from './App';
