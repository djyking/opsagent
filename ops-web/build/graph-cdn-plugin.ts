import { resolve } from 'node:path';
import type { Plugin } from 'vite';

export const GRAPH_CDN_ORIGIN = 'https://www.opsagent.cloud';
const virtualId = 'virtual:opsagent-graph-runtime-url';
const resolvedVirtualId = `\0${virtualId}`;

/** Emit only the graph engine as a CDN entry; application/Vue modules stay on the page origin. */
export function graphCdnPlugin(origin = ''): Plugin {
  if (origin && origin !== GRAPH_CDN_ORIGIN) throw new Error('OPSAGENT_GRAPH_CDN_ORIGIN must be empty or https://www.opsagent.cloud');
  let building = false;
  let runtimeId = '';
  let referenceId = '';
  return {
    name: 'opsagent-graph-cdn',
    configResolved(config) {
      building = config.command === 'build';
      runtimeId = resolve(config.root, 'src/utils/observability-graph-runtime.ts').replaceAll('\\', '/');
    },
    buildStart() {
      if (building) referenceId = this.emitFile({ type: 'chunk', id: runtimeId, name: 'opsagent-graph-runtime', preserveSignature: 'strict' });
    },
    resolveId(id) { if (id === virtualId) return resolvedVirtualId; },
    load(id) {
      if (id !== resolvedVirtualId) return;
      const path = building ? `import.meta.ROLLUP_FILE_URL_${referenceId}` : JSON.stringify('/src/utils/observability-graph-runtime.ts');
      return `export const runtimePath = ${path}; export const cdnOrigin = ${JSON.stringify(building ? origin : '')};`;
    },
    resolveFileUrl({ referenceId: id, fileName }) {
      if (id === referenceId) return JSON.stringify(`/${fileName}`);
    },
    generateBundle(_, bundle) {
      if (!building) return;
      const pending = [this.getFileName(referenceId)];
      const visited = new Set<string>();
      while (pending.length) {
        const fileName = pending.pop()!;
        if (visited.has(fileName)) continue;
        visited.add(fileName);
        const chunk = bundle[fileName];
        if (!chunk || chunk.type !== 'chunk') this.error(`Missing graph runtime dependency: ${fileName}`);
        for (const rawId of Object.keys(chunk.modules)) {
          const id = rawId.replaceAll('\\', '/').split('?')[0];
          const applicationModule = id.includes('/src/') && !id.includes('/node_modules/') && id !== runtimeId;
          const appFramework = /\/node_modules\/(?:\.pnpm\/)?(?:@vue[+/]|vue(?:@|\/)|vue-router(?:@|\/)|pinia(?:@|\/))/.test(id);
          if (applicationModule || appFramework) this.error(`Graph CDN entry must not import application/Vue modules: ${id}`);
        }
        pending.push(...chunk.imports, ...chunk.dynamicImports);
      }
    },
  };
}
