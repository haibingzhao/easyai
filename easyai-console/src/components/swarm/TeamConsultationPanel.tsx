import React, { useState } from 'react';
import { MessageSquare, Send, X, CheckCircle2 } from 'lucide-react';
import { swarmService } from '@/services/swarm-service';
import { i18n } from '@/utils/i18n';

export interface ConsultationData {
  memberId: string;
  question: string;
  options?: string[];
  allowOther?: boolean;
}

interface TeamConsultationPanelProps {
  runId: string;
  taskId: string;
  consultation: ConsultationData;
  onResolved: () => void;
  onClose: () => void;
}

/**
 * Panel for answering a team consultation question.
 * Rendered when user clicks a TEAM node in "waiting for user answer" state.
 * Follows the ask_question interaction pattern: options + free text input.
 */
export const TeamConsultationPanel: React.FC<TeamConsultationPanelProps> = ({
  runId,
  taskId,
  consultation,
  onResolved,
  onClose,
}) => {
  const [selectedOption, setSelectedOption] = useState<string | null>(null);
  const [freeText, setFreeText] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [answered, setAnswered] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const effectiveAnswer = selectedOption || freeText.trim();
  const canSubmit = effectiveAnswer.length > 0 && !submitting && !answered;

  const handleSubmit = async () => {
    if (!canSubmit) return;
    setSubmitting(true);
    setError(null);
    try {
      await swarmService.answerTeamConsultation(runId, taskId, consultation.memberId, effectiveAnswer);
      setAnswered(true);
      onResolved();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to submit answer');
    } finally {
      setSubmitting(false);
    }
  };

  const handleReject = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await swarmService.rejectTeamConsultation(runId, taskId, consultation.memberId);
      setAnswered(true);
      onResolved();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to reject');
    } finally {
      setSubmitting(false);
    }
  };

  if (answered) {
    return (
      <div className="p-4 border border-border rounded-lg bg-card">
        <div className="flex items-center gap-2 text-green-600">
          <CheckCircle2 className="w-4 h-4" />
          <span className="text-sm">{i18n('Answer submitted')}</span>
        </div>
      </div>
    );
  }

  return (
    <div className="p-4 border border-amber-300 rounded-lg bg-card shadow-sm">
      {/* Header */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <MessageSquare className="w-4 h-4 text-amber-500" />
          <span className="text-sm font-medium">{i18n('Team needs your input')}</span>
        </div>
        <button type="button" onClick={onClose} className="text-muted-foreground hover:text-foreground">
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* Question */}
      <p className="text-sm text-foreground mb-3">{consultation.question}</p>

      {/* Options */}
      {consultation.options && consultation.options.length > 0 && (
        <div className="space-y-2 mb-3">
          {consultation.options.map((opt) => (
            <button
              key={opt}
              type="button"
              onClick={() => { setSelectedOption(opt); setFreeText(''); }}
              className={[
                'w-full text-left px-3 py-2 rounded-md border text-sm transition-colors',
                selectedOption === opt
                  ? 'border-primary bg-primary/10 text-primary'
                  : 'border-border hover:border-primary/40',
              ].join(' ')}
            >
              {opt}
            </button>
          ))}
        </div>
      )}

      {/* Free text input */}
      {(consultation.allowOther !== false) && (
        <textarea
          value={freeText}
          onChange={(e) => { setFreeText(e.target.value); setSelectedOption(null); }}
          placeholder={i18n('Type your answer...')}
          className="w-full text-sm border border-border rounded-md px-3 py-2 mb-3 resize-none h-20 focus:outline-none focus:ring-1 focus:ring-primary"
        />
      )}

      {/* Error */}
      {error && <p className="text-xs text-red-500 mb-2">{error}</p>}

      {/* Actions */}
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSubmit}
          className="flex items-center gap-1 px-3 py-1.5 text-xs rounded-md bg-primary text-primary-foreground disabled:opacity-50 hover:bg-primary/90 transition-colors"
        >
          <Send className="w-3 h-3" />
          {submitting ? i18n('Submitting...') : i18n('Submit Answer')}
        </button>
        <button
          type="button"
          onClick={handleReject}
          disabled={submitting}
          className="px-3 py-1.5 text-xs rounded-md border border-border text-muted-foreground hover:text-foreground disabled:opacity-50 transition-colors"
        >
          {i18n('Skip')}
        </button>
      </div>
    </div>
  );
};
