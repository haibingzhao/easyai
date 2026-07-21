/**
 * AskQuestion 工具消息渲染组件
 * 直接在 tool card 内联展示多步问答表单
 * 适配后端 AskQuestionParameter 类格式
 */

import { useState, useCallback, Fragment } from 'react';
import { MessageSquare, ChevronRight, ChevronLeft, AlertCircle, Check, X } from 'lucide-react';
import { useChatStore } from '@/services/stores/chat-store';
import { answerQuestion } from '@/services/chat-service';
import { authFetch } from '@/services/api-client';
import type { ToolMessageProps } from './types';

/** 问答选项（对齐后端 QuestionOption） */
interface QuestionOption {
  label: string;
  description?: string;
  isOther?: boolean;
}

/** 单个问题（对齐后端 QuestionParameter） */
interface Question {
  question: string;
  header?: string;
  options: QuestionOption[];
  multiple: boolean;
  allowOther: boolean;
  otherPlaceholder?: string;
  otherLabel?: string;
}

/** 解析后的问答参数（对齐后端 AskQuestionParameter） */
interface ParsedAskQuestionArgs {
  questions: Question[];
  allowSupplement: boolean;
  supplementQuestion?: string;
  supplementPlaceholder?: string;
  supplementHeader?: string;
}

/** 用户的答案 */
interface Answers {
  [questionIndex: number]: string[]; // 存储选中的 option label
}

/** 自定义回答（allowOther 选中时） */
interface CustomAnswers {
  [questionIndex: number]: string;
}

/** 补充信息 */
interface SupplementAnswer {
  text: string;
}

/** 解析后的问题回答对 */
interface ParsedQA {
  header?: string;
  question: string;
  answers: string[];
}

