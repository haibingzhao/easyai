import { APP_CONFIG } from '@/constants/navigation';

/**
 * Whether the composer may offer the auto-approve (shield) panel.
 *
 * `projectId` must be undefined for temporary workspaces: their rules die with the session, so the
 * panel has nothing durable to edit. A host can further restrict the entry point through
 * [APP_CONFIG.canConfigureAutoApprove] (e.g. only the owner view may change tool permissions).
 */
export function canConfigureAutoApprove(projectId: string | undefined): boolean {
  return !!projectId && (APP_CONFIG.canConfigureAutoApprove?.() ?? true);
}
