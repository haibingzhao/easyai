import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertCircle,
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  FileText,
  Github,
  Loader2,
  Package,
  Plus,
  Send,
  ShieldAlert,
  Sparkles,
  Square,
  Wand2,
} from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { ModelSelector } from '@/components/chat/ModelSelector';
import { ThinkingBlock } from '@/components/chat/ThinkingBlock';
import { TextMarkdown } from '@/components/chat/segments/TextMarkdown';
import {
  archiveEntryInstallable,
  archiveEntryNeedsAcknowledgement,
  useSkillAgentStore,
} from '@/services/stores/skill-agent-store';
import type { SkillAgentMessage } from '@/services/stores/skill-agent-store';
import { i18n } from '@/utils/i18n';
import type {
  SkillArchiveEntry,
  SkillProposal,
  SkillProposalFile,
  SkillScanCategory,
  SkillScanFinding,
  SkillScanReport,
  SkillScanSeverity,
} from '@/types/skill-agent';

const EXAMPLES: Array<{ icon: React.ReactNode; label: string; prompt: string }> = [
  {
    icon: <Github className="w-4 h-4" />,
    label: 'Import a GitHub skill',
    prompt: 'Import this skill: https://github.com/owner/repo/blob/main/skills/demo/SKILL.md',
  },
  {
    icon: <Sparkles className="w-4 h-4" />,
    label: 'Create a code review skill',
    prompt: 'Create a skill that reviews a pull request for correctness, security and tests.',
  },
  {
    icon: <FileText className="w-4 h-4" />,
    label: 'Turn a docs page into a skill',
    prompt: 'Turn https://example.com/docs/getting-started into a practical skill.',
  },
];

const TOOL_LABELS: Record<string, string> = {
  webfetch: 'Fetch URL',
  list_installed_skills: 'Check installed skills',
  preview_skill_archive: 'Download & scan archive',
  submit_skill: 'Validate & scan package',
};

const SEVERITY_CLASS: Record<SkillScanSeverity, string> = {
  critical: 'bg-red-500/15 text-red-600 border-red-500/30',
  high: 'bg-orange-500/15 text-orange-600 border-orange-500/30',
  medium: 'bg-amber-500/15 text-amber-600 border-amber-500/30',
  low: 'bg-muted text-muted-foreground border-border',
  info: 'bg-muted text-muted-foreground border-border',
};

const SEVERITY_ORDER: SkillScanSeverity[] = ['critical', 'high', 'medium', 'low', 'info'];

