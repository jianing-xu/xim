/** Older system WebViews lack AbortSignal.any/timeout; preserve the same request deadlines. */
export function deadlineSignal(milliseconds: number, signal?: AbortSignal): AbortSignal {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(new DOMException('Request timed out', 'TimeoutError')), milliseconds);
  const abort = () => controller.abort(signal?.reason);
  controller.signal.addEventListener('abort', () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); }, { once: true });
  if (signal?.aborted) abort();
  else signal?.addEventListener('abort', abort, { once: true });
  return controller.signal;
}
