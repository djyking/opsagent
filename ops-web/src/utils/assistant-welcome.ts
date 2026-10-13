// A greeting belongs to one page visit and login identity. Keeping this in memory
// survives orb remounts (chat, approval inbox, mobile navigation), while a fresh
// page load or login can welcome a returning visitor without a 24-hour cooldown.
const welcomedSessions = new Set<string>();

export function claimAssistantWelcome(actor: number | undefined, identity?: string | null): boolean {
  if (!actor) return false;
  const key = JSON.stringify([identity || null, actor]);
  if (welcomedSessions.has(key)) return false;
  welcomedSessions.add(key);
  return true;
}
