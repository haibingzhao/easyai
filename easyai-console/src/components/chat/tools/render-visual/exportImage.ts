/**
 * "Save as image" for render_visual cards.
 *
 * SVG fragments are rasterized host-side (the source string is already here);
 * HTML fragments are rasterized inside the sandbox, where the contract CSS
 * variables are resolved. Both end in a PNG blob download.
 */

import { RV_MSG, type RenderVisualExportErrorMessage, type RenderVisualExportedMessage } from './shell';
import { tokensCssForMode, currentMode } from './theme';

const MAX_RASTER_EDGE = 4000;

export function downloadBlob(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function safeFileName(title: string, extension: string): string {
  const base = title.trim().replace(/[\\/:*?"<>|]/g, '_').slice(0, 80) || 'visual';
  return `${base}.${extension}`;
}

function rasterizeSvgSource(svgSource: string, width: number, height: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(new Blob([svgSource], { type: 'image/svg+xml;charset=utf-8' }));
    const image = new Image();
    image.onload = () => {
      try {
        const scale = Math.min(2, MAX_RASTER_EDGE / Math.max(width, height));
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(width * scale);
        canvas.height = Math.round(height * scale);
        const ctx = canvas.getContext('2d');
        if (!ctx) throw new Error('canvas unavailable');
        ctx.scale(scale, scale);
        ctx.drawImage(image, 0, 0);
        canvas.toBlob((blob) => {
          URL.revokeObjectURL(url);
          if (blob) resolve(blob);
          else reject(new Error('canvas export failed'));
        }, 'image/png');
      } catch (error) {
        URL.revokeObjectURL(url);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('external resources cannot be rasterized'));
    };
    image.src = url;
  });
}

/** Rasterize an SVG fragment host-side, injecting the active theme tokens. */
export async function exportSvgFragmentAsImage(code: string): Promise<Blob> {
  const parser = new DOMParser();
  const doc = parser.parseFromString(code, 'image/svg+xml');
  if (doc.querySelector('parsererror')) {
    throw new Error('fragment is not valid SVG');
  }
  const svg = doc.documentElement;
  const viewBox = svg.getAttribute('viewBox');
  let width = parseFloat(svg.getAttribute('width') || '');
  let height = parseFloat(svg.getAttribute('height') || '');
  if (viewBox) {
    const parts = viewBox.split(/[\s,]+/).map(Number);
    if (parts.length === 4) {
      width = Number.isFinite(width) ? width : parts[2];
      height = Number.isFinite(height) ? height : parts[3];
    }
  }
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    throw new Error('SVG needs a viewBox or explicit width/height to be exported');
  }
  const style = doc.createElementNS('http://www.w3.org/2000/svg', 'style');
  style.textContent = `:root{${tokensCssForMode(currentMode())}}`;
  svg.insertBefore(style, svg.firstChild);
  svg.setAttribute('width', String(width));
  svg.setAttribute('height', String(height));
  return rasterizeSvgSource(new XMLSerializer().serializeToString(svg), width, height);
}

/** Ask the sandbox to rasterize its rendered body and hand the PNG back. */
export function requestSandboxExport(iframe: HTMLIFrameElement, id: string): Promise<Blob> {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      window.removeEventListener('message', listener);
      reject(new Error('export timed out'));
    }, 15000);
    const listener = (event: MessageEvent) => {
      const data = event.data as RenderVisualExportedMessage | RenderVisualExportErrorMessage;
      if (!data || data.id !== id) return;
      if (data.type === RV_MSG.EXPORTED) {
        window.removeEventListener('message', listener);
        clearTimeout(timeout);
        resolve(data.blob);
      } else if (data.type === RV_MSG.EXPORT_ERROR) {
        window.removeEventListener('message', listener);
        clearTimeout(timeout);
        reject(new Error(data.message));
      }
    };
    window.addEventListener('message', listener);
    iframe.contentWindow?.postMessage({ type: RV_MSG.EXPORT, id }, '*');
  });
}
