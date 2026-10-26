/**
 * Library entry point for @easyai/console.
 *
 * This file exports the public API surface that external consumers
 * (e.g. trading-console) can import when using this package as a library.
 *
 * Usage:
 *   import { ChatPanel, useChatStore, authFetch } from '@easyai/console';
 *
 * Hosts that run Tailwind add this to their CSS entry (after `@import "tailwindcss"`):
 *   @import "@easyai/console/theme.css";
 *   plus an @source glob covering every JS chunk in the package's dist-lib directory.
 * Consumers without Tailwind can still import the pre-built '@easyai/console/style.css'.
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
  readErrorBody,
  readErrorMessage,
  // Group-sharing write scope: hosts fold group ownership into login, then call
  // setDefaultAssetScope('group') once so every console write routes to the shared bucket.
  setDefaultAssetScope,
  resolveWriteScope,
  scopeQuery,
  withScope,
  // Host-scoped headers (e.g. an active-family routing header) for every non-FormData request.
  setDefaultRequestHeaders,
} from './services/api-client';
export type { AssetScope } from './services/api-client';

// Scope-aware asset services: exported so hosts can drive model / MCP / skill / storage / task-model
// writes directly (passing an explicit `scope`) instead of relying on setDefaultAssetScope.
export { modelConfigService } from './services/model-config-service';
export { mcpService, McpService } from './services/mcp-service';
export { SkillService, SHARED_OWNER_ID } from './services/skill-service';
export { auxModelConfigService } from './services/aux-model-config-service';
export { chatPreferenceService } from './services/chat-preference-service';
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

// Account-level Web Push API (`/api/push/*`): config, device subscribe/unsubscribe, test send.
// The browser-side plumbing (service worker, VAPID subscription) lives in `./mobile/push`.
export { pushService } from './services/push-service';
export type {
  PushConfig,
  PushSubscriptionInput,
  PushTestResult,
} from './services/push-service';

// Account-level notification inbox (`/api/notifications/*`). A host publishes through the backend
// `NotificationService`; this is only the read/answer side the console and host pages call.
export { notificationService, EMPTY_SUMMARY } from './services/notification-service';
export type {
  NotificationActionStatus,
  NotificationDecision,
  NotificationDirection,
  NotificationItem,
  NotificationPage as NotificationApiPage,
  NotificationSummary,
} from './services/notification-service';

// Durable async-task plane (global Tasks page). Exported so a host can mount
// its own tasks surface or drive task control directly.
export { taskService } from './services/task-service';

// Watch (关注) plane: definitions created from chat, their findings, and the page surfaces.
// A host that mounts its own router must add `/watches` and `/watches/:id` itself, then call
// `enableWatchesNav()` so the sidebar entry is shown.
export { enableWatchesNav } from './constants/navigation';
export { watchService } from './services/watch-service';
export { useWatchStore } from './services/stores/watch-store';
export { WatchesPage } from './pages/WatchesPage';
export { WatchDetailPage } from './pages/WatchDetailPage';
export {
  isWatchActive,
  isWatchTerminal,
  parseWatchRunResult,
} from './types/watch';
export type {
  Watch,
  WatchCheckResult,
  WatchExpiryDecision,
  WatchFinding,
  WatchFindingTask,
  WatchMode,
  WatchNotifyPolicy,
  WatchPendingAction,
  WatchReviewResult,
  WatchRunResult,
  WatchSchedule,
  WatchScheduleDraft,
  WatchScheduleKind,
  WatchSource,
  WatchStatus,
  WatchTag,
  WatchUpdatePayload,
  WatchVerdict,
} from './types/watch';

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
export {
  useNotificationStore,
  startNotificationPolling,
  stopNotificationPolling,
} from './services/stores/notification-store';
export { convertSnapshot } from './services/stores/chat/message-converter';
export { mergeToolResults } from './services/stores/chat/session-loader';

// === Components ===
export { ChatPanel } from './components/chat/ChatPanel';
export { MessageList } from './components/chat/MessageList';
// Model dropdown reused by host-built composers; `allowEmptySelection` lets a host keep
// "server default" as a real choice instead of pinning the first enabled model.
export { ModelSelector } from './components/chat/ModelSelector';
export type { ModelSelectorProps } from './components/chat/ModelSelector';
export { RenderVisualToolMessage } from './components/chat/tools/RenderVisualToolMessage';
// Tool-renderer extension point: hosts register a component by tool name (overriding built-ins)
// instead of forking the router. Register at startup, before the first transcript render.
export { registerToolRenderers } from './components/chat/tools/ToolMessageRouter';
export type { ToolRenderer } from './components/chat/tools/ToolMessageRouter';
export type { ToolMessageProps } from './components/chat/tools/types';
// Streaming-safe markdown renderer used by chat text segments; hosts reuse it for
// any LLM-authored text so rendering stays identical to the chat bubbles.
export { TextMarkdown } from './components/chat/segments/TextMarkdown';
export { AppLayout, SIDEBAR_COLLAPSED_WIDTH } from './components/layout/AppLayout';
export { Sidebar } from './components/layout/Sidebar';
export { ReadOnlyBanner } from './components/ui/ReadOnlyBanner';
// One inbox row. Exported so a host that embeds the list in its own surface renders the same
// decision buttons and the same server-computed flags.
export { NotificationCard } from './components/notification/NotificationCard';

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
  ModelOverrides,
  ReasoningMode,
  ReasoningSource,
  ReasoningProfile,
  StorageConfig,
  StorageBackendType,
  StorageEffectiveSource,
  StorageTestResult,
  ModelCapabilities,
} from './types/settings';

export type { NavItem } from './types/layout';

export type { ChatPreference, ChatPreferenceSource, SaveChatPreferenceRequest } from './types/agent';

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
  registerTaskTypes,
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
export { TasksPage } from './pages/TasksPage';
export { TaskTranscriptPage } from './pages/TaskTranscriptPage';
export { KnowledgePage } from './pages/KnowledgePage';
export { ArticleEditorPage } from './pages/ArticleEditorPageLazy';
export { CommandsPage } from './pages/CommandsPage';
// The shared inbox. A host mounts it on the route it puts in `APP_CONFIG.notificationsPath`, and
// passes `onActionComplete` when an accepted decision changes its own permissions.
export { NotificationsPage } from './pages/NotificationsPage';
export type { NotificationsPageProps } from './pages/NotificationsPage';
export { SkillsPage } from './pages/SkillsPage';
export { SkillAgentPage } from './pages/SkillAgentPage';
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
// Canonical reader for a contenteditable composer (chip-aware); hosts that mirror dictation slots
// into their own draft state must read the editor through this, not innerText.
export { readMessageEditorText } from './utils/attachment-utils';
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
export { MobileNotificationsPage } from './mobile/pages/MobileNotificationsPage';
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
  MOBILE_TOP_ITEMS,
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
// Browser Web Push client: capability probes, service-worker registration, VAPID subscription
// and the boot-time self-heal (`syncPushOnBoot`) hosts call once identity is ready.
export {
  supportsPush,
  isStandalone,
  urlBase64ToUint8Array,
  registerPushServiceWorker,
  ensureSubscription,
  subscriptionInput,
  syncPushOnBoot,
} from './mobile/push';

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
// Dictation for a contenteditable composer: live draft slots + sentence correction + polish.
export { useVoiceInput } from './hooks/useVoiceInput';
export type { VoicePhase, UseVoiceInputOptions } from './hooks/useVoiceInput';

// Deploy freshness. `AppLayout` and `MobileAppLayout` mount the prompt themselves, so a host only
// reaches for these when it builds its own root instead of using either layout.
export { useAppUpdate, applyBuildUpdate } from './hooks/useAppUpdate';
export type { AppUpdateState } from './hooks/useAppUpdate';
export { AppUpdateBanner } from './components/layout/AppUpdateBanner';
export { AppUpdateDialog } from './mobile/components/AppUpdateDialog';

// === Full App (for embedding the complete easyai-console experience) ===
export { default as EasyAiApp } from './App';
