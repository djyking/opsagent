// G6 5.0.49's main entry eagerly registers every layout, shape and plugin.
// Use its ESM exports without preset registration; this graph positions nodes
// itself and needs only HTML cards, routed edges, canvas navigation and minimap.
import { Graph, HTML, Polyline, DragCanvas, ZoomCanvas, Minimap, register } from '@antv/g6/esm/exports.js';
import { ArrangeDrawOrder, CollapseExpandCombo, CollapseExpandNode, GetEdgeActualEnds, UpdateRelatedEdge } from '@antv/g6/esm/transforms/index.js';
import { light } from '@antv/g6/esm/themes/light.js';

register('node', 'html', HTML);
register('edge', 'polyline', Polyline);
register('behavior', 'drag-canvas', DragCanvas);
register('behavior', 'zoom-canvas', ZoomCanvas);
register('plugin', 'minimap', Minimap);
register('theme', 'light', light);

// G6's TransformController always instantiates these, even with layout disabled.
register('transform', 'update-related-edges', UpdateRelatedEdge);
register('transform', 'collapse-expand-node', CollapseExpandNode);
register('transform', 'collapse-expand-combo', CollapseExpandCombo);
register('transform', 'get-edge-actual-ends', GetEdgeActualEnds);
register('transform', 'arrange-draw-order', ArrangeDrawOrder);

export { Graph };
