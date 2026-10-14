import { useCallback, useEffect, useRef, useState } from 'react';
import { useChatStore } from '@/services/stores/chat-store';

/** Distance in px from the container bottom that still counts as "at the bottom". */
const SCROLL_BOTTOM_THRESHOLD = 50;

/**
 * Keeps the transcript pinned to the newest content, and tracks which user question the
 * current viewport belongs to.
 *
 * Auto-follow is a *user intent* state, not a scroll position: it turns off the moment the user
 * scrolls up during a stream and only turns back on when they reach the bottom again. The wheel
 * listener exists because a wheel event lands before the resulting scroll event, which makes it
 * the reliable signal on desktop; on touch the `scrollTop` decrease inside `onScroll` is enough,
 * so the same code serves both.
 *
 * Extracted from `ChatPanel` for reuse by the mobile shell.
 */
export function useMessageAutoScroll() {
  const messages = useChatStore((state) => state.messages);
  const streamingBlocks = useChatStore((state) => state.streamingBlocks);
  const isStreaming = useChatStore((state) => state.isStreaming);
  const sessionId = useChatStore((state) => state.sessionId);
  const runningSessionId = useChatStore((state) => state.runningSessionId);

  const containerRef = useRef<HTMLDivElement>(null);
  const autoScrollEnabledRef = useRef(true);
  const prevScrollTopRef = useRef(0);
  const [followEnabled, setFollowEnabled] = useState(true);
  const [activeUserMessage, setActiveUserMessage] = useState<string | null>(null);
  const activeMsgRafRef = useRef<number | null>(null);

  const setFollow = useCallback((enabled: boolean) => {
    autoScrollEnabledRef.current = enabled;
    setFollowEnabled((prev) => (prev === enabled ? prev : enabled));
  }, []);

  /** Whether the transcript is at (or near) the newest message. */
  const isAtBottom = useCallback(() => {
    const el = containerRef.current;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= SCROLL_BOTTOM_THRESHOLD;
  }, []);

  const scrollToBottom = useCallback((force = false) => {
    const el = containerRef.current;
    if (!el || (!force && !autoScrollEnabledRef.current)) return;
    prevScrollTopRef.current = el.scrollTop;
    el.scrollTo({ top: el.scrollHeight, behavior: 'auto' });
  }, []);

  /**
   * Detect the nearest user message at or above the top of the visible viewport.
   * This shows the user message that initiated the currently visible assistant response,
   * not necessarily the one scrolled into view.
   */
  const updateActiveUserMessage = useCallback(() => {
    const container = containerRef.current;
    if (!container) return;

    const containerRect = container.getBoundingClientRect();
    const viewportTop = containerRect.top;

    // Collect all user message elements inside the scroll container
    const userMsgElements = container.querySelectorAll<HTMLElement>('[data-user-message="true"]');
    if (userMsgElements.length === 0) {
      setActiveUserMessage((prev) => (prev === null ? prev : null));
      return;
    }

    // Find the last user message whose top is at or above the viewport top
    let foundText: string | null = null;
    for (const el of userMsgElements) {
      const elRect = el.getBoundingClientRect();
      if (elRect.top <= viewportTop) {
        const contentDiv = el.querySelector('.whitespace-pre-wrap');
        const text = contentDiv?.getAttribute('data-message-content') ?? contentDiv?.textContent?.trim() ?? '';
        if (text) foundText = text;
      } else {
        // Elements are in DOM order; once we pass the viewport top, stop
        break;
      }
    }

    // Fallback: if scrolled to the very top and no message is above viewport,
    // use the first user message
    if (!foundText) {
      const first = userMsgElements[0];
      const contentDiv = first.querySelector('.whitespace-pre-wrap');
      foundText = contentDiv?.getAttribute('data-message-content') ?? contentDiv?.textContent?.trim() ?? null;
    }

    setActiveUserMessage((prev) => (prev === foundText ? prev : foundText));
  }, []);

  /**
   * Handle messages container scroll event:
   * - Non-streaming: toggle auto-scroll based on scroll position
   * - Streaming: detect user scroll-up via scrollTop decrease (wheel event handled separately below)
   */
  const onScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;

    const currentScrollTop = el.scrollTop;
    const prevScrollTop = prevScrollTopRef.current;
    prevScrollTopRef.current = currentScrollTop;

    if (isStreaming) {
      // scrollTop decrease = user scrolled up (programmatic scrollTo only increases scrollTop)
      if (currentScrollTop < prevScrollTop - 5) {
        setFollow(false);
      } else if (isAtBottom() && !autoScrollEnabledRef.current) {
        // Re-enable auto-scroll when user manually scrolls back to bottom
        setFollow(true);
      }
      return;
    }

    setFollow(isAtBottom());
    // Also update toolbar preview on scroll
    updateActiveUserMessage();
  }, [isStreaming, isAtBottom, updateActiveUserMessage, setFollow]);

  // Wheel event: immediately disable auto-scroll when user scrolls up during streaming (fires before scroll event, most reliable)
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const handleWheel = (e: WheelEvent) => {
      if (isStreaming && e.deltaY < 0) {
        setFollow(false);
      }
    };
    el.addEventListener('wheel', handleWheel, { passive: true });
    return () => el.removeEventListener('wheel', handleWheel);
  }, [isStreaming, setFollow]);

  // Auto-scroll to bottom during streaming
  useEffect(() => {
    if (isStreaming && autoScrollEnabledRef.current) {
      requestAnimationFrame(() => {
        scrollToBottom();
      });
    }
  }, [messages, streamingBlocks, isStreaming, scrollToBottom]);

  // Update toolbar preview when messages change (session load, new message, etc.)
  useEffect(() => {
    // Use rAF to ensure DOM has rendered before detecting
    if (activeMsgRafRef.current) cancelAnimationFrame(activeMsgRafRef.current);
    activeMsgRafRef.current = requestAnimationFrame(() => {
      updateActiveUserMessage();
    });
    return () => {
      if (activeMsgRafRef.current) cancelAnimationFrame(activeMsgRafRef.current);
    };
  }, [messages, updateActiveUserMessage]);

  // When streaming ends, re-evaluate whether to enable auto-scroll based on current position
  useEffect(() => {
    if (!isStreaming) {
      setFollow(isAtBottom());
    }
  }, [isStreaming, isAtBottom, setFollow]);

  // Auto-scroll to bottom when entering a running session
  useEffect(() => {
    if (!runningSessionId) return;
    const el = containerRef.current;
    if (el) {
      el.scrollTo({ top: el.scrollHeight, behavior: 'auto' });
    }
  }, [runningSessionId]);

  // Scroll to bottom when switching sessions (history load, fork navigation, startup restore)
  const prevSessionIdRef = useRef<string | null>(null);
  useEffect(() => {
    const prev = prevSessionIdRef.current;
    prevSessionIdRef.current = sessionId;
    if (!sessionId || sessionId === prev) return;
    const el = containerRef.current;
    if (!el) return;
    // This jump is ours, not the user's: reset the scroll-up heuristic first, or switching into a
    // shorter session (a smaller scrollTop than the one we left) would read as "user scrolled up"
    // in onScroll and switch auto-follow off for the whole new session.
    setFollow(true);
    prevScrollTopRef.current = 0;
    requestAnimationFrame(() => {
      el.scrollTo({ top: el.scrollHeight, behavior: 'auto' });
    });
  }, [sessionId, setFollow]);

  const resumeFollow = useCallback(() => setFollow(true), [setFollow]);

  return {
    containerRef,
    onScroll,
    scrollToBottom,
    /** False while the user is reading above the newest message — show the "jump to latest" affordance. */
    followEnabled,
    resumeFollow,
    activeUserMessage,
  };
}
