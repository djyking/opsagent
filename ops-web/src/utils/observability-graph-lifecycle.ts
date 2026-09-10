import type { Graph } from './observability-graph-runtime';

export function destroyTopologyGraph(graph: Graph | undefined, hasMinimap: boolean) {
  if (!graph || graph.destroyed) return;
  if (hasMinimap && graph.rendered) {
    // G6 5.0.49 removes minimap listeners on destroy but leaves its 128 ms
    // debounced draw queued. That debounce has no cancel API and calls these
    // methods after the graph model has been destroyed. Neutralize only this
    // disposed plugin's pending draws, then let G6 clean up its canvas normally.
    const minimap = graph.getPluginInstance('minimap') as unknown as {
      renderMinimap: () => void;
      renderMask: () => void;
      onTransform?: { cancel?: () => void };
    } | undefined;
    if (minimap) {
      minimap.renderMinimap = () => {};
      minimap.renderMask = () => {};
      minimap.onTransform?.cancel?.();
    }
  }
  graph.destroy();
}
