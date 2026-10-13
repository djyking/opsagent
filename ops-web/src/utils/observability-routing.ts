export type RoutePoint = [number, number];
export interface RouteObstacle { left: number; top: number; right: number; bottom: number }
export interface RouteRequest { id: string; source: RoutePoint; target: RoutePoint; sourceSide: 'left' | 'right'; targetSide: 'left' | 'right' }
type Segment = { from: RoutePoint; to: RoutePoint; horizontal: boolean };
const CLEARANCE = 10;
const TRACK_GAP = 10;

class Queue {
  private items: { state: number; cost: number; estimate: number }[] = [];
  push(item: { state: number; cost: number; estimate: number }) {
    this.items.push(item);
    let index = this.items.length - 1;
    while (index > 0) {
      const parent = (index - 1) >> 1;
      if (this.items[parent]!.estimate <= item.estimate) break;
      this.items[index] = this.items[parent]!; index = parent;
    }
    this.items[index] = item;
  }
  pop() {
    const first = this.items[0], last = this.items.pop();
    if (!this.items.length || !last) return first;
    let index = 0;
    while (index * 2 + 1 < this.items.length) {
      let child = index * 2 + 1;
      if (child + 1 < this.items.length && this.items[child + 1]!.estimate < this.items[child]!.estimate) child++;
      if (this.items[child]!.estimate >= last.estimate) break;
      this.items[index] = this.items[child]!; index = child;
    }
    this.items[index] = last;
    return first;
  }
}
function simplify(points: RoutePoint[]) {
  const result: RoutePoint[] = [];
  for (const point of points) {
    if (result.length && result.at(-1)![0] === point[0] && result.at(-1)![1] === point[1]) continue;
    while (result.length >= 2) {
      const a = result.at(-2)!, b = result.at(-1)!;
      if ((a[0] === b[0] && b[0] === point[0]) || (a[1] === b[1] && b[1] === point[1])) result.pop();
      else break;
    }
    result.push(point);
  }
  return result;
}
function segments(points: RoutePoint[]): Segment[] {
  return points.slice(1).map((point, index) => ({ from: points[index]!, to: point, horizontal: points[index]![1] === point[1] }));
}
function congestion(from: RoutePoint, to: RoutePoint, occupied: Segment[]) {
  const horizontal = from[1] === to[1], along = horizontal ? 0 : 1, across = horizontal ? 1 : 0;
  const low = Math.min(from[along], to[along]), high = Math.max(from[along], to[along]);
  let penalty = 0;
  for (const segment of occupied) {
    if (segment.horizontal === horizontal) {
      const distance = Math.abs(from[across] - segment.from[across]);
      if (distance >= TRACK_GAP) continue;
      const overlap = Math.min(high, Math.max(segment.from[along], segment.to[along])) - Math.max(low, Math.min(segment.from[along], segment.to[along]));
      if (overlap > 0) penalty += overlap * 35 * (1 - distance / TRACK_GAP);
    } else if (segment.from[along] > low && segment.from[along] < high
      && from[across] > Math.min(segment.from[across], segment.to[across]) && from[across] < Math.max(segment.from[across], segment.to[across])) penalty += 70;
  }
  return penalty;
}

