import { beforeEach } from 'vitest';
import '@testing-library/jest-dom/vitest';
import 'vitest-axe/extend-expect';

// jsdom lacks the layout/pointer APIs that Radix Popover (`react-popper`) and
// cmdk touch when opening. Stub them so combobox-style components can be tested.
if (typeof globalThis.ResizeObserver === 'undefined') {
  globalThis.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
}
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.setPointerCapture = Element.prototype.setPointerCapture ?? (() => {});
Element.prototype.releasePointerCapture = Element.prototype.releasePointerCapture ?? (() => {});

// Node 22+ defines a global `localStorage` getter (require('internal/webstorage'))
// that returns undefined unless Node is started with --experimental-webstorage.
// Under jsdom this getter shadows jsdom's own Storage, so `localStorage` is
// undefined while `sessionStorage` works. The console keeps the bearer token in
// localStorage, so install an in-memory Storage the tests can rely on.
class MemoryStorage {
  private store = new Map<string, string>();
  get length() {
    return this.store.size;
  }
  clear() {
    this.store.clear();
  }
  getItem(key: string) {
    return this.store.has(key) ? this.store.get(key)! : null;
  }
  key(index: number) {
    return Array.from(this.store.keys())[index] ?? null;
  }
  removeItem(key: string) {
    this.store.delete(key);
  }
  setItem(key: string, value: string) {
    this.store.set(key, String(value));
  }
}

const memoryStorage = new MemoryStorage();
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  get: () => memoryStorage,
});

beforeEach(() => {
  memoryStorage.clear();
});