/** 解析工具结果中的问答数据 */
function parseCompletedQA(argsStr: string, resultStr: string): ParsedQA[] {
  const parsedArgs = parseAskQuestionArgs(argsStr);
  const qas: ParsedQA[] = [];

  if (!parsedArgs || !resultStr) return qas;

  // 解析结果格式: [Question answered]\nQ1: answer1, answer2\nQ2: answer3
  const lines = resultStr.split('\n').filter(line => line.trim());
  const answerLines = lines.filter(line => line.startsWith('Q'));

  const questions = parsedArgs.questions;
  const supplementQuestion = parsedArgs.allowSupplement
    ? {
        header: parsedArgs.supplementHeader ?? '补充信息',
        question: parsedArgs.supplementQuestion ?? '补充信息',
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
  // 综合判断：全局 streaming 结束且工具状态不是 COMPLETED/FAILED，说明在等待用户输入
  const isAwaitingUserResponse = !globalIsStreaming && !result && status !== 'COMPLETED' && status !== 'FAILED';
  const args = streamingOutput ?? toolCall.args;
  const parsedArgs = args ? parseAskQuestionArgs(args) : null;
  const questions = parsedArgs?.questions ?? [];
  const allowSupplement = parsedArgs?.allowSupplement ?? false;
  const supplementQuestion = parsedArgs?.supplementQuestion ?? '你有什么补充信息吗？';
  const supplementPlaceholder = parsedArgs?.supplementPlaceholder ?? '描述你的具体想法...';
  const supplementHeader = parsedArgs?.supplementHeader ?? '补充信息';
  const isCompleted = status === 'COMPLETED';
  const isFailed = status === 'FAILED';
  const isRejected = result?.result === 'REJECTED';
  // 总步骤数 = 问题数 + 可选的补充信息
  const totalSteps = questions.length + (allowSupplement ? 1 : 0);
  // 解析是否成功（用于区分 streaming 中的不完整 JSON 和真正的空问题）
  const isArgsParsingComplete = parsedArgs !== null;
  // 是否正在等待用户输入：全局 streaming 已结束 + args 已解析 + 有问题 + 未提交
  const isAwaitingUserInput = isAwaitingUserResponse && isArgsParsingComplete && questions.length > 0;

  const [isOpen, setIsOpen] = useState(true);
  const [qaOpen, setQaOpen] = useState(false);
  const [currentStepIndex, setCurrentStepIndex] = useState(0);
  const [answers, setAnswers] = useState<Answers>({});
  const [customAnswers, setCustomAnswers] = useState<CustomAnswers>({});
  const [supplement, setSupplement] = useState<SupplementAnswer>({ text: '' });
  const [error, setError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // 当前步骤是否是补充信息
  const isSupplementStep = allowSupplement && currentStepIndex === questions.length;
  // 当前问题（如果不是补充信息步骤）
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
    // 如果选中的是"其它"选项，清空之前的自定义回答
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
      // 补充信息步骤不需要验证，直接提交
      return;
    }
    const currentAnswers = answers[currentStepIndex] ?? [];
    if (currentAnswers.length === 0) {
      setError('请选择一个选项');
      return;
    }
    // 验证"其它"选项是否填写了自定义内容
    const currentQuestion = questions[currentStepIndex];
    if (currentQuestion?.allowOther) {
      const otherLabel = currentQuestion.otherLabel ?? '其它';
      const hasOtherSelected = currentAnswers.includes(otherLabel);
      if (hasOtherSelected && !customAnswers[currentStepIndex]?.trim()) {
        setError('请输入自定义内容');
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
    // 验证所有问题都已回答
    for (let i = 0; i < questions.length; i++) {
      const currentAnswers = answers[i] ?? [];
      if (currentAnswers.length === 0) {
        setCurrentStepIndex(i);
        setError('请回答所有问题');
        return;
      }
      // 验证"其它"选项是否填写了自定义内容
      const question = questions[i];
      if (question.allowOther) {
        const otherLabel = question.otherLabel ?? '其它';
        const hasOtherSelected = currentAnswers.includes(otherLabel);
        if (hasOtherSelected && !customAnswers[i]?.trim()) {
          setCurrentStepIndex(i);
          setError('请填写"其它"选项的内容');
          return;
        }
      }
    }
    setError(null);

    // 构建提交载荷 - 后端期望 List<List<String>> 格式
    const answerPayload: string[][] = questions.map((_, i) => {
      const selectedAnswers = answers[i] ?? [];
      // 如果有自定义回答（"其它"选项），追加到答案中
      const customAnswer = customAnswers[i];
      return customAnswer && customAnswer.trim()
        ? [...selectedAnswers, customAnswer.trim()]
        : selectedAnswers;
    });

    // 如果有补充信息，添加到载荷中
    if (allowSupplement && supplement.text.trim()) {
      answerPayload.push([supplement.text.trim()]);
    }

    if (!sessionId) {
      setError('缺少 sessionId');
      setIsSubmitting(false);
      return;
    }

    setIsSubmitting(true);

    const chatState = useChatStore.getState();
    // 先关闭问答表单
    setIsOpen(false);

    // 调用 SSE 接口，监听流式事件进入 streaming 状态
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
      setError('操作失败，请重试');
    }
  }, [sessionId, toolCall.id]);

  // Already completed or rejected - show static result with Q&A
  if (isCompleted || isFailed || isRejected) {
    const statusText = isRejected
      ? '已取消'
      : isFailed
        ? '失败'
        : '已完成';

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
                  [{index + 1}] {qa.header ?? `问题 ${index + 1}`}
                </div>
                {/* Question text */}
                <div className="text-sm text-foreground">
                  {qa.question}
                </div>
                {/* Answer */}
                <div className="text-sm text-muted-foreground">
                  {qa.answers.length > 0 ? qa.answers.join(', ') : '未回答'}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    );
  }

  // 确保有内容可显示
  if (questions.length === 0 && !allowSupplement) {
    // Streaming 中但 args 还未完整解析 — 显示加载状态
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

  // 获取当前步骤的显示文本
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
              {/* "其它"选项：当 allowOther 为 true 时自动添加 */}
              {currentQuestion!.allowOther && (() => {
                const otherLabel = currentQuestion!.otherLabel ?? '其它';
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
                    {/* 其它选项的自定义输入框 */}
                    {isSelected && (
                      <div className="px-1">
                        <input
                          type="text"
                          className="w-full p-2 text-sm border border-border rounded-md bg-background focus:outline-none focus:ring-2 focus:ring-primary focus:border-transparent"
                          placeholder={currentQuestion!.otherPlaceholder ?? '请输入自定义内容...'}
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
              取消
            </button>
            <div className="flex items-center gap-2">
              {currentStepIndex > 0 && (
                <button
                  className="px-4 py-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors rounded-md hover:bg-muted flex items-center gap-1"
                  onClick={handlePrev}
                  disabled={isSubmitting}
                >
                  <ChevronLeft className="w-4 h-4" />
                  上一个
                </button>
              )}
              {currentStepIndex < totalSteps - 1 ? (
                <button
                  className="px-4 py-2 text-sm font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1"
                  onClick={handleNext}
                  disabled={isSupplementStep ? false : (answers[currentStepIndex] ?? []).length === 0 || isSubmitting}
                >
                  下一个
                  <ChevronRight className="w-4 h-4" />
                </button>
              ) : (
                <button
                  className="px-4 py-2 text-sm font-medium bg-primary text-primary-foreground rounded-md hover:bg-primary/90 transition-colors disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1"
                  onClick={handleSubmit}
                  disabled={isSubmitting}
                >
                  {isSubmitting ? '提交中...' : '提交'}
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
