export interface PlacementRect { left: number; top: number; right: number; bottom: number }
export interface AssistantPoint { left: number; top: number }
export function assistantPlacement(preferred: AssistantPoint, size: { width: number; height: number }, viewport: { width: number; height: number }, obstacles: PlacementRect[]): AssistantPoint {
  const clamp = (point: AssistantPoint) => ({ left: Math.max(12, Math.min(viewport.width - size.width - 12, point.left)), top: Math.max(36, Math.min(viewport.height - size.height - 20, point.top)) });
  const origin = clamp(preferred);
  const overlap = (point: AssistantPoint) => obstacles.reduce((area, rect) => area + Math.max(0, Math.min(point.left + size.width + 8, rect.right) - Math.max(point.left - 8, rect.left)) * Math.max(0, Math.min(point.top + size.height + 8, rect.bottom) - Math.max(point.top - 16, rect.top)), 0);
  if (!overlap(origin)) return origin;
  let best = origin, bestCost = Infinity;
  const candidates = [origin];
  for (let y = 36; y <= viewport.height - size.height - 20; y += 32) for (let x = 12; x <= viewport.width - size.width - 12; x += 32) candidates.push({ left: x, top: y });
  for (const point of candidates) {
    const cost = overlap(point) * 10000 + (point.left - origin.left) ** 2 + (point.top - origin.top) ** 2;
    if (cost < bestCost) { best = point; bestCost = cost; }
  }
  return best;
}
