/**
 * Sandbox shell document for render_visual fragments.
 *
 * The iframe runs with `sandbox="allow-scripts"` and WITHOUT allow-same-origin,
 * so the host can only talk to it via postMessage: mount (sanitized fragment),
 * theme (swap :root variables without reloading) and export (rasterize inside
 * the sandbox where the variables are already resolved).
 */

export const RV_MSG = {
  MOUNT: 'render_visual:mount',
  THEME: 'render_visual:theme',
  HEIGHT: 'render_visual:height',
  ERROR: 'render_visual:error',
  EXPORT: 'render_visual:export',
  EXPORTED: 'render_visual:exported',
  EXPORT_ERROR: 'render_visual:export-error',
} as const;

export interface RenderVisualHeightMessage {
  type: typeof RV_MSG.HEIGHT;
  id: string;
  height: number;
}

export interface RenderVisualErrorMessage {
  type: typeof RV_MSG.ERROR;
  id: string;
  message: string;
}

export interface RenderVisualExportedMessage {
  type: typeof RV_MSG.EXPORTED;
  id: string;
  blob: Blob;
}

export interface RenderVisualExportErrorMessage {
  type: typeof RV_MSG.EXPORT_ERROR;
  id: string;
  message: string;
}

export type RenderVisualSandboxMessage =
  | RenderVisualHeightMessage
  | RenderVisualErrorMessage
  | RenderVisualExportedMessage
  | RenderVisualExportErrorMessage;

export function isRenderVisualSandboxMessage(data: unknown, id: string): data is RenderVisualSandboxMessage {
  if (typeof data !== 'object' || data === null) return false;
  const record = data as Record<string, unknown>;
  return record.id === id && typeof record.type === 'string' &&
    (record.type as string).startsWith('render_visual:');
}

export const CSP_POLICY =
  "default-src 'none'; " +
  "style-src 'unsafe-inline'; " +
  "script-src 'unsafe-inline' https://cdnjs.cloudflare.com https://esm.sh https://cdn.jsdelivr.net https://unpkg.com; " +
  "img-src data: blob: https:; " +
  "font-src https://cdnjs.cloudflare.com https://cdn.jsdelivr.net; " +
  "connect-src 'none';";

/**
 * In-sandbox runtime: height reporting, theme swap, error reporting and the
 * foreignObject rasterizer used for HTML fragments. Written with string
 * concatenation only — no template literals — so it can be embedded verbatim.
 */
