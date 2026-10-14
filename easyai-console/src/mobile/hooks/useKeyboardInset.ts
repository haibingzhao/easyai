import { useEffect, useState } from 'react';

/** Anything under this many pixels is scroll chrome, not the keyboard. */
const KEYBOARD_MIN_HEIGHT = 80;

/**
 * Height in px currently occupied by the on-screen keyboard.
 *
 * iOS reports neither `100vh` changes nor `interactive-widget` behaviour, so the only way to keep
 * the composer above the keyboard is to diff `visualViewport.height` against the layout viewport.
 * The caller offsets the composer by this value instead of scrolling the input into view —
 * `scrollIntoView` on iOS fights the viewport and leaves the page jittering.
 */
export function useKeyboardInset(): number {
  const [inset, setInset] = useState(0);

  useEffect(() => {
    const vv = window.visualViewport;
    if (!vv) return;

    const update = () => {
      const next = window.innerHeight - vv.height - vv.offsetTop;
      setInset(next > KEYBOARD_MIN_HEIGHT ? next : 0);
    };

    update();
    vv.addEventListener('resize', update);
    vv.addEventListener('scroll', update);
    return () => {
      vv.removeEventListener('resize', update);
      vv.removeEventListener('scroll', update);
    };
  }, []);

  return inset;
}
