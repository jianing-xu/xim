import { afterEach, expect, it, vi } from 'vitest';
import { deadlineSignal } from './abort';
afterEach(() => vi.useRealTimers());
it('enforces the same request timeout without requiring AbortSignal.timeout', () => {
  vi.useFakeTimers();
  const signal = deadlineSignal(8000);
  vi.advanceTimersByTime(7999); expect(signal.aborted).toBe(false);
  vi.advanceTimersByTime(1); expect(signal.aborted).toBe(true);
  expect(signal.reason.name).toBe('TimeoutError');
});
it('propagates cancellation and cancels its deadline', () => {
  vi.useFakeTimers();
  const request = new AbortController(); const signal = deadlineSignal(8000, request.signal);
  request.abort(); expect(signal.aborted).toBe(true); expect(vi.getTimerCount()).toBe(0);
  expect(deadlineSignal(8000, request.signal).aborted).toBe(true);
});