/** Route the complete graph together. Node rectangles are hard obstacles; occupied tracks are soft costs. */
export function separateTopologyRoutes(requests: RouteRequest[], obstacles: RouteObstacle[]): Record<string, RoutePoint[] | undefined> {
  const xs = new Set<number>(), ys = new Set<number>();
  const rectangles = obstacles.map(box => ({ left: box.left - CLEARANCE, right: box.right + CLEARANCE, top: box.top - CLEARANCE, bottom: box.bottom + CLEARANCE }));
  for (const box of rectangles) {
    xs.add((box.left + box.right) / 2); ys.add((box.top + box.bottom) / 2);
    for (const offset of [0, 12, 24, 36]) {
      xs.add(box.left - offset); xs.add(box.right + offset);
      ys.add(box.top - offset); ys.add(box.bottom + offset);
    }
  }
  const endpoints = new Map(requests.map(request => {
    const source: RoutePoint = [request.source[0] + (request.sourceSide === 'left' ? -12 : 12), request.source[1]];
    const target: RoutePoint = [request.target[0] + (request.targetSide === 'left' ? -12 : 12), request.target[1]];
    xs.add(source[0]); xs.add(target[0]); ys.add(source[1]); ys.add(target[1]);
    return [request.id, { source, target }];
  }));
  const x = [...xs].sort((a, b) => a - b), y = [...ys].sort((a, b) => a - b), width = x.length;
  const xIndex = new Map(x.map((value, index) => [value, index])), yIndex = new Map(y.map((value, index) => [value, index]));
  const size = width * y.length, result: Record<string, RoutePoint[] | undefined> = {};
  // Extremely large imported layouts retain the existing bounded G6 fallback.
  if (!size || size > 180_000) return result;
  const blocked = new Uint8Array(size);
  for (let row = 0; row < y.length; row++) for (let column = 0; column < width; column++) {
    if (rectangles.some(box => x[column]! > box.left + .01 && x[column]! < box.right - .01 && y[row]! > box.top + .01 && y[row]! < box.bottom - .01)) blocked[row * width + column] = 1;
  }
  const point = (cell: number): RoutePoint => [x[cell % width]!, y[Math.floor(cell / width)]!];
  const occupied: Segment[] = [];
  const length = (request: RouteRequest) => Math.abs(request.target[0] - request.source[0]) + Math.abs(request.target[1] - request.source[1]);
  const ordered = [...requests].sort((a, b) => length(b) - length(a) || a.id.localeCompare(b.id, 'en'));
  for (const request of ordered) {
    const { source, target } = endpoints.get(request.id)!;
    const start = yIndex.get(source[1])! * width + xIndex.get(source[0])!, end = yIndex.get(target[1])! * width + xIndex.get(target[0])!;
    if (blocked[start] || blocked[end]) continue;
    const costs = new Float64Array(size * 2).fill(Infinity), previous = new Int32Array(size * 2).fill(-1);
    const queue = new Queue();
    costs[start * 2] = 0;
    queue.push({ state: start * 2, cost: 0, estimate: length(request) });
    let finish = -1, remaining = Math.min(size * 3, 160_000);
    while (remaining-- > 0) {
      const current = queue.pop(); if (!current) break;
      if (current.cost !== costs[current.state]) continue;
      const cell = current.state >> 1, axis = current.state % 2;
      if (cell === end) { finish = current.state; break; }
      const column = cell % width, row = Math.floor(cell / width), from = point(cell);
      const neighbours = [column > 0 ? cell - 1 : -1, column < width - 1 ? cell + 1 : -1, row > 0 ? cell - width : -1, row < y.length - 1 ? cell + width : -1];
      for (let direction = 0; direction < neighbours.length; direction++) {
        const next = neighbours[direction]!; if (next < 0 || blocked[next]) continue;
        const nextAxis = direction < 2 ? 0 : 1, state = next * 2 + nextAxis, to = point(next);
        const cost = current.cost + Math.abs(from[0] - to[0]) + Math.abs(from[1] - to[1]) + (axis === nextAxis ? 0 : 18) + congestion(from, to, occupied);
        if (cost >= costs[state]!) continue;
        costs[state] = cost; previous[state] = current.state;
        queue.push({ state, cost, estimate: cost + Math.abs(to[0] - target[0]) + Math.abs(to[1] - target[1]) });
      }
    }
    if (finish < 0) continue;
    const controls: RoutePoint[] = [];
    for (let state = finish; state >= 0; state = previous[state]!) controls.push(point(state >> 1));
    const path = simplify([request.source, ...controls.reverse(), request.target]);
    result[request.id] = path.slice(1, -1);
    occupied.push(...segments(path));
  }
  return result;
}
