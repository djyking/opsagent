export interface RagRouteQuery {
  new?: unknown;
  draft?: unknown;
  conversation?: unknown;
}

export type RagNavigation = { kind: 'new'; draft: string } | { kind: 'conversation'; id: string };

export function ragNavigation(query: RagRouteQuery): RagNavigation {
  if (query.new === '1') {
    return { kind: 'new', draft: typeof query.draft === 'string' ? query.draft.slice(0, 2000) : '' };
  }
  return typeof query.conversation === 'string' && query.conversation
    ? { kind: 'conversation', id: query.conversation }
    : { kind: 'new', draft: '' };
}
