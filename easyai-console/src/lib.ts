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
  // Group-sharing write scope: hosts fold group ownership into login, then call
  // setDefaultAssetScope('group') once so every console write routes to the shared bucket.
  setDefaultAssetScope,
  resolveWriteScope,
  scopeQuery,
  withScope,
} from './services/api-client';
export type { AssetScope } from './services/api-client';

// Scope-aware asset services: exported so hosts can drive model / MCP / skill / storage / task-model
// writes directly (passing an explicit `scope`) instead of relying on setDefaultAssetScope.
export { modelConfigService } from './services/model-config-service';
export { mcpService, McpService } from './services/mcp-service';
export { SkillService, SHARED_OWNER_ID } from './services/skill-service';
export { auxModelConfigService } from './services/aux-model-config-service';
export { storageConfigService } from './services/storage-config-service';

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
  requireStreamingStatus,
  addQueueMessage,
  getQueueMessages,
  removeQueueMessage,
  type SendMessageParams,
  type QueuedMessageDto,
  type CancelStatus,
  type CancelOutcome,
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

// Raw auth service: hosts that drive their own group-selection UI call `switchGroup(groupId)` /
// `login(u, p, groupId)` to re-mint tokens under a group. `useAuthStore` wraps these with token
// application + capability reload; export the service for hosts that manage that themselves.
export { authService, AuthService } from './services/auth-service';
export type { AuthResponse, UserProfile } from './services/auth-service';

export { storageService } from './services/storage-service';

// Durable async-task plane (global Tasks page). Exported so a host can mount
// its own tasks surface or drive task control directly.
export { taskService } from './services/task-service';

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
export { useTaskStore } from './services/stores/task-store';
export { useCapabilityStore, selectCanManage } from './services/stores/capability-store';
export { convertSnapshot } from './services/stores/chat/message-converter';
export { mergeToolResults } from './services/stores/chat/session-loader';

// === Components ===
export { ChatPanel } from './components/chat/ChatPanel';
export { MessageList } from './components/chat/MessageList';
export { RenderVisualToolMessage } from './components/chat/tools/RenderVisualToolMessage';
// Streaming-safe markdown renderer used by chat text segments; hosts reuse it for
// any LLM-authored text so rendering stays identical to the chat bubbles.
export { TextMarkdown } from './components/chat/segments/TextMarkdown';
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

// === Async task registry + types (extensible task presentation) ===
// A host registers its own backend task types by adding an entry to TASK_TYPE_REGISTRY, so the
// Tasks page renders them without a page change.
export {
  TASK_TYPE_REGISTRY,
  DEFAULT_TASK_DESCRIPTOR,
  getTaskDescriptor,
  taskTypeLabel,
} from './constants/task-types';
export type {
  AsyncTask,
  AsyncTaskType,
  AsyncTaskStatus,
  TaskTrigger,
  TaskFilters,
  TaskTypeDescriptor,
} from './types/task';

// === Page components (for external routing) ===
export { WorkflowPage } from './pages/WorkflowPage';
export { AgentsPage } from './pages/AgentsPage';
export { AgentCreatePage } from './pages/AgentCreatePage';
export { ModelsPage } from './pages/ModelsPage';
export { McpPage } from './pages/McpPage';
export { MemoriesPage } from './pages/MemoriesPage';
export { TasksPage } from './pages/TasksPage';
export { TaskTranscriptPage } from './pages/TaskTranscriptPage';
export { KnowledgePage } from './pages/KnowledgePage';
export { ArticleEditorPage } from './pages/ArticleEditorPage';
export { CommandsPage } from './pages/CommandsPage';
export { SkillsPage } from './pages/SkillsPage';
export { SettingsPage } from './pages/SettingsPage';
// Mutable Settings tab list — hosts trim tabs they don't surface (e.g. home-console drops
// database / rag / integrations) by splicing this array before render.
export { SETTINGS_MENU_ITEMS } from './pages/SettingsPage';
export type { SettingsMenuItem } from './pages/SettingsPage';
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

