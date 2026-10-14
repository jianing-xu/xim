/** Browser remains independent of Tauri. The native module loads only inside the App. */
export interface ChatSocket extends EventTarget {
  readonly readyState: number;
  binaryType: string;
  send(data: ArrayBuffer): void;
  close(): void;
}
export interface Transport {
  socket(url: string): ChatSocket;
  session(url: string, init: RequestInit): Promise<{ ok: boolean }>;
}
let native: Transport | undefined;
export async function initializePlatform() {
  if ('__TAURI_INTERNALS__' in window) native = await import('./native').then(module => module.nativeTransport);
}
export const transport: Transport = {
  socket: url => native ? native.socket(url) : new WebSocket(url),
  session: (url, init) => native ? native.session(url, init) : fetch(url, init),
};