function formatBytes(size: number): string {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

/** The file body is only mounted once expanded: a 128 KiB package would otherwise sit in the DOM. */
const ProposalFile: React.FC<{ file: SkillProposalFile }> = ({ file }) => {
  const [open, setOpen] = useState(false);
  return (
    <div className="rounded-md border border-border p-2">
      <button
        type="button"
        className="w-full text-xs select-none flex items-center justify-between gap-2"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
      >
        <span className="font-mono truncate">{file.path}</span>
        <span className="text-muted-foreground shrink-0">{formatBytes(file.size)}</span>
      </button>
      {open && (
        <pre className="mt-2 max-h-72 overflow-auto rounded bg-muted p-2 text-[11px] whitespace-pre-wrap break-all">
          {file.content}
        </pre>
      )}
    </div>
  );
};

function targetLabels(target: SkillProposal['target']): { badge: string; detail: string } {
  switch (target) {
    case 'shared':
      return { badge: i18n('Shared'), detail: i18n('Everyone can see it.') };
    case 'group':
      return { badge: i18n('Group'), detail: i18n('All group members can see it.') };
    default:
      return { badge: i18n('Personal'), detail: i18n('Only you can see and manage it.') };
  }
}

const FindingRow: React.FC<{ finding: SkillScanFinding }> = ({ finding }) => (
  <div className="text-xs space-y-0.5">
    <div className="flex items-center gap-1.5 flex-wrap">
      <span className={`text-[10px] uppercase px-1.5 py-0.5 rounded border ${SEVERITY_CLASS[finding.severity]}`}>
        {finding.severity}
      </span>
      <span className="font-mono text-muted-foreground">
        {finding.file ?? '-'}
        {finding.line != null ? `:${finding.line}` : ''}
      </span>
      <span className="font-mono text-muted-foreground/70">{finding.id}</span>
    </div>
    <p className="text-foreground">{finding.message}</p>
    <p className="text-muted-foreground">{finding.suggestion}</p>
  </div>
);

const ScanGroup: React.FC<{ title: string; findings: SkillScanFinding[] }> = ({ title, findings }) => {
  if (findings.length === 0) return null;
  return (
    <div className="space-y-2">
      <div className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        {title} ({findings.length})
      </div>
      {findings.map((finding) => {
        const expanded = finding.severity === 'critical' || finding.severity === 'high';
        if (expanded) {
          return (
            <div key={`${finding.id}-${finding.file ?? ''}-${finding.message}`} className="rounded-md border border-border p-2">
              <FindingRow finding={finding} />
            </div>
          );
        }
        return (
          <details key={`${finding.id}-${finding.file ?? ''}-${finding.message}`} className="rounded-md border border-border p-2">
            <summary className="text-xs cursor-pointer select-none">
              <span className="text-muted-foreground mr-1">[{finding.severity}]</span>
              {finding.message}
            </summary>
            <div className="mt-2">
              <FindingRow finding={finding} />
            </div>
          </details>
        );
      })}
    </div>
  );
};

const ScanReport: React.FC<{ scan: SkillScanReport }> = ({ scan }) => {
  // Category totals come from the full scan; `findings` is display-capped.
  const spec = scan.categoryCounts.spec;
  const specWarnings = spec.critical + spec.high + spec.medium + spec.low;
  const security = scan.categoryCounts.security;
  const securityCritical = security.critical;
  const securityHigh = security.high;
  const securityOther = security.medium + security.low + security.info;
  const groups: Array<{ category: SkillScanCategory; title: string }> = [
    { category: 'security', title: i18n('Security findings') },
    { category: 'spec', title: i18n('EasyAI spec findings') },
    { category: 'quality', title: i18n('Quality hints') },
  ];

  return (
    <div className="rounded-md border border-border bg-muted/20 p-3 space-y-3">
      <div className="flex items-center gap-2 flex-wrap text-xs">
        <span className={`px-1.5 py-0.5 rounded border ${specWarnings > 0 ? SEVERITY_CLASS.medium : 'bg-green-500/10 text-green-600 border-green-500/30'}`}>
          {i18n('EasyAI spec')}: {specWarnings > 0 ? `${specWarnings} ${i18n('warnings')}` : i18n('pass')}
        </span>
        <span className={`px-1.5 py-0.5 rounded border ${securityCritical > 0 ? SEVERITY_CLASS.critical : securityHigh > 0 ? SEVERITY_CLASS.high : securityOther > 0 ? SEVERITY_CLASS.medium : 'bg-green-500/10 text-green-600 border-green-500/30'}`}>
          {i18n('Security')}: {securityCritical > 0
            ? i18n('blocked')
            : securityHigh > 0
              ? `${securityHigh} ${i18n('high-risk')}`
              : securityOther > 0
                ? `${securityOther} ${i18n('findings')}`
                : i18n('pass')}
        </span>
        {SEVERITY_ORDER.filter((severity) => (scan.counts[severity] ?? 0) > 0).map((severity) => (
          <span key={severity} className={`text-[10px] uppercase px-1.5 py-0.5 rounded border ${SEVERITY_CLASS[severity]}`}>
            {severity} {scan.counts[severity]}
          </span>
        ))}
      </div>
      {groups.map(({ category, title }) => (
        <ScanGroup
          key={category}
          title={title}
          findings={scan.findings.filter((finding) => finding.category === category)}
        />
      ))}
      {scan.findings.length === 0 && (
        <p className="text-xs text-muted-foreground">{i18n('No findings.')}</p>
      )}
    </div>
  );
};

const ProposalCard: React.FC<{
  message: SkillAgentMessage;
  onInstall: () => void;
  onAcknowledge: (acknowledged: boolean) => void;
  onAskFix: (error: string) => void;
}> = ({ message, onInstall, onAcknowledge, onAskFix }) => {
  const proposal = message.proposal;
  if (!proposal) return null;
  const target = targetLabels(proposal.target);
  const installed = message.proposalState === 'installed';
  const superseded = message.proposalState === 'superseded';
  const needsAcknowledgement = proposal.scan.requiresAcknowledgement;
  const cannotInstall = superseded || proposal.scan.blocking;
  const disabled = cannotInstall || message.installing ||
    (needsAcknowledgement && !message.acknowledged);

  return (
    <div className={`rounded-lg border ${superseded ? 'border-border opacity-60' : 'border-primary/40'} bg-card p-4 space-y-3`}>
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <span className="font-mono text-sm font-semibold">{proposal.name}</span>
            <span className="text-xs text-muted-foreground">v{proposal.version}</span>
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">{target.badge}</span>
            {superseded && (
              <span className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">
                {i18n('Replaced by a newer preview')}
              </span>
            )}
            {installed && (
              <span className="text-[10px] px-1.5 py-0.5 rounded bg-green-500/10 text-green-600 flex items-center gap-1">
                <CheckCircle2 className="w-3 h-3" /> {i18n('Installed')}
              </span>
            )}
          </div>
          <p className="text-xs text-muted-foreground mt-1">{target.detail}</p>
        </div>
      </div>

      <p className="text-sm whitespace-pre-wrap break-words">{proposal.summary}</p>
      {proposal.description && <p className="text-xs text-muted-foreground">{proposal.description}</p>}
      {proposal.tags.length > 0 && (
        <div className="flex gap-1 flex-wrap">
          {proposal.tags.map((tag) => (
            <span key={tag} className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">{tag}</span>
          ))}
        </div>
      )}

      {proposal.warnings.length > 0 && (
        <div className="rounded-md border border-amber-500/30 bg-amber-500/10 p-2 space-y-1">
          {proposal.warnings.map((warning) => (
            <p key={warning} className="text-xs text-amber-700 dark:text-amber-400 flex items-start gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5 mt-0.5 shrink-0" /> {warning}
            </p>
          ))}
        </div>
      )}

      <ScanReport scan={proposal.scan} />

      <div className="space-y-1">
        <div className="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
          {i18n('Files')} ({proposal.files.length})
        </div>
        {proposal.files.map((file) => (
          <ProposalFile key={file.path} file={file} />
        ))}
      </div>

      {proposal.scan.blocking && (
        <div className="rounded-md border border-red-500/40 bg-red-500/10 p-2 text-xs text-red-600 flex items-start gap-1.5">
          <ShieldAlert className="w-4 h-4 shrink-0" />
          {i18n('This package is blocked by the server scan and cannot be installed.')}
        </div>
      )}

      {needsAcknowledgement && !installed && !superseded && (
        <label className="rounded-md border border-orange-500/40 bg-orange-500/10 p-2 flex items-start gap-2 text-xs cursor-pointer">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={!!message.acknowledged}
            onChange={(event) => onAcknowledge(event.target.checked)}
          />
          <span>{i18n('I have read and understand the high-risk findings above.')}</span>
        </label>
      )}

      {message.installError && (
        <div className="rounded-md border border-red-500/40 bg-red-500/10 p-2 space-y-2">
          <p className="text-xs text-red-600 whitespace-pre-wrap break-words">{message.installError}</p>
          <Button size="sm" variant="outline" onClick={() => onAskFix(message.installError ?? '')}>
            {i18n('Ask Agent to fix')}
          </Button>
        </div>
      )}

      {installed ? (
        <div className="rounded-md border border-green-500/30 bg-green-500/10 p-3">
          {message.installWarning && (
            <p className="text-xs text-amber-700 dark:text-amber-400 mb-2">{message.installWarning}</p>
          )}
          <p className="text-xs font-medium text-green-700 dark:text-green-400 mb-1">{i18n('Next steps')}</p>
          <ul className="text-xs text-muted-foreground space-y-1 list-disc pl-4">
            <li>{i18n('Not bound to any agent yet: enable it in the skill settings of an agent, or call it in chat as /<name>.')}</li>
            <li>{i18n('Indexing is asynchronous: the Skills page shows Indexing until the retrieval index catches up, and skill_search may miss it temporarily.')}</li>
            <li>{i18n('Installation does not run scripts or install dependencies. Run them yourself if needed.')}</li>
            <li>{i18n('The scan is rule-based, not a security guarantee. Review the listed files yourself.')}</li>
          </ul>
        </div>
      ) : (
        !superseded && !proposal.scan.blocking && (
          <div className="flex justify-end">
            <Button
              size="sm"
              onClick={onInstall}
              disabled={disabled}
              title={needsAcknowledgement && !message.acknowledged ? i18n('Acknowledge the high-risk findings before installing.') : undefined}
            >
              {message.installing ? (
                <><Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> {i18n('Installing...')}</>
              ) : (
                i18n('Install')
              )}
            </Button>
          </div>
        )
      )}
    </div>
  );
};

const ArchiveEntryCard: React.FC<{
  entry: SkillArchiveEntry;
  /** The whole preview was superseded: show the result but offer no actions. */
  locked: boolean;
  onInstall: () => void;
  onAcknowledge: (acknowledged: boolean) => void;
  onAskFix: (reason: string) => void;
}> = ({ entry, locked, onInstall, onAcknowledge, onAskFix }) => {
  const installed = entry.state === 'installed';
  const installing = entry.state === 'installing';
  const blocked = !archiveEntryInstallable(entry);
  const needsAcknowledgement = archiveEntryNeedsAcknowledgement(entry);

  return (
    <div className={`rounded-md border p-3 space-y-2 ${installed ? 'border-green-500/40 bg-green-500/5' : 'border-border'}`}>
      <div className="flex items-center gap-2 flex-wrap">
        <span className="font-mono text-sm font-medium">{entry.name}</span>
        {entry.version && <span className="text-xs text-muted-foreground">v{entry.version}</span>}
        <span className="text-[10px] text-muted-foreground/70 font-mono truncate" title={entry.entryPath || undefined}>
          {entry.entryPath || i18n('(archive root)')}
        </span>
        {installed && (
          <span className="text-[10px] px-1.5 py-0.5 rounded bg-green-500/10 text-green-600 flex items-center gap-1">
            <CheckCircle2 className="w-3 h-3" /> {i18n('Installed')}
          </span>
        )}
        {entry.conflict && (
          <span className="text-[10px] px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600">{i18n('Name conflict')}</span>
        )}
        {entry.invalidReason && (
          <span className="text-[10px] px-1.5 py-0.5 rounded bg-red-500/15 text-red-600">{i18n('Not installable')}</span>
        )}
      </div>

      {entry.description && <p className="text-xs text-muted-foreground">{entry.description}</p>}
      {entry.tags.length > 0 && (
        <div className="flex gap-1 flex-wrap">
          {entry.tags.map((tag) => (
            <span key={tag} className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">{tag}</span>
          ))}
        </div>
      )}

      <details>
        <summary className="text-xs cursor-pointer select-none text-muted-foreground">
          {i18n('Files')} ({entry.files.length})
        </summary>
        <ul className="mt-1 max-h-40 overflow-auto space-y-0.5">
          {entry.files.map((file) => (
            <li key={file.path} className="flex items-center justify-between gap-2 text-[11px] font-mono">
              <span className="truncate" title={file.path}>{file.path}</span>
              <span className="text-muted-foreground shrink-0">{formatBytes(file.size)}</span>
            </li>
          ))}
        </ul>
      </details>

      {entry.warnings.length > 0 && (
        <div className="rounded-md border border-amber-500/30 bg-amber-500/10 p-2 space-y-1">
          {entry.warnings.map((warning) => (
            <p key={warning} className="text-xs text-amber-700 dark:text-amber-400 flex items-start gap-1.5">
              <AlertTriangle className="w-3.5 h-3.5 mt-0.5 shrink-0" /> {warning}
            </p>
          ))}
        </div>
      )}

      <details className="rounded-md border border-border">
        <summary className="px-2 py-1.5 text-xs cursor-pointer select-none flex items-center gap-2 flex-wrap">
          <span className="font-medium">{i18n('Scan')}</span>
          {entry.scan.blocking ? (
            <span className="text-red-600">{i18n('blocked')}</span>
          ) : entry.scan.requiresAcknowledgement ? (
            <span className="text-amber-600">{i18n('high-risk')}</span>
          ) : (
            <span className="text-muted-foreground">{i18n('no blockers')}</span>
          )}
        </summary>
        <div className="p-2 pt-0">
          <ScanReport scan={entry.scan} />
        </div>
      </details>

      {entry.conflict && (
        <div className="rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-700 dark:text-amber-400 flex items-start gap-1.5">
          <AlertTriangle className="w-4 h-4 shrink-0" />
          <span>
            {i18n('A skill with this name is already installed, so this entry is skipped. Ask the agent to preview again with a new name.')}
            {entry.conflict && <span className="block text-muted-foreground">{entry.conflict}</span>}
          </span>
        </div>
      )}
      {entry.invalidReason && (
        <div className="rounded-md border border-red-500/40 bg-red-500/10 p-2 text-xs text-red-600 flex items-start gap-1.5">
          <ShieldAlert className="w-4 h-4 shrink-0" />
          <span>{entry.invalidReason}</span>
        </div>
      )}

      {needsAcknowledgement && !installed && !locked && (
        <label className="rounded-md border border-orange-500/40 bg-orange-500/10 p-2 flex items-start gap-2 text-xs cursor-pointer">
          <input
            type="checkbox"
            className="mt-0.5"
            checked={!!entry.acknowledged}
            onChange={(event) => onAcknowledge(event.target.checked)}
          />
          <span>{i18n('I have read and understand the high-risk findings for this skill.')}</span>
        </label>
      )}

      {entry.installWarning && (
        <p className="text-xs text-amber-700 dark:text-amber-400">{entry.installWarning}</p>
      )}

      {entry.error && (
        <div className="rounded-md border border-red-500/40 bg-red-500/10 p-2 space-y-2">
          <p className="text-xs text-red-600 whitespace-pre-wrap break-words">{entry.error}</p>
          <Button size="sm" variant="outline" onClick={() => onAskFix(`${entry.name}: ${entry.error}`)}>
            {i18n('Ask Agent to fix')}
          </Button>
        </div>
      )}

      {!installed && !blocked && !locked && (
        <div className="flex justify-end">
          <Button
            size="sm"
            onClick={onInstall}
            disabled={installing || needsAcknowledgement}
            title={needsAcknowledgement ? i18n('Acknowledge the high-risk findings before installing.') : undefined}
          >
            {installing ? (
              <><Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> {i18n('Installing...')}</>
            ) : (
              i18n('Install')
            )}
          </Button>
        </div>
      )}
    </div>
  );
};

const ArchiveProposalCard: React.FC<{
  message: SkillAgentMessage;
  onInstall: (entryPaths: string[]) => void;
  onInstallAll: () => void;
  onAcknowledge: (entryPath: string, acknowledged: boolean) => void;
  onAskFix: (reason: string) => void;
}> = ({ message, onInstall, onInstallAll, onAcknowledge, onAskFix }) => {
  const proposal = message.archiveProposal;
  if (!proposal) return null;
  const target = targetLabels(proposal.target);
  const superseded = message.archiveState === 'superseded';
  const installing = proposal.skills.some((entry) => entry.state === 'installing');
  const installed = proposal.skills.filter((entry) => entry.state === 'installed').length;
  const installable = proposal.skills.filter(
    (entry) => entry.state !== 'installed' && archiveEntryInstallable(entry) && !archiveEntryNeedsAcknowledgement(entry),
  ).length;
  const awaitingAcknowledgement = proposal.skills.filter(archiveEntryNeedsAcknowledgement).length;

  return (
    <div className={`rounded-lg border ${superseded ? 'border-border opacity-60' : 'border-primary/40'} bg-card p-4 space-y-3`}>
      <div className="flex items-start justify-between gap-3 flex-wrap">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            <Package className="w-4 h-4 text-primary shrink-0" />
            <span className="text-sm font-semibold">{i18n('Skill archive')}</span>
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">{target.badge}</span>
            <span className="text-xs text-muted-foreground">
              {i18n('{count} skills').replace('{count}', String(proposal.skills.length))}
            </span>
            {installed > 0 && (
              <span className="text-[10px] px-1.5 py-0.5 rounded bg-green-500/10 text-green-600">
                {i18n('{count} installed').replace('{count}', String(installed))}
              </span>
            )}
            {superseded && (
              <span className="text-[10px] px-1.5 py-0.5 rounded bg-muted text-muted-foreground">
                {i18n('Replaced by a newer preview')}
              </span>
            )}
          </div>
          <p className="text-xs text-muted-foreground mt-1 font-mono truncate" title={proposal.finalUrl}>
            {proposal.finalUrl}
          </p>
          <p className="text-[11px] text-muted-foreground/70 mt-0.5">
            {formatBytes(proposal.archiveBytes)} · sha256 {proposal.sha256.slice(0, 12)}…
          </p>
          <p className="text-xs text-muted-foreground mt-1">{target.detail}</p>
        </div>
      </div>

      <div className="space-y-2">
        {proposal.skills.map((entry) => (
          <ArchiveEntryCard
            key={entry.entryPath || entry.name}
            entry={entry}
            locked={superseded}
            onInstall={() => onInstall([entry.entryPath])}
            onAcknowledge={(acknowledged) => onAcknowledge(entry.entryPath, acknowledged)}
            onAskFix={onAskFix}
          />
        ))}
      </div>

      {message.installError && (
        <div className="rounded-md border border-red-500/40 bg-red-500/10 p-2 space-y-2">
          <p className="text-xs text-red-600 whitespace-pre-wrap break-words">{message.installError}</p>
          <Button size="sm" variant="outline" onClick={() => onAskFix(message.installError ?? '')}>
            {i18n('Ask Agent to fix')}
          </Button>
        </div>
      )}

      {!superseded && (
        <div className="flex items-center justify-between gap-2 flex-wrap">
          {awaitingAcknowledgement > 0 ? (
            <p className="text-[11px] text-muted-foreground">
              {i18n('{count} high-risk skills need confirmation before they can be installed.')
                .replace('{count}', String(awaitingAcknowledgement))}
            </p>
          ) : (
            <span />
          )}
          <Button size="sm" onClick={onInstallAll} disabled={installing || installable === 0}>
            {installing ? (
              <><Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> {i18n('Installing...')}</>
            ) : (
              <>{i18n('Install all')} ({installable})</>
            )}
          </Button>
        </div>
      )}
    </div>
  );
};

const ToolRow: React.FC<{ toolName: string; status: 'running' | 'success' | 'error'; output?: string }> = ({
  toolName,
  status,
  output,
}) => (
  <div className="rounded-md border border-border px-2 py-1.5">
    <div className="flex items-center gap-2 text-xs">
      {status === 'running' && <Loader2 className="w-3.5 h-3.5 animate-spin text-muted-foreground" />}
      {status === 'success' && <CheckCircle2 className="w-3.5 h-3.5 text-green-600" />}
      {status === 'error' && <AlertCircle className="w-3.5 h-3.5 text-red-600" />}
      <span className="font-mono text-muted-foreground">{toolName}</span>
      <span className="text-muted-foreground/70">{TOOL_LABELS[toolName] ? i18n(TOOL_LABELS[toolName]) : ''}</span>
    </div>
    {status === 'error' && output && (
      <pre className="mt-1.5 max-h-40 overflow-auto text-[11px] text-red-600 whitespace-pre-wrap break-words">{output}</pre>
    )}
  </div>
);

export const SkillAgentPage: React.FC = () => {
  const navigate = useNavigate();
  const messages = useSkillAgentStore((state) => state.messages);
  const isStreaming = useSkillAgentStore((state) => state.isStreaming);
  const modelConfigId = useSkillAgentStore((state) => state.modelConfigId);
  const setModelConfigId = useSkillAgentStore((state) => state.setModelConfigId);
  const error = useSkillAgentStore((state) => state.error);
  const send = useSkillAgentStore((state) => state.send);
  const stop = useSkillAgentStore((state) => state.stop);
  const reset = useSkillAgentStore((state) => state.reset);
  const install = useSkillAgentStore((state) => state.install);
  const setAcknowledged = useSkillAgentStore((state) => state.setAcknowledged);
  const installArchive = useSkillAgentStore((state) => state.installArchive);
  const setArchiveAcknowledged = useSkillAgentStore((state) => state.setArchiveAcknowledged);

  const [input, setInput] = useState('');
  const listRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  /** Follow the newest content only while the reader is parked at the bottom. */
  const followRef = useRef(true);

  useEffect(() => {
    const el = listRef.current;
    if (el && followRef.current) el.scrollTop = el.scrollHeight;
  }, [messages]);

  // Leaving the route must not leave an agent run streaming to nobody. The transcript itself is
  // in-memory only and stays readable on return — "New conversation" is what clears it.
  useEffect(() => () => stop(), [stop]);

  const submit = () => {
    const text = input.trim();
    if (!text || isStreaming || !modelConfigId) return;
    followRef.current = true;
    // The store rejects oversized turns before adding them; keep the draft in that case.
    if (send(text)) setInput('');
  };

  const startNewConversation = () => {
    reset();
    setInput('');
    followRef.current = true;
    inputRef.current?.focus();
  };

  const askFix = (error: string) => {
    setInput(`The install failed: ${error}\nPlease fix the package and submit a new preview.`);
    inputRef.current?.focus();
  };

  const askArchiveFix = (reason: string) => {
    setInput(`The archive install failed: ${reason}\nPlease fix the problem and preview the archive again.`);
    inputRef.current?.focus();
  };

  return (
    <div className="h-full flex flex-col bg-background">
      <header className="px-6 py-4 border-b border-border flex items-start justify-between gap-4 shrink-0">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Wand2 className="w-5 h-5 text-primary" />
            <h1 className="text-lg font-semibold">{i18n('Install with Agent')}</h1>
          </div>
          <p className="text-xs text-muted-foreground mt-1">
            {i18n('Describe a skill or paste a GitHub, raw URL or skill .zip link. The conversation is not saved; nothing is installed until you confirm.')}
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {messages.length > 0 && (
            <Button variant="outline" size="sm" onClick={startNewConversation}>
              <Plus className="w-3.5 h-3.5 mr-1" /> {i18n('New conversation')}
            </Button>
          )}
          <Button variant="outline" size="sm" onClick={() => navigate('/skills')}>
            {i18n('Skills')} <ArrowRight className="w-3.5 h-3.5 ml-1" />
          </Button>
        </div>
      </header>

      <div
        ref={listRef}
        className="flex-1 overflow-y-auto"
        onScroll={(event) => {
          const el = event.currentTarget;
          followRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 50;
        }}
      >
        <div className="max-w-3xl mx-auto px-4 py-6 flex flex-col gap-5">
          {messages.length === 0 && (
            <div className="py-10 text-center space-y-4">
              <div className="flex justify-center">
                <div className="w-12 h-12 rounded-full bg-primary/10 flex items-center justify-center">
                  <Wand2 className="w-6 h-6 text-primary" />
                </div>
              </div>
              <div>
                <p className="text-sm font-medium">{i18n('What should the skill do?')}</p>
                <p className="text-xs text-muted-foreground mt-1">
                  {i18n('Paste a GitHub, raw URL or skill .zip link, or describe the skill you want.')}
                </p>
                <p className="text-[11px] text-muted-foreground/70 mt-1">{i18n('Conversations are not saved.')}</p>
              </div>
              <div className="flex flex-wrap justify-center gap-2">
                {EXAMPLES.map((example) => (
                  <button
                    key={example.label}
                    type="button"
                    onClick={() => {
                      setInput(example.prompt);
                      inputRef.current?.focus();
                    }}
                    className="flex items-center gap-1.5 px-3 py-1.5 rounded-full border border-border text-xs hover:bg-muted transition-colors"
                  >
                    {example.icon}
                    {i18n(example.label)}
                  </button>
                ))}
              </div>
            </div>
          )}

          {messages.map((message) => {
            if (message.role === 'user') {
              return (
                <div key={message.id} className="self-end max-w-[85%] px-3 py-2 rounded-lg bg-primary/10 text-sm whitespace-pre-wrap break-words">
                  {message.content}
                </div>
              );
            }
            return (
              <div key={message.id} className="flex flex-col gap-2 min-w-0">
                {message.parts.map((part, index) => {
                  const key = `${message.id}-part-${index}`;
                  if (part.kind === 'thinking') {
                    const isLastPart = index === message.parts.length - 1;
                    return (
                      <ThinkingBlock
                        key={key}
                        content={part.content}
                        isStreaming={isStreaming && isLastPart && !message.thinkingDone}
                        isFinished={!isLastPart || !!message.thinkingDone}
                      />
                    );
                  }
                  if (part.kind === 'text') {
                    return part.content ? <TextMarkdown key={key} content={part.content} /> : null;
                  }
                  const tool = message.tools.find((item) => item.toolCallId === part.toolCallId);
                  return tool ? (
                    <ToolRow key={key} toolName={tool.toolName} status={tool.status} output={tool.output} />
                  ) : null;
                })}
                {message.proposal && (
                  <ProposalCard
                    message={message}
                    onInstall={() => void install(message.id)}
                    onAcknowledge={(acknowledged) => setAcknowledged(message.id, acknowledged)}
                    onAskFix={askFix}
                  />
                )}
                {message.archiveProposal && (
                  <ArchiveProposalCard
                    message={message}
                    onInstall={(entryPaths) => void installArchive(message.id, entryPaths)}
                    onInstallAll={() => void installArchive(message.id, null)}
                    onAcknowledge={(entryPath, acknowledged) =>
                      setArchiveAcknowledged(message.id, entryPath, acknowledged)}
                    onAskFix={askArchiveFix}
                  />
                )}
                {message.error && (
                  <div className="rounded-md border border-red-500/40 bg-red-500/10 p-2 text-xs text-red-600 flex items-start gap-1.5">
                    <AlertCircle className="w-4 h-4 shrink-0" />
                    <span className="whitespace-pre-wrap break-words">{message.error}</span>
                  </div>
                )}
                {!isStreaming && !message.content && !message.thinking && message.tools.length === 0 &&
                  !message.proposal && !message.archiveProposal && !message.error && (
                    <p className="text-xs text-muted-foreground">
                      {i18n('The agent did not produce a package. Ask it to submit a preview.')}
                    </p>
                  )}
              </div>
            );
          })}
        </div>
      </div>

      <div className="border-t border-border p-4 shrink-0">
        <div className="max-w-3xl mx-auto">
          <div className="rounded-lg border border-border bg-card focus-within:ring-1 focus-within:ring-ring">
            <textarea
              ref={inputRef}
              value={input}
              onChange={(event) => setInput(event.target.value)}
              onKeyDown={(event) => {
                // Enter confirms an IME candidate while composing; sending then would submit a
                // half-typed message (same guard as the main chat composer).
                if (event.nativeEvent.isComposing) return;
                if (event.key === 'Enter' && !event.shiftKey) {
                  event.preventDefault();
                  submit();
                }
              }}
              rows={3}
              placeholder={i18n('Paste a GitHub, raw URL or skill .zip link, or describe the skill you want.')}
              className="w-full resize-none bg-transparent px-3 py-2 text-sm outline-none"
            />
            <div className="flex items-center justify-between gap-2 px-2 pb-2">
              <ModelSelector
                selectedId={modelConfigId}
                persistSelection={false}
                onModelChange={(configId) => setModelConfigId(configId)}
              />
              <button
                type="button"
                onClick={isStreaming ? stop : submit}
                disabled={!isStreaming && (!input.trim() || !modelConfigId)}
                className={`p-1.5 rounded-md transition-colors disabled:opacity-50 disabled:cursor-not-allowed ${
                  isStreaming ? 'bg-destructive/10 text-destructive hover:bg-destructive/20' : 'bg-primary/10 text-primary hover:bg-primary/20'
                }`}
                title={isStreaming ? i18n('Stop') : i18n('Send')}
              >
                {isStreaming ? <Square className="w-4 h-4" /> : <Send className="w-4 h-4" />}
              </button>
            </div>
          </div>
          {error && <p className="text-[11px] text-red-600 mt-1.5">{error}</p>}
          {!modelConfigId && (
            <p className="text-[11px] text-muted-foreground mt-1.5">{i18n('Select a model to start.')}</p>
          )}
        </div>
      </div>
    </div>
  );
};
