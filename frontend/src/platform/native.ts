import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import type { ChatSocket, Transport } from './transport';

interface SocketEvent { id: string; kind: 'open' | 'message' | 'close'; data?: number[] }
class NativeSocket extends EventTarget implements ChatSocket {
  readyState = 0;
  binaryType = 'arraybuffer';
  private id = typeof crypto.randomUUID === 'function' ? crypto.randomUUID() : crypto.getRandomValues(new Uint32Array(4)).join('-');
  private unlisten?: UnlistenFn;
  // Preserve frame ordering even when multiple asynchronous IPC sends are queued.
  private sends = Promise.resolve();
  constructor() { super(); void this.open(); }
  private async open() {
    try {
      this.unlisten = await listen<SocketEvent>('xim-socket', ({ payload }) => {
        if (payload.id !== this.id || this.readyState >= 2) return;
        if (payload.kind === 'open') { this.readyState = 1; this.dispatchEvent(new Event('open')); }
        else if (payload.kind === 'message') this.dispatchEvent(new MessageEvent('message', { data: new Uint8Array(payload.data!).buffer }));
        else this.finish();
      });
      if (this.readyState >= 2) { this.unlisten(); return; }
      await invoke('socket_open', { id: this.id });
      if (this.readyState >= 2) await invoke('socket_close', { id: this.id });
    } catch { if (this.readyState < 2) { this.dispatchEvent(new Event('error')); this.finish(); } }
  }
  send(data: ArrayBuffer) {
    if (this.readyState !== 1) throw new Error('Socket is not open');
    this.sends = this.sends.then(() => invoke<void>('socket_send', { id: this.id, data: Array.from(new Uint8Array(data)) }))
      .catch(() => { this.dispatchEvent(new Event('error')); this.close(); });
  }
  close() {
    if (this.readyState >= 2) return;
    this.finish();
    void invoke('socket_close', { id: this.id }).catch(() => { /* Already closed. */ });
  }
  private finish() { this.readyState = 3; this.unlisten?.(); this.dispatchEvent(new Event('close')); }
}
let exchanges: Promise<unknown> = Promise.resolve();
export const nativeTransport: Transport = {
  socket: () => new NativeSocket(),
  // The native side pins the build-time service URL and never returns the Cookie to JS.
  session: async (_url, init) => {
    if (init.signal?.aborted) throw init.signal.reason || new DOMException('Aborted', 'AbortError');
    const ticket = init.body ? (JSON.parse(String(init.body)) as { ticket: string }).ticket : undefined;
    const pending = exchanges.catch(() => {}).then(() => {
      if (init.signal?.aborted) throw init.signal.reason || new DOMException('Aborted', 'AbortError');
      return invoke<{ ok: boolean }>('session_exchange', { method: init.method, ticket });
    });
    exchanges = pending;
    const result = await pending;
    if (init.signal?.aborted) throw init.signal.reason || new DOMException('Aborted', 'AbortError');
    return result;
  },
};
