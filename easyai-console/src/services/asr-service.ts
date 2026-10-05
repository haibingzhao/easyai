import { authFetch, fetchJson, JSON_HEADERS } from '@/services/api-client';

const API_BASE = '/api/asr';

/**
 * Dictation backend calls: one WAV segment in, transcription text out;
 * a dictation round plus its editor context in, the rewritten text out.
 */
export const asrService = {
  async transcribeSegment(blob: Blob, language?: string): Promise<string> {
    const form = new FormData();
    form.append('file', blob, 'segment.wav');
    if (language) form.append('language', language);
    const response = await authFetch(`${API_BASE}/transcribe`, { method: 'POST', body: form });
    if (!response.ok) {
      throw new Error(`Transcription failed: ${response.status}`);
    }
    const data = (await response.json()) as { text: string };
    return data.text ?? '';
  },

  async refineText(text: string, contextBefore: string, contextAfter: string): Promise<string> {
    const data = await fetchJson<{ text: string }>(`${API_BASE}/refine`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify({ text, contextBefore, contextAfter }),
    });
    return data.text ?? '';
  },
};
