import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript');
const module = { exports: {} };
const source = readFileSync(new URL('../src/utils/observability-layout.ts', import.meta.url), 'utf8');
const routing = { exports: {} };
new Function('module', 'exports', ts.transpileModule(readFileSync(new URL('../src/utils/observability-routing.ts', import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(routing, routing.exports);
new Function('require', 'module', 'exports', ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText)(id => id === './observability-routing' ? routing.exports : require(id), module, module.exports);
const layout = module.exports;
const { aStarSearch } = require('./node_modules/@antv/g6/lib/utils/router/shortest-path.js');
const { getPolylinePath } = require('./node_modules/@antv/g6/lib/utils/edge.js');
const node = (ciCode, ciType = 'SERVICE') => ({ ciCode, ciName: ciCode, ciType });
const edge = (sourceCiCode, targetCiCode, id) => ({ sourceCiCode, targetCiCode, id, relationType: 'DEPENDS_ON' });

test('dependency ordering untangles an inverted pair and is independent of API order', () => {
  const nodes = [node('service-a'), node('service-b'), node('db-a', 'DATABASE'), node('db-b', 'DATABASE')];
  const edges = [edge('service-a', 'db-b', 1), edge('service-b', 'db-a', 2)];
  const positions = layout.initialTopologyPositions(nodes, false, edges);
  assert((positions['service-a'].y - positions['service-b'].y) * (positions['db-b'].y - positions['db-a'].y) > 0);
  assert.deepEqual(layout.initialTopologyPositions([...nodes].reverse(), false, [...edges].reverse()), positions);
  assert(positions['service-a'].x < positions['db-a'].x);
});

test('multi-column default leaves corridors while saved, dragged and filtered positions stay fixed', () => {
  const nodes = [node('gateway', 'GATEWAY'), ...Array.from({ length: 8 }, (_, i) => node(`service-${i}`)), ...Array.from({ length: 6 }, (_, i) => node(`db-${i}`, 'DATABASE'))];
  const positions = layout.initialTopologyPositions(nodes);
  for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
    const a = positions[nodes[i].ciCode], b = positions[nodes[j].ciCode];
    assert(Math.abs(a.x - b.x) >= layout.TOPOLOGY_NODE_WIDTH + 40 || Math.abs(a.y - b.y) >= layout.TOPOLOGY_NODE_HEIGHT + 40);
  }
  const saved = { ...positions, 'service-1': { x: 385, y: 615 } };
  assert.deepEqual(layout.reconcileTopologyPositions(nodes, [], saved), saved);
  assert.deepEqual(layout.reconcileTopologyPositions([nodes[0]], [], {}, saved), saved);
  const added = layout.reconcileTopologyPositions([...nodes, node('new-service')], [], saved);
  for (const item of nodes) assert.deepEqual(added[item.ciCode], saved[item.ciCode]);
});

test('shared endpoints have separate ports and backward/same-column edges keep their real direction', () => {
  const nodes = [node('a'), node('b'), node('c')];
  const positions = { a: { x: 100, y: 100 }, b: { x: 400, y: 100 }, c: { x: 400, y: 250 } };
  const edges = [edge('a', 'b', 1), edge('a', 'c', 2), edge('b', 'a', 3), edge('b', 'c', 4)];
  const { ports, connections } = layout.topologyConnections(nodes, edges, positions);
  assert.equal(connections.length, edges.length);
  assert.equal(new Set(ports.a.map(port => port.placement.join(','))).size, ports.a.length);
  assert.deepEqual(connections[2].router.startDirections, ['left']);
  assert.deepEqual(connections[2].router.endDirections, ['right']);
  assert.deepEqual(connections[3].router.startDirections, ['right']);
  assert.deepEqual(connections[3].router.endDirections, ['right']);
  for (const connection of connections) {
    assert(ports[connection.edge.sourceCiCode].some(port => port.key === connection.sourcePort));
    assert(ports[connection.edge.targetCiCode].some(port => port.key === connection.targetPort));
  }
});

function shape(id, position) {
  const bbox = { min: [position.x - 68, position.y - 39, 0], max: [position.x + 68, position.y + 39, 0] };
  return { id, getCenter: () => [position.x, position.y], getRenderBounds: () => bbox, isVisible: () => true };
}
function segmentHitsCard(a, b, rectangle) {
  const box = rectangle.getRenderBounds();
  if (a[1] === b[1]) return a[1] > box.min[1] && a[1] < box.max[1] && Math.min(a[0], b[0]) < box.max[0] && Math.max(a[0], b[0]) > box.min[0];
  if (a[0] === b[0]) return a[0] > box.min[0] && a[0] < box.max[0] && Math.min(a[1], b[1]) < box.max[1] && Math.max(a[1], b[1]) > box.min[1];
  assert.fail('middle routing segments must remain horizontal or vertical');
}
test('actual G6 router avoids intervening cards for long, backward and same-column connections', () => {
  const nodes = [node('source'), node('obstacle'), node('target'), node('lower')];
  const positions = { source: { x: 100, y: 100 }, obstacle: { x: 320, y: 100 }, target: { x: 540, y: 100 }, lower: { x: 540, y: 300 } };
  const shapes = nodes.map(item => shape(item.ciCode, positions[item.ciCode]));
  const edges = [edge('source', 'target', 1), edge('target', 'source', 2), edge('target', 'lower', 3)];
  for (const connection of layout.topologyConnections(nodes, edges, positions).connections) {
    const points = aStarSearch(shapes.find(item => item.id === connection.edge.sourceCiCode), shapes.find(item => item.id === connection.edge.targetCiCode), shapes, connection.router);
    assert(points.length >= 2, 'routing must succeed without the non-obstacle-aware fallback');
    for (let i = 1; i < points.length; i++) for (const rectangle of shapes) assert(!segmentHitsCard(points[i - 1], points[i], rectangle), `route must avoid ${rectangle.id}`);
  }
});

function portPoint(ports, positions, code, key) {
  const placement = ports[code].find(port => port.key === key).placement;
  return [positions[code].x + (placement[0] - .5) * 136, positions[code].y + (placement[1] - .5) * 78];
}
function sharedLength(paths) {
  let total = 0;
  for (let i = 0; i < paths.length; i++) for (let j = i + 1; j < paths.length; j++) {
    for (let a = 1; a < paths[i].length; a++) for (let b = 1; b < paths[j].length; b++) {
      const [one, two, three, four] = [paths[i][a - 1], paths[i][a], paths[j][b - 1], paths[j][b]];
      for (const axis of [0, 1]) if (Math.abs(one[axis] - two[axis]) < .01 && Math.abs(three[axis] - four[axis]) < .01 && Math.abs(one[axis] - three[axis]) < .01) {
        const other = 1 - axis;
        total += Math.max(0, Math.min(Math.max(one[other], two[other]), Math.max(three[other], four[other])) - Math.max(Math.min(one[other], two[other]), Math.min(three[other], four[other])));
      }
    }
  }
  return total;
}
test('fan-out routes separate occupied tracks and their rendered rounded corners avoid cards', () => {
  const nodes = [node('source'), node('obstacle'), ...Array.from({ length: 5 }, (_, i) => node(`target-${i}`))];
  const positions = { source: { x: 100, y: 320 }, obstacle: { x: 310, y: 320 }, ...Object.fromEntries(Array.from({ length: 5 }, (_, i) => [`target-${i}`, { x: 560, y: 20 + i * 150 }])) };
  const edges = Array.from({ length: 5 }, (_, i) => edge('source', `target-${i}`, i + 1));
  const { connections, ports } = layout.topologyConnections(nodes, edges, positions);
  const shapes = nodes.map(node => shape(node.ciCode, positions[node.ciCode]));
  const paths = connections.map(connection => {
    assert.ok(connection.controlPoints, 'Separated routing should not need the legacy fallback.');
    return [portPoint(ports, positions, connection.edge.sourceCiCode, connection.sourcePort), ...connection.controlPoints, portPoint(ports, positions, connection.edge.targetCiCode, connection.targetPort)];
  });
  const independent = connections.map(connection => [portPoint(ports, positions, connection.edge.sourceCiCode, connection.sourcePort), ...aStarSearch(shapes[0], shapes.find(shape => shape.id === connection.edge.targetCiCode), shapes, connection.router), portPoint(ports, positions, connection.edge.targetCiCode, connection.targetPort)]);
  assert(sharedLength(paths) < sharedLength(independent) * .2, 'Shared-track length must fall by at least 80%.');
  for (const path of paths) {
    let current;
    for (const command of getPolylinePath(path, 24)) {
      if (command[0] === 'M') { current = command.slice(1); continue; }
      const end = command.slice(-2), length = Math.hypot(end[0] - current[0], end[1] - current[1]);
      for (let step = 1; step <= Math.max(24, Math.ceil(length / 3)); step++) {
        const t = step / Math.max(24, Math.ceil(length / 3));
        const sample = command[0] === 'Q' ? [0, 1].map(axis => (1 - t) ** 2 * current[axis] + 2 * (1 - t) * t * command[axis + 1] + t * t * end[axis]) : [0, 1].map(axis => current[axis] + t * (end[axis] - current[axis]));
        for (const shape of shapes) { const b = shape.getRenderBounds(); assert(!(sample[0] > b.min[0] + .1 && sample[0] < b.max[0] - .1 && sample[1] > b.min[1] + .1 && sample[1] < b.max[1] - .1), `Rounded path must avoid ${shape.id}`); }
      }
      current = end;
    }
  }
  const reordered = layout.topologyConnections([...nodes].reverse(), [...edges].reverse(), positions);
  assert.deepEqual(Object.fromEntries(reordered.connections.map(connection => [connection.id, connection.controlPoints])), Object.fromEntries(connections.map(connection => [connection.id, connection.controlPoints])));
});
