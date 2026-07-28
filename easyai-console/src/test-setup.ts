// Provide browser globals not available in Node test environment.
// api-client.ts accesses sessionStorage at module level.
const storage = new Map<string, string>();

Object.defineProperty(globalThis, 'sessionStorage', {
  value: {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => { storage.set(key, value); },
    removeItem: (key: string) => { storage.delete(key); },
    clear: () => { storage.clear(); },
    get length() { return storage.size; },
    key: (index: number) => [...storage.keys()][index] ?? null,
  },
  writable: true,
});
