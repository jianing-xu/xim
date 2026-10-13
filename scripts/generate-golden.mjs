// Explicit fixture regeneration; normal builds do not rewrite checked-in protocol evidence.
import { xim } from '../frontend/src/generated/xim.js';
import { writeFile } from 'node:fs/promises';
const Envelope = xim.v2.Envelope;
const cases = [
  ['loginRequest', { username: '用户', password: 'test-only', requestId: '登录编号' }],
  ['messageRequest', { toUserId: 'peer', message: '中文 🌏\n<script>', requestId: 'client-1' }],
  ['historyRequest', { kind: 'user', id: 'peer', after: '9007199254740993', limit: 50, requestId: 'page' }],
  ['readRequest', { kind: 'group', id: 'group', sequence: '9223372036854775807', requestId: 'read' }],
  ['historyResponse', { kind: 'user', id: 'peer', messages: [{ id: 'server-id', sequence: '9223372036854775807', fromUserId: 'peer', fromUsername: '用户', message: '中文 📨', time: '1700000000000', clientMessageId: 'client-1' }], nextAfter: '9223372036854775807', hasMore: false, syncCursor: '9223372036854775807', requestId: 'page' }],
  ['conversationsResponse', { conversations: [{ kind: 'user', id: 'peer', name: '用户', lastSequence: '9007199254740995', readSequence: '9007199254740993', unread: '2' }], syncCursor: '9223372036854775807' }],
  ['contactsResponse', { requestId: 'directory', users: [{ userId: 'peer', username: 'peer_user', displayName: '离线用户', online: false }], hasMore: true, nextAfter: 'peer' }],
];
const fixtures = cases.map(([key, body]) => {
  const envelope = Envelope.fromObject({ version: 2, [key]: body });
  const bytes = Envelope.encode(envelope).finish();
  const value = Envelope.toObject(Envelope.decode(bytes), { longs: String, arrays: true });
  return { request: key.endsWith('Request'), event: { type: key.replace(/(?:Request|Response)$/, ''), ...value[key] }, base64: Buffer.from(bytes).toString('base64') };
});
await writeFile(new URL('../protocol/golden-vectors.json', import.meta.url), JSON.stringify(fixtures, null, 2) + '\n');
