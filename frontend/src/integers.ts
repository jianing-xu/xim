/** Decimal strings are canonical for cursors. Safe numeric legacy cache/test values are accepted. */
export type Int64 = string | number;
export const MAX_INT64 = '9223372036854775807';
export function integer(value: Int64 | undefined | null): bigint {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('Unsafe integer');
  if (typeof value === 'string' && !/^-?\d+$/.test(value)) throw new Error('Invalid integer');
  const result = BigInt(value ?? 0);
  if (result < -9223372036854775808n || result > 9223372036854775807n) throw new Error('Integer out of range');
  return result;
}
export function compareInt(a?: Int64 | null, b?: Int64 | null) { const x = integer(a), y = integer(b); return x < y ? -1 : x > y ? 1 : 0; }
export function maxInt(...values: (Int64 | null | undefined)[]): string { return values.reduce<bigint>((max, value) => { const n = integer(value); return n > max ? n : max; }, 0n).toString(); }
export function positive(value?: Int64 | null) { return integer(value) > 0n; }
export function count(value?: Int64, increment = 0): Int64 {
  const n = integer(value) + BigInt(increment);
  return n <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(n) : n.toString();
}
export function safeNumber(value: Int64): number { const n = integer(value); if (n < BigInt(Number.MIN_SAFE_INTEGER) || n > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Unsafe numeric display value'); return Number(n); }
