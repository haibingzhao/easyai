/**
 * AskQuestion tool message rendering component.
 * Displays a multi-step Q&A form inline within the tool card.
 * Aligned with backend AskQuestionParameter class format.
 */

import { useState, useCallback, Fragment } from 'react';
import { MessageSquare, ChevronRight, ChevronLeft, AlertCircle, Check, X } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { answerQuestion } from '@/services/chat-service';
import { authFetch } from '@/services/api-client';
import type { ToolMessageProps } from './types';

/** Question option (aligned with backend QuestionOption) */
interface QuestionOption {
  label: string;
  description?: string;
  isOther?: boolean;
}

/** Single question (aligned with backend QuestionParameter) */
interface Question {
  question: string;
  header?: string;
  options: QuestionOption[];
  multiple: boolean;
  allowOther: boolean;
  otherPlaceholder?: string;
  otherLabel?: string;
}

/** Parsed ask-question arguments (aligned with backend AskQuestionParameter) */
interface ParsedAskQuestionArgs {
  questions: Question[];
  allowSupplement: boolean;
  supplementQuestion?: string;
  supplementPlaceholder?: string;
  supplementHeader?: string;
}

/** User's answers */
interface Answers {
  [questionIndex: number]: string[]; // stores selected option labels
}

/** Custom answer (when allowOther is selected) */
interface CustomAnswers {
  [questionIndex: number]: string;
}

/** Supplement information */
interface SupplementAnswer {
  text: string;
}

/** Parsed question-answer pair */
interface ParsedQA {
  header?: string;
  question: string;
  answers: string[];
}

/** Parse Q&A data from tool result */
function parseCompletedQA(argsStr: string, resultStr: string): ParsedQA[] {
  const parsedArgs = parseAskQuestionArgs(argsStr);
  const qas: ParsedQA[] = [];

  if (!parsedArgs || !resultStr) return qas;

  // Result format: [Question answered]\nQ1: answer1, answer2\nQ2: answer3
  const lines = resultStr.split('\n').filter(line => line.trim());
  const answerLines = lines.filter(line => line.startsWith('Q'));

  const questions = parsedArgs.questions;
  const supplementQuestion = parsedArgs.allowSupplement
    ? {
        header: parsedArgs.supplementHeader ?? 'Additional Information',
        question: parsedArgs.supplementQuestion ?? 'Additional Information',
        options: [],
        multiple: false,
        allowOther: false,
      }
    : null;

  const allQuestions = supplementQuestion
    ? [...questions, supplementQuestion]
    : questions;

  answerLines.forEach((line) => {
    const match = line.match(/^Q(\d+):\s*(.*)/);
    if (match) {
      const qIndex = parseInt(match[1], 10) - 1;
      const answerText = match[2].trim();
      const qaAnswers = answerText ? answerText.split(', ').map(a => a.trim()) : [];
      const sourceQuestion = allQuestions[qIndex];
      if (sourceQuestion) {
        qas.push({
          header: sourceQuestion.header,
          question: sourceQuestion.question,
          answers: qaAnswers,
        });
      }
    }
  });

  return qas;
}

function parseAskQuestionArgs(args: string): ParsedAskQuestionArgs | null {
  try {
    const parsed = JSON.parse(args);
    if (parsed.questions && Array.isArray(parsed.questions) && parsed.questions.length > 0) {
      return {
        questions: parsed.questions.map((q: Partial<Question>) => ({
          question: q.question ?? '',
          header: q.header,
          options: q.options ?? [],
          multiple: q.multiple ?? false,
          allowOther: q.allowOther ?? false,
          otherPlaceholder: q.otherPlaceholder,
          otherLabel: q.otherLabel,
        })),
        allowSupplement: parsed.allowSupplement ?? false,
        supplementQuestion: parsed.supplementQuestion,
        supplementPlaceholder: parsed.supplementPlaceholder,
        supplementHeader: parsed.supplementHeader,
      };
    }
  } catch {
    // ignore parse error
  }
  return null;
}

