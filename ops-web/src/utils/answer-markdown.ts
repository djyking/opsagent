import { h, type VNodeChild } from 'vue';

// Render supported Markdown as Vue nodes; model output is never interpreted as HTML.
function inline(text: string): VNodeChild[] {
  const nodes: VNodeChild[] = [];
  const pattern = /(`[^`\n]+`|\*\*[^*\n]+\*\*|\[[^\]\n]+\]\(https?:\/\/[^\s)]+\))/g;
  let start = 0;
  for (const match of text.matchAll(pattern)) {
    const index = match.index!;
    if (index > start) nodes.push(text.slice(start, index));
    const value = match[0];
    if (value.startsWith('`')) nodes.push(h('code', value.slice(1, -1)));
    else if (value.startsWith('**')) nodes.push(h('strong', value.slice(2, -2)));
    else {
      const link = /^\[([^\]]+)\]\((.+)\)$/.exec(value)!;
      nodes.push(h('a', { href: link[2], target: '_blank', rel: 'noopener noreferrer' }, link[1]));
    }
    start = index + value.length;
  }
  if (start < text.length) nodes.push(text.slice(start));
  return nodes;
}

function cells(line: string) {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map(cell => cell.trim().replace(/\\\|/g, '|'));
}
export function renderAnswer(content: string) {
  const lines = content.replace(/\r\n/g, '\n').replace(/\[chunk:\d+\]/g, '').split('\n');
  const nodes: VNodeChild[] = [];
  let i = 0;
  const structural = (line: string) => /^(?:#{1,6}\s|[-*+]\s|\d+[.)]\s|```|~~~|(?:---+|\*\*\*+)\s*$)/.test(line.trim());
  while (i < lines.length) {
    const line = lines[i]!.trim();
    if (!line) { i++; continue; }
    if (/^```|^~~~/.test(line)) {
      const marker = line.slice(0, 3);
      const code: string[] = [];
      i++;
      while (i < lines.length && !lines[i]!.trim().startsWith(marker)) code.push(lines[i++]!);
      if (i < lines.length) i++;
      nodes.push(h('pre', [h('code', code.join('\n'))]));
    } else if (/^#{1,6}\s+/.test(line)) {
      nodes.push(h('h4', inline(line.replace(/^#{1,6}\s+/, '')))); i++;
    } else if (/^(?:---+|\*\*\*+)\s*$/.test(line)) {
      nodes.push(h('hr')); i++;
    } else if (line.includes('|') && i + 1 < lines.length && cells(lines[i + 1]!).every(cell => /^:?-{3,}:?$/.test(cell))) {
      const header = cells(line);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i]!.trim().includes('|') && lines[i]!.trim()) rows.push(cells(lines[i++]!));
      nodes.push(h('div', { class: 'answer-table-scroll', tabindex: 0, role: 'region', 'aria-label': '回答表格' }, [h('table', [
        h('thead', [h('tr', header.map(cell => h('th', inline(cell))))]),
        h('tbody', rows.map(row => h('tr', header.map((_, index) => h('td', inline(row[index] || ''))))))
      ])]));
    } else if (/^(?:[-*+]|\d+[.)])\s+/.test(line)) {
      const ordered = /^\d/.test(line);
      const itemPattern = ordered ? /^\d+[.)]\s+/ : /^[-*+]\s+/;
      const items: VNodeChild[] = [];
      const first = ordered ? parseInt(line, 10) : undefined;
      while (i < lines.length && itemPattern.test(lines[i]!.trim())) items.push(h('li', inline(lines[i++]!.trim().replace(itemPattern, ''))));
      nodes.push(h(ordered ? 'ol' : 'ul', ordered ? { start: first } : {}, items));
    } else {
      const paragraph = [lines[i++]!];
      while (i < lines.length && lines[i]!.trim() && !structural(lines[i]!) && !(lines[i]!.includes('|') && i + 1 < lines.length && cells(lines[i + 1]!).every(cell => /^:?-{3,}:?$/.test(cell)))) paragraph.push(lines[i++]!);
      nodes.push(h('p', inline(paragraph.join('\n'))));
    }
  }
  return h('div', { class: 'answer-content' }, nodes);
}
