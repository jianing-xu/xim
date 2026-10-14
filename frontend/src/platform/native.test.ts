import { afterEach, beforeEach, expect, it, vi } from 'vitest';
const mocks = vi.hoisted(() => ({ invoke: vi.fn(), listen: vi.fn(), unlisten: vi.fn() }));
vi.mock('@tauri-apps/api/core', () => ({ invoke: mocks.invoke }));
vi.mock('@tauri-apps/api/event', () => ({ listen: mocks.listen }));
import { nativeTransport } from './native';
let incoming: (event: { payload: { id: string; kind: string; data?: number[] } }) => void;
beforeEach(() => {
  vi.clearAllMocks();
  mocks.listen.mockImplementation(async (_name, callback) => { incoming = callback; return mocks.unlisten; });
  mocks.invoke.mockResolvedValue({ ok: true });
});
afterEach(() => vi.restoreAllMocks());
async function open() {
  const socket = nativeTransport.socket('ws://ignored.example/ws');
  await vi.waitFor(() => expect(mocks.invoke).toHaveBeenCalledWith('socket_open', expect.anything()));
  const id = mocks.invoke.mock.calls.find(([command]) => command === 'socket_open')![1].id;
  incoming({ payload: { id, kind: 'open' } });
  return { socket, id };
}
it('adapts native binary frames, filters other sockets, and orders outbound frames', async () => {
  const { socket, id } = await open();
  const received = vi.fn(); socket.addEventListener('message', received);
  incoming({ payload: { id: 'another', kind: 'message', data: [9] } });
  incoming({ payload: { id, kind: 'message', data: [0, 255, 1] } });
  expect(received).toHaveBeenCalledTimes(1);
  expect([...new Uint8Array(received.mock.calls[0][0].data)]).toEqual([0, 255, 1]);
  let release!: () => void;
  mocks.invoke.mockImplementation(command => command === 'socket_send' ? new Promise<void>(resolve => { release = resolve; }) : Promise.resolve());
  socket.send(new Uint8Array([1]).buffer); socket.send(new Uint8Array([2]).buffer);
  await vi.waitFor(() => expect(mocks.invoke.mock.calls.filter(([c]) => c === 'socket_send')).toHaveLength(1));
  release();
  await vi.waitFor(() => expect(mocks.invoke.mock.calls.filter(([c]) => c === 'socket_send')).toHaveLength(2));
  release(); socket.close();
  expect(socket.readyState).toBe(3); expect(mocks.unlisten).toHaveBeenCalledTimes(1);
});
it('closing before the native listener is ready does not open a leaked socket', async () => {
  let release!: (value: typeof mocks.unlisten) => void;
  mocks.listen.mockReturnValue(new Promise(resolve => { release = resolve; }));
  const socket = nativeTransport.socket('ws://ignored/ws'); socket.close(); release(mocks.unlisten);
  await vi.waitFor(() => expect(mocks.unlisten).toHaveBeenCalledTimes(1));
  expect(mocks.invoke.mock.calls.some(([command]) => command === 'socket_open')).toBe(false);
});
it('serializes credential exchange and logout without exposing or forwarding service URLs', async () => {
  let release!: (value: { ok: boolean }) => void;
  mocks.invoke.mockImplementation((_command, args) => args.method === 'POST' ? new Promise(resolve => { release = resolve; }) : Promise.resolve({ ok: true }));
  const saved = nativeTransport.session('https://untrusted.example/api/session', { method: 'POST', body: JSON.stringify({ ticket: 'one-use' }) });
  const deleted = nativeTransport.session('https://untrusted.example/api/session', { method: 'DELETE' });
  await vi.waitFor(() => expect(mocks.invoke).toHaveBeenCalledTimes(1));
  release({ ok: true }); await saved; await deleted;
  expect(mocks.invoke.mock.calls).toEqual([
    ['session_exchange', { method: 'POST', ticket: 'one-use' }], ['session_exchange', { method: 'DELETE', ticket: undefined }],
  ]);
});
