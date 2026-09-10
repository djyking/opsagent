import { runtimePath, cdnOrigin } from 'virtual:opsagent-graph-runtime-url';

type GraphRuntime = typeof import('./observability-graph-runtime');
type RuntimeImporter = (url: string) => Promise<GraphRuntime>;

/** One selected runtime per page, including prefetches and concurrent component mounts. */
export function createTopologyGraphLoader(options: {
  path: string;
  cdnOrigin?: string;
  pageOrigin: () => string;
  importer: RuntimeImporter;
  timeoutMs?: number;
  failureGraceMs?: number;
}) {
  let pending: Promise<GraphRuntime> | undefined;
  let originOnly = !options.cdnOrigin;
  async function checkedImport(url: string) {
    const runtime = await options.importer(url);
    if (typeof runtime?.Graph !== 'function') throw new Error('Graph runtime is unavailable');
    return runtime;
  }
  async function selectRuntime() {
    if (!originOnly) {
      let timer: ReturnType<typeof setTimeout> | undefined;
      let failureTimer: ReturnType<typeof setTimeout> | undefined;
      let settled = false;
      let failAfterGrace!: (cause: unknown) => void;
      const fallbackFailure = new Promise<never>((_, reject) => { failAfterGrace = reject; });
      const cdn = checkedImport(new URL(options.path, options.cdnOrigin).href)
        .then(runtime => ({ runtime, fromOrigin: false }));
      const fallback = new Promise<{ runtime: GraphRuntime; fromOrigin: boolean }>((resolve, reject) => {
        let started = false;
        const startOrigin = () => {
          if (started) return;
          started = true;
          checkedImport(new URL(options.path, options.pageOrigin()).href)
            .then(runtime => resolve({ runtime, fromOrigin: true }), cause => {
              reject(cause);
              // A failed fallback must not leave a stalled CDN import loading forever.
              if (!settled) failureTimer = setTimeout(() => failAfterGrace(cause), options.failureGraceMs ?? 3000);
            });
        };
        timer = setTimeout(startOrigin, options.timeoutMs ?? 2500);
        void cdn.catch(startOrigin);
      });
      try {
        // Starting a fallback does not discard a CDN download that is about to finish.
        // The first successful import wins; later imports cannot replace that runtime.
        const selected = await Promise.race([Promise.any([cdn, fallback]), fallbackFailure]);
        if (selected.fromOrigin) originOnly = true;
        return selected.runtime;
      } catch (cause) {
        originOnly = true;
        throw cause instanceof AggregateError ? cause.errors.at(-1) ?? cause : cause;
      } finally {
        settled = true;
        clearTimeout(timer);
        clearTimeout(failureTimer);
      }
    }
    return checkedImport(new URL(options.path, options.pageOrigin()).href);
  }
  return function loadTopologyGraphRuntime() {
    if (!pending) pending = selectRuntime().catch(cause => { pending = undefined; throw cause; });
    return pending;
  };
}

export const loadTopologyGraphRuntime = createTopologyGraphLoader({
  path: runtimePath,
  cdnOrigin,
  pageOrigin: () => globalThis.location.origin,
  importer: url => import(/* @vite-ignore */ url),
});