// === Mobile (iPhone web / PWA / WKWebView shell) ===
// The shell a host app mounts at its own `mobile.html`: same services, same stores, same
// transcript renderers, phone layout. Hosts add tabs and composer entries through the mutable
// registries below — the same idiom as EXTRA_NAV_ITEMS.
export { MobileAppLayout } from './mobile/MobileAppLayout';
export { MobileGate } from './mobile/MobileGate';
export { MobileApp } from './mobile/MobileApp';
export { MobileChatPage } from './mobile/pages/MobileChatPage';
export { MobileHistoryPage } from './mobile/pages/MobileHistoryPage';
export { MobileProfilePage } from './mobile/pages/MobileProfilePage';
export { MobileMorePage } from './mobile/pages/MobileMorePage';
export { MobileToolDetailPage } from './mobile/pages/MobileToolDetailPage';
export { MobileArtifactPage } from './mobile/pages/MobileArtifactPage';
export { MobileLoginPage } from './mobile/pages/MobileLoginPage';
export { MobileProjectPage } from './mobile/pages/MobileProjectPage';
export { MobileHeader } from './mobile/components/MobileHeader';
export { MobileTabBar } from './mobile/components/MobileTabBar';
export { MobileSheet } from './mobile/components/MobileSheet';
export { MobileIcon } from './mobile/components/MobileIcon';
export { MobileSwipeRow } from './mobile/components/MobileSwipeRow';
export { TasksSheet } from './mobile/components/TasksSheet';
export { MessageActionSheet } from './mobile/components/MessageActionSheet';
export { ConnectionBanner } from './mobile/components/ConnectionBanner';
export {
  MOBILE_TAB_ITEMS,
  MOBILE_MORE_ITEMS,
  MOBILE_SHEET_ITEMS,
  MOBILE_COMPOSER_PLUS_ITEMS,
  MOBILE_BUILT_IN_TAB_ITEMS,
  MOBILE_TAB_MAX,
  resolveMobileTabs,
  resolveMoreItems,
} from './mobile/registries';
export type {
  MobileTabItem,
  MobileMoreItem,
  MobileSheetItem,
  MobileComposerPlusItem,
  MobileComposerContext,
} from './mobile/registries';
export type { MobileSwipeAction } from './mobile/components/MobileSwipeRow';
export { useMobileUiStore } from './mobile/stores/mobile-ui-store';
export { useKeyboardInset } from './mobile/hooks/useKeyboardInset';
export { useLongPress } from './mobile/hooks/useLongPress';

// === Connection plugin (business-agnostic route + latency self-diagnostics) ===
// A host mounts the desktop badge with `TOPBAR_EXTRA_ITEMS.push(ConnectionPill)`. The mobile
// surfaces already ride inside the exported `MobileChatPage` / `MobileProfilePage`.
export { ConnectionPill } from './plugins/connection/ConnectionPill';
export { ConnectionChip } from './plugins/connection/ConnectionChip';
export { ConnectionSheet } from './plugins/connection/ConnectionSheet';
export { ConnectionReport } from './plugins/connection/ConnectionReport';
export { CONNECTION_CONFIG } from './plugins/connection/config';
export { startConnectionProbe, useConnectionStore, useConnectionStatus } from './plugins/connection/store';
export { classifyAccess, mergeRoute } from './plugins/connection/route';
export { accessText, qualityOf, qualityDot, routeText, msText, sourceText } from './plugins/connection/labels';
export type { ConnectionState } from './plugins/connection/store';
export type {
  AccessKind,
  Confidence,
  ConnectionInfoDto,
  EvidenceSource,
  ProbeSample,
  Quality,
  RelayInfo,
  RouteSnapshot,
  ServerRoute,
  Via,
} from './plugins/connection/types';

// Transcript state hooks, shared by the desktop panel and any host building its own surface.
export { useSessionRecovery } from './hooks/useSessionRecovery';
export { useMessageAutoScroll } from './hooks/useMessageAutoScroll';
export { useDisplaySessions } from './hooks/useDisplaySessions';
export { useAggregatedReferences } from './hooks/useAggregatedReferences';
export { useChatBootstrap } from './hooks/useChatBootstrap';
export { useVisibilityReconnect } from './hooks/useVisibilityReconnect';

// Deploy freshness. `AppLayout` and `MobileAppLayout` mount the prompt themselves, so a host only
// reaches for these when it builds its own root instead of using either layout.
export { useAppUpdate, applyBuildUpdate } from './hooks/useAppUpdate';
export type { AppUpdateState } from './hooks/useAppUpdate';
export { AppUpdateBanner } from './components/layout/AppUpdateBanner';
export { AppUpdateDialog } from './mobile/components/AppUpdateDialog';

// === Full App (for embedding the complete easyai-console experience) ===
export { default as EasyAiApp } from './App';
