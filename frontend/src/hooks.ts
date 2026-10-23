import { useCallback, useEffect, useRef, useState } from 'react';

/** Loads data and refreshes it on an interval while the page is visible. */
export function usePolling<T>(load: () => Promise<T>, deps: unknown[], intervalMs = 2000) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const loadRef = useRef(load);
  loadRef.current = load;

  const reload = useCallback(async () => {
    try { setData(await loadRef.current()); setError(null); }
    catch (e) { setError(e as Error); }
  }, []);

  useEffect(() => {
    setData(null);
    void reload();
    if (intervalMs <= 0) return;
    const timer = setInterval(() => { if (document.visibilityState !== 'hidden') void reload(); }, intervalMs);
    return () => clearInterval(timer);
  }, deps);

  return { data, error, reload };
}

/** Runs a mutating action, tracking its pending state and last error. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const run = useCallback(async <T,>(action: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true); setError(null);
    try { return await action(); }
    catch (e) { setError(e as Error); return undefined; }
    finally { setBusy(false); }
  }, []);
  return { busy, error, run, clear: () => setError(null) };
}