export function AskQuestionToolMessage({ toolCall, result, status, streamingOutput }: ToolMessageProps) {
  const sessionId = useChatStore((state) => state.sessionId);
  const globalIsStreaming = useChatStore((state) => state.isStreaming);
  // Combined check: global streaming ended and tool status is not COMPLETED/FAILED means awaiting user input
  const isAwaitingUserResponse = !globalIsStreaming && !result && status !== 'COMPLETED' && status !== 'FAILED';
  const args = streamingOutput ?? toolCall.args;
  const parsedArgs = args ? parseAskQuestionArgs(args) : null;
  const questions = parsedArgs?.questions ?? [];
  const allowSupplement = parsedArgs?.allowSupplement ?? false;
  const supplementQuestion = parsedArgs?.supplementQuestion ?? 'Do you have any additional information?';
  const supplementPlaceholder = parsedArgs?.supplementPlaceholder ?? 'Describe your specific thoughts...';
  const supplementHeader = parsedArgs?.supplementHeader ?? 'Additional Information';
  const isCompleted = status === 'COMPLETED';
  const isFailed = status === 'FAILED';
  const isRejected = result?.result === 'REJECTED';
  // Total steps = number of questions + optional supplement
  const totalSteps = questions.length + (allowSupplement ? 1 : 0);
  // Whether parsing succeeded (to distinguish incomplete JSON during streaming from truly empty questions)
  const isArgsParsingComplete = parsedArgs !== null;
  // Whether awaiting user input: global streaming ended + args parsed + has questions + not submitted
  const isAwaitingUserInput = isAwaitingUserResponse && isArgsParsingComplete && questions.length > 0;

  const [isOpen, setIsOpen] = useState(true);
  const [qaOpen, setQaOpen] = useState(false);
  const [currentStepIndex, setCurrentStepIndex] = useState(0);
  const [answers, setAnswers] = useState<Answers>({});
  const [customAnswers, setCustomAnswers] = useState<CustomAnswers>({});
  const [supplement, setSupplement] = useState<SupplementAnswer>({ text: '' });
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Whether current step is the supplement step
  const isSupplementStep = allowSupplement && currentStepIndex === questions.length;
  // Current question (if not supplement step)
  const currentQuestion = isSupplementStep ? null : questions[currentStepIndex];

  const handleSelectOption = useCallback((questionIndex: number, optionLabel: string, multiple: boolean, isOtherOption: boolean) => {
    setAnswers((prev) => {
      const currentAnswers = prev[questionIndex] ?? [];
      if (multiple) {
        const updated = currentAnswers.includes(optionLabel)
          ? currentAnswers.filter((l) => l !== optionLabel)
          : [...currentAnswers, optionLabel];
        return { ...prev, [questionIndex]: updated };
      } else {
        return { ...prev, [questionIndex]: [optionLabel] };
      }
    });
    // If "Other" option is selected, clear previous custom answer
    if (isOtherOption) {
      setCustomAnswers((prev) => ({ ...prev, [questionIndex]: '' }));
    }
    setError(null);
  }, []);

  const handleCustomAnswerChange = useCallback((questionIndex: number, text: string) => {
    setCustomAnswers((prev) => ({ ...prev, [questionIndex]: text }));
  }, []);

  const handleNext = useCallback(() => {
    if (isSupplementStep) {
      // Supplement step doesn't need validation, submit directly
      return;
    }
    const currentAnswers = answers[currentStepIndex] ?? [];
    if (currentAnswers.length === 0) {
      setError('Please select an option');
      return;
    }
    // Validate "Other" option has custom content filled
    const currentQuestion = questions[currentStepIndex];
    if (currentQuestion?.allowOther) {
      const otherLabel = currentQuestion.otherLabel ?? 'Other';
      const hasOtherSelected = currentAnswers.includes(otherLabel);
      if (hasOtherSelected && !customAnswers[currentStepIndex]?.trim()) {
        setError('Please enter custom content');
        return;
      }
    }
    setError(null);

    if (currentStepIndex < totalSteps - 1) {
      setCurrentStepIndex((prev) => prev + 1);
    }
  }, [answers, customAnswers, currentStepIndex, totalSteps, isSupplementStep, questions]);

  const handlePrev = useCallback(() => {
    if (currentStepIndex > 0) {
      setCurrentStepIndex((prev) => prev - 1);
      setError(null);
    }
  }, [currentStepIndex]);

  const handleSubmit = useCallback(async () => {
    // Validate all questions are answered
    for (let i = 0; i < questions.length; i++) {
      const currentAnswers = answers[i] ?? [];
      if (currentAnswers.length === 0) {
        setCurrentStepIndex(i);
        setError('Please answer all questions');
        return;
      }
      // Validate "Other" option has custom content filled
      const question = questions[i];
      if (question.allowOther) {
        const otherLabel = question.otherLabel ?? 'Other';
        const hasOtherSelected = currentAnswers.includes(otherLabel);
        if (hasOtherSelected && !customAnswers[i]?.trim()) {
          setCurrentStepIndex(i);
          setError('Please fill in the "Other" option content');
          return;
        }
      }
    }
    setError(null);

    // Build submission payload - backend expects List<List<String>> format
    const answerPayload: string[][] = questions.map((_, i) => {
      const selectedAnswers = answers[i] ?? [];
      // If custom answer exists ("Other" option), append to answers
      const customAnswer = customAnswers[i];
      return customAnswer && customAnswer.trim()
        ? [...selectedAnswers, customAnswer.trim()]
        : selectedAnswers;
    });

    // If supplement info exists, add to payload
    if (allowSupplement && supplement.text.trim()) {
      answerPayload.push([supplement.text.trim()]);
    }

    if (!sessionId) {
      setError('Missing sessionId');
      setIsSubmitting(false);
      return;
    }

    setIsSubmitting(true);

    const chatState = useChatStore.getState();
    // Close the Q&A form first
    setIsOpen(false);

    // Call SSE endpoint, listen for streaming events to enter streaming state
    answerQuestion(sessionId, toolCall.id, answerPayload, {
      onEvent: (event) => chatState.handleEvent(event),
      onDone: (event) => chatState.handleEvent(event),
      onError: (event) => chatState.handleEvent(event),
    });
  }, [answers, customAnswers, questions, allowSupplement, supplement, sessionId, toolCall.id]);

  const handleReject = useCallback(async () => {
    try {
      await authFetch(`/api/chat/question/${sessionId}/${toolCall.id}/reject`, {
        method: 'POST',
      });
      setIsOpen(false);
    } catch {
      setError('Operation failed, please retry');
    }
  }, [sessionId, toolCall.id]);

  // Already completed or rejected - show static result with Q&A
  if (isCompleted || isFailed || isRejected) {
    const statusText = isRejected
      ? 'Cancelled'
      : isFailed
        ? 'Failed'
        : 'Completed';

    const statusColor = isRejected
      ? 'text-muted-foreground'
      : isFailed
        ? 'text-destructive'
        : 'text-foreground';

    const statusDotColor = isRejected
      ? 'bg-muted-foreground'
      : isFailed
        ? 'bg-destructive'
        : 'bg-foreground';

    const qas = args && result?.result
      ? parseCompletedQA(args, result.result)
      : [];

    return (
      <div className="border border-border rounded-lg bg-card overflow-hidden">
        {/* Header bar */}
        <div
          className="p-3 flex items-center justify-between gap-2 cursor-pointer hover:bg-muted/50 transition-colors"
          onClick={() => setQaOpen(!qaOpen)}
        >
          <div className="flex items-center gap-2">
            <MessageSquare className="w-4 h-4 text-muted-foreground" />
            <span className="text-sm font-medium">Ask Question</span>
          </div>
          <div className="flex items-center gap-2">
            <span className={`w-2 h-2 rounded-full ${statusDotColor}`} />
            <span className={`text-sm font-medium ${statusColor}`}>
              {statusText}
            </span>
            <ChevronRight className={`w-4 h-4 text-muted-foreground transition-transform ${qaOpen ? 'rotate-90' : ''}`} />
          </div>
        </div>

        {/* Q&A content */}
        {qaOpen && qas.length > 0 && (
          <div className="px-4 pb-4 space-y-4 border-t border-border pt-4">
            {qas.map((qa, index) => (
              <div key={index} className="space-y-2">
                {/* Question header */}
                <div className="text-xs text-muted-foreground">
                  [{index + 1}] {qa.header ?? `Question ${index + 1}`}
                </div>
                {/* Question text */}
                <div className="text-sm text-foreground">
                  {qa.question}
                </div>
                {/* Answer */}
                <div className="text-sm text-muted-foreground">
                  {qa.answers.length > 0 ? qa.answers.join(', ') : 'Not answered'}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  // Ensure there's content to display
  if (questions.length === 0 && !allowSupplement) {
    // Streaming but args not yet fully parsed — show loading state
    if (globalIsStreaming && !isArgsParsingComplete) {
      return (
        <div className="border border-border rounded-lg bg-card overflow-hidden">
          <div className="p-3 flex items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <MessageSquare className="w-4 h-4 text-muted-foreground" />
              <span className="text-sm font-medium">Ask Question</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="w-2 h-2 rounded-full bg-muted-foreground animate-pulse" />
              <span className="text-xs text-muted-foreground">Loading...</span>
            </div>
          </div>
        </div>
      );
    }

    return (
      <div className="border border-border rounded-lg bg-card overflow-hidden">
        <div className="p-3 flex items-center gap-2">
          <MessageSquare className="w-4 h-4 text-muted-foreground" />
          <span className="text-sm font-medium">Ask Question</span>
          <span className="text-xs text-muted-foreground">(No questions)</span>
        </div>
      </div>
    );
  }

  // Get display text for current step
  const currentHeaderText = isSupplementStep
    ? supplementHeader
    : currentQuestion?.header ?? `Question ${currentStepIndex + 1}`;

  const currentQuestionText = isSupplementStep
    ? supplementQuestion
    : currentQuestion?.question ?? '';

  return (
    <div className="border border-border rounded-lg bg-card overflow-hidden">
      {/* Header bar */}
      <div
        className={`p-3 flex items-center justify-between gap-2 ${isOpen ? 'border-b border-border' : ''} cursor-pointer hover:bg-muted/50 transition-colors`}
        onClick={() => setIsOpen(!isOpen)}
      >
        <div className="flex items-center gap-2">
          <MessageSquare className="w-4 h-4 text-muted-foreground" />
          <span className="text-sm font-medium">Ask Question</span>
          {totalSteps > 0 && (
            <span className="text-xs text-muted-foreground">
              ({totalSteps} step{totalSteps > 1 ? 's' : ''})
            </span>
          )}
        </div>
        <div className="flex items-center gap-2">
          {!isOpen && globalIsStreaming && (
            <>
              <span className="w-2 h-2 rounded-full bg-muted-foreground animate-pulse" />
              <span className="text-sm font-medium text-muted-foreground">Loading...</span>
            </>
          )}
          {!isOpen && isAwaitingUserInput && (
            <>
              <span className="w-2 h-2 rounded-full bg-primary animate-pulse" />
              <span className="text-sm font-medium text-primary">Awaiting response</span>
            </>
          )}
          <ChevronRight className={`w-4 h-4 text-muted-foreground transition-transform ${isOpen ? 'rotate-90' : ''}`} />
        </div>
      </div>

      {/* Inline form */}
      {isOpen && globalIsStreaming && !isArgsParsingComplete && (
        <div className="p-4 flex items-center justify-center">
          <span className="text-sm text-muted-foreground">Preparing question...</span>
        </div>
      )}
      {isOpen && isAwaitingUserInput && (
        <div>
          {/* Progress indicator */}
          <div className="px-4 pt-3 pb-1">
            <div className="flex items-center justify-between mb-2">
              <span className="text-xs text-muted-foreground">
                {currentStepIndex + 1} / {totalSteps}
              </span>
              <span className="text-xs font-medium text-muted-foreground">
                {currentHeaderText}
              </span>
            </div>
            {/* Progress bar */}
            <div className="w-full h-1 bg-muted rounded-full overflow-hidden">
              <div
                className="h-full bg-primary transition-all duration-300"
                style={{ width: `${((currentStepIndex + 1) / totalSteps) * 100}%` }}
              />
            </div>
          </div>

          {/* Question */}
          <div className="px-4 pt-3 pb-2">
            <p className="text-base font-medium">{currentQuestionText}</p>
          </div>

          {/* Options or Supplement Input */}
          {isSupplementStep ? (
            <div className="px-4 pb-3">
              <textarea
                className="w-full p-3 text-sm border border-border rounded-lg bg-background resize-none focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent"
                rows={3}
                placeholder={supplementPlaceholder}
                value={supplement.text}
                onChange={(e) => setSupplement({ text: e.target.value })}
              />
            </div>
          ) : (
            <div className="px-4 pb-3 space-y-2">
              {currentQuestion!.options.map((option, optionIndex) => {
                const isSelected = (answers[currentStepIndex] ?? []).includes(option.label);
                return (
                  <Fragment key={optionIndex}>
                    <button
                      className={`w-full flex items-start gap-3 p-3 rounded-lg border text-left transition-colors ${
                        isSelected
                          ? 'border-primary bg-primary/10'
                          : 'border-border hover:bg-muted'
                      }`}
                      onClick={() => handleSelectOption(currentStepIndex, option.label, currentQuestion!.multiple, false)}
                    >
                      {/* Radio/Checkbox indicator */}
                      <div className="mt-0.5 flex-shrink-0">
                        {currentQuestion!.multiple ? (
                          <div className={`w-5 h-5 rounded border-2 flex items-center justify-center ${
                            isSelected ? 'border-primary bg-primary' : 'border-muted-foreground'
                          }`}>
                            {isSelected && (
                              <svg className="w-3 h-3 text-primary-foreground" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
                                <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                              </svg>
                            )}
                          </div>
                        ) : (
                          <div className={`w-5 h-5 rounded-full border-2 flex items-center justify-center ${
                            isSelected ? 'border-primary' : 'border-muted-foreground'
                          }`}>
                            {isSelected && (
                              <div className="w-2.5 h-2.5 rounded-full bg-primary" />
                            )}
                          </div>
                        )}
                      </div>

                      {/* Option content */}
                      <div className="flex-1 min-w-0">
                        <div className={`text-sm font-medium ${isSelected ? 'text-primary' : 'text-foreground'}`}>
                          {option.label}
                        </div>
                        {option.description && (
                          <div className="text-xs text-muted-foreground mt-0.5">
                            {option.description}
                          </div>
                        )}
                      </div>
                    </button>
                  </Fragment>
                );
              })}
              {/* "Other" option: automatically added when allowOther is true */}
              {currentQuestion!.allowOther && (() => {
                const otherLabel = currentQuestion!.otherLabel ?? 'Other';
                const isSelected = (answers[currentStepIndex] ?? []).includes(otherLabel);
                return (
                  <Fragment key="other-option">
                    <button
                      className={`w-full flex items-start gap-3 p-3 rounded-lg border text-left transition-colors ${
                        isSelected
                          ? 'border-primary bg-primary/10'
                          : 'border-border hover:bg-muted'
                      }`}
                      onClick={() => handleSelectOption(currentStepIndex, otherLabel, currentQuestion!.multiple, true)}
                    >
                      {/* Radio/Checkbox indicator */}
                      <div className="mt-0.5 flex-shrink-0">
                        {currentQuestion!.multiple ? (
                          <div className={`w-5 h-5 rounded border-2 flex items-center justify-center ${
                            isSelected ? 'border-primary bg-primary' : 'border-muted-foreground'
                          }`}>
                            {isSelected && (
                              <svg className="w-3 h-3 text-primary-foreground" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
                                <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                              </svg>
                            )}
                          </div>
                        ) : (
                          <div className={`w-5 h-5 rounded-full border-2 flex items-center justify-center ${
                            isSelected ? 'border-primary' : 'border-muted-foreground'
                          }`}>
                            {isSelected && (
                              <div className="w-2.5 h-2.5 rounded-full bg-primary" />
                            )}
                          </div>
                        )}
                      </div>

                      {/* Option content */}
                      <div className="flex-1 min-w-0">
                        <div className={`text-sm font-medium ${isSelected ? 'text-primary' : 'text-foreground'}`}>
                          {otherLabel}
                        </div>
                      </div>
                    </button>
                    {/* Custom input for Other option */}
                    {isSelected && (
                      <div className="px-1">
                        <input
                          type="text"
                          className="w-full p-2 text-sm border border-border rounded-md bg-background focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent"
                          placeholder={currentQuestion!.otherPlaceholder ?? 'Enter custom content...'}
                          value={customAnswers[currentStepIndex] ?? ''}
                          onChange={(e) => handleCustomAnswerChange(currentStepIndex, e.target.value)}
                        />
                      </div>
                    )}
                  </Fragment>
                );
              })()}
            </div>
          )}

          {/* Error message */}
          {error && (
            <div className="px-4 pb-2">
              <div className="flex items-center gap-2 p-2 rounded bg-destructive/10 text-destructive text-sm">
                <AlertCircle className="w-4 h-4 flex-shrink-0" />
                <span>{error}</span>
              </div>
            </div>
          )}

          {/* Footer actions */}
          <div className="flex items-center justify-between p-4 border-t border-border bg-muted/30">
            <button
              className="px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors rounded-md hover:bg-muted flex items-center gap-1"
              onClick={handleReject}
              disabled={isSubmitting}
            >
              <X className="w-4 h-4" />
              Cancel
            </button>
            <div className="flex items-center gap-2">
              {currentStepIndex > 0 && (
                <button
                  className="px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors rounded-md hover:bg-muted flex items-center gap-1"
                  onClick={handlePrev}
                  disabled={isSubmitting}
                >
                  <ChevronLeft className="w-4 h-4" />
                  Previous
                </button>
              )}
              {currentStepIndex < totalSteps - 1 ? (
                <button
                  className="px-4 py-2 text-sm font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1"
                  onClick={handleNext}
                  disabled={isSupplementStep ? false : (answers[currentStepIndex] ?? []).length === 0 || isSubmitting}
                >
                  Next
                  <ChevronRight className="w-4 h-4" />
                </button>
              ) : (
                <button
                  className="px-4 py-2 text-sm font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1"
                  onClick={handleSubmit}
                  disabled={isSubmitting}
                >
                  {isSubmitting ? 'Submitting...' : 'Submit'}
                  {!isSubmitting && <Check className="w-4 h-4" />}
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