const SHELL_SCRIPT_BODY = [
  '(function () {',
  '  var root = document.getElementById("root");',
  '  function send(msg) { parent.postMessage(msg, "*"); }',
  '  new ResizeObserver(function (entries) {',
  '    send({ type: "' + RV_MSG.HEIGHT + '", id: ID_PLACEHOLDER, height: entries[0].contentRect.height });',
  '  }).observe(root);',
  '  window.onerror = function (message) {',
  '    send({ type: "' + RV_MSG.ERROR + '", id: ID_PLACEHOLDER, message: String(message) });',
  '  };',
  '  window.addEventListener("message", function (event) {',
  '    var data = event.data;',
  '    if (!data || data.id !== ID_PLACEHOLDER) return;',
  '    if (data.type === "' + RV_MSG.MOUNT + '") {',
  '      mount(data.html);',
  '    } else if (data.type === "' + RV_MSG.THEME + '") {',
  '      document.getElementById("rv-tokens").textContent = ":root{" + data.css + "}";',
  '    } else if (data.type === "' + RV_MSG.EXPORT + '") {',
  '      exportImage();',
  '    }',
  '  });',
  // Scripts inserted via innerHTML never execute — rebuild them as live nodes.
  '  function mount(html) {',
  '    root.innerHTML = html;',
  '    var scripts = Array.prototype.slice.call(root.querySelectorAll("script"));',
  '    scripts.forEach(function (old) {',
  '      var live = document.createElement("script");',
  '      Array.prototype.slice.call(old.attributes).forEach(function (attr) {',
  '        live.setAttribute(attr.name, attr.value);',
  '      });',
  '      live.textContent = old.textContent;',
  '      old.parentNode.replaceChild(live, old);',
  '    });',
  '  }',
  '  function exportImage() {',
  '    try {',
  '      var w = root.scrollWidth;',
  '      var h = root.scrollHeight;',
  '      if (!w || !h) throw new Error("empty fragment");',
  '      var clone = root.cloneNode(true);',
  '      var wrapper = document.createElement("div");',
  '      wrapper.setAttribute("xmlns", "http://www.w3.org/1999/xhtml");',
  '      wrapper.id = "rv-export";',
  '      var style = document.createElement("style");',
  '      style.textContent = "#rv-export{" + document.getElementById("rv-tokens").textContent.replace(/^:root\\{|\\}$/g, "") + "}";',
  '      wrapper.appendChild(style);',
  '      while (clone.firstChild) wrapper.appendChild(clone.firstChild);',
  '      var svg = \'<svg xmlns="http://www.w3.org/2000/svg" width="\' + w + \'" height="\' + h + \'">\' +',
  '        \'<foreignObject width="100%" height="100%">\' +',
  '        new XMLSerializer().serializeToString(wrapper) +',
  '        \'</foreignObject></svg>\';',
  // The sandbox runs at an opaque origin, where a blob:-URL image taints the
  // canvas and toBlob throws SecurityError — inline the SVG as a data: URL.
  '      var url = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);',
  '      var img = new Image();',
  '      img.onload = function () {',
  '        try {',
  '          rasterize(img, w, h);',
  '        } catch (e) {',
  '          fail(e);',
  '        }',
  '      };',
  '      img.onerror = function () {',
  '        fail("external resources cannot be rasterized");',
  '      };',
  '      img.src = url;',
  '    } catch (e) {',
  '      fail(e);',
  '    }',
  '  }',
  // Async export callbacks must never throw: an uncaught error here reaches
  // window.onerror and the host would misreport it as a render failure.
  '  function fail(e) {',
  '    send({ type: "' + RV_MSG.EXPORT_ERROR + '", id: ID_PLACEHOLDER, message: String(e && e.message || e) });',
  '  }',
  '  function rasterize(img, w, h) {',
  '    var canvas = document.createElement("canvas");',
  '    canvas.width = w * 2;',
  '    canvas.height = h * 2;',
  '    var ctx = canvas.getContext("2d");',
  '    if (!ctx) throw new Error("canvas unavailable");',
  '    ctx.scale(2, 2);',
  '    ctx.drawImage(img, 0, 0);',
  '    canvas.toBlob(function (blob) {',
  '      try {',
  '        if (blob) send({ type: "' + RV_MSG.EXPORTED + '", id: ID_PLACEHOLDER, blob: blob });',
  '        else fail("canvas export failed");',
  '      } catch (e) {',
  '        fail(e);',
  '      }',
  '    }, "image/png");',
  '  }',
  '})();',
].join('\n');

export function buildShellSrcdoc(id: string, tokensCss: string): string {
  // Embed the id as a JS string literal; escape `<` so the script can never
  // close the surrounding <script> tag early.
  const embeddedId = JSON.stringify(id).replace(/</g, '\\u003C');
  const script = SHELL_SCRIPT_BODY.split('ID_PLACEHOLDER').join(embeddedId);
  return (
    '<!DOCTYPE html>\n' +
    '<html><head>\n' +
    '<meta http-equiv="Content-Security-Policy" content="' + CSP_POLICY + '">\n' +
    '<style id="rv-tokens">:root{' + tokensCss + '}</style>\n' +
    '<style>\n' +
    'html, body { margin: 0; padding: 0; background: transparent; }\n' +
    'body { font-family: var(--font-sans); color: var(--color-text-primary); font-size: 13px; }\n' +
    'body { overflow-x: hidden; overflow-y: auto; }\n' +
    '</style>\n' +
    '</head><body>\n' +
    '<div id="root"></div>\n' +
    '<script>' + script + '</script>\n' +
    '</body></html>'
  );
}
