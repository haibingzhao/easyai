import React, { useEffect, useRef } from 'react';

/** Live mic-level bars drawn from the recording analyser, shown in place of the mic/send button. */
export const VoiceWaveform: React.FC<{ analyserRef: React.RefObject<AnalyserNode | null> }> = ({ analyserRef }) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = canvasRef.current;
    const ctx = canvas?.getContext('2d');
    if (!canvas || !ctx) return;
    let raf = 0;
    const data = new Uint8Array(128);
    const bars = 16;
    const draw = () => {
      raf = requestAnimationFrame(draw);
      const w = canvas.width;
      const h = canvas.height;
      ctx.clearRect(0, 0, w, h);
      const analyser = analyserRef.current;
      if (!analyser) return;
      analyser.getByteTimeDomainData(data);
      ctx.fillStyle = '#10b981';
      const step = Math.floor(data.length / bars);
      for (let i = 0; i < bars; i++) {
        let peak = 0;
        for (let j = 0; j < step; j++) peak = Math.max(peak, Math.abs(data[i * step + j] - 128));
        const bh = Math.max(2, (peak / 128) * (h - 2));
        ctx.fillRect((i * w) / bars + 1, (h - bh) / 2, w / bars - 2, bh);
      }
    };
    draw();
    return () => cancelAnimationFrame(raf);
  }, [analyserRef]);
  return <canvas ref={canvasRef} width={96} height={20} className="mx-1" />;
};
