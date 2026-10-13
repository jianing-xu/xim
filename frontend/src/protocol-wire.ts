import { xim } from './generated/xim';
import type { ClientEvent, ServerEvent } from './protocol';
import { integer, count, safeNumber } from './integers';
export const WIRE_VERSION = 2;
export const MAX_FRAME_BYTES = 64 * 1024;
const Envelope = xim.v2.Envelope;
const sequences = new Set(['sequence', 'lastSequence', 'readSequence', 'syncCursor', 'summaryCursor']);
const times = new Set(['time', 'lastTime', 'expiresAt']);

function normalize(value: any, type: string, encoding = false): any {
  if (Array.isArray(value)) return value.map(item => normalize(item, type, encoding));
  if (value === null || value === undefined || typeof value !== 'object') return value;
  const result: Record<string, unknown> = {};
  for (const [key, item] of Object.entries(value)) {
    if (item === null || item === undefined || key.startsWith('_')) continue;
    if (sequences.has(key) || type === 'history' && ['before','after','nextBefore','nextAfter'].includes(key)) result[key] = integer(item as string | number).toString();
    else if (times.has(key)) result[key] = encoding ? integer(item as string | number).toString() : safeNumber(item as string | number);
    else if (key === 'unread') result[key] = encoding ? integer(item as string | number).toString() : count(item as string | number);
    else result[key] = normalize(item, type, encoding);
  }
  return result;
}
function encode(event: { type: string }, request: boolean): Uint8Array {
  const { type, ...fields } = event;
  const key = type + (request ? 'Request' : 'Response');
  const input = { version: WIRE_VERSION, [key]: normalize(fields, type, true) };
  const envelope = Envelope.fromObject(input);
  const object = Envelope.toObject(envelope);
  if (!(key in object)) throw new Error('Unknown Protobuf operation');
  const bytes = Envelope.encode(envelope).finish();
  if (bytes.length > MAX_FRAME_BYTES) throw new Error('Protobuf frame too large');
  return bytes;
}
export function encodeClientEvent(event: ClientEvent) { return encode(event, true); }
export function encodeServerEvent(event: ServerEvent) { return encode(event, false); }
export function decodeWire(bytes: Uint8Array, request: boolean): Record<string, any> {
  if (!bytes.length || bytes.length > MAX_FRAME_BYTES) throw new Error('Invalid Protobuf frame size');
  const value = Envelope.toObject(Envelope.decode(bytes), { longs: String, arrays: true });
  if (value.version !== WIRE_VERSION) throw new Error('Unsupported protocol version');
  const payloads = Object.keys(value).filter(key => key.endsWith('Request') || key.endsWith('Response'));
  if (payloads.length !== 1 || !payloads[0].endsWith(request ? 'Request' : 'Response')) throw new Error('Invalid Protobuf payload or direction');
  const key = payloads[0], type = key.replace(/(?:Request|Response)$/, '');
  return { type, ...normalize(value[key], type) };
}
export function decodeClientEvent(bytes: Uint8Array) { return decodeWire(bytes, true); }
export function decodeServerEvent(bytes: Uint8Array): ServerEvent { return decodeWire(bytes, false) as ServerEvent; }
export function normalizeCache<T>(value: T): T { return normalize(value, 'cache') as T; }
