/**
 * Mescla a lista que veio do servidor com a que já está na tela (spec 008, RF-10).
 * - A ordem e o conteúdo seguem `next` (fonte da verdade).
 * - Item sem mudança mantém o MESMO objeto de `prev`, para o React.memo não re-renderizar a linha.
 * - Se nada mudou, devolve o próprio `prev` (o setState não causa nova renderização).
 */
export const mergeById = <T extends { id: string }>(prev: T[], next: T[]): T[] => {
  const previous = new Map(prev.map((item) => [item.id, item]));
  let changed = prev.length !== next.length;
  const merged = next.map((item, index) => {
    const old = previous.get(item.id);
    const keep = old !== undefined && (old === item || sameContent(old, item));
    const result = keep ? old : item;
    if (!changed && prev[index] !== result) changed = true;
    return result;
  });
  return changed ? merged : prev;
};

const sameContent = (a: unknown, b: unknown): boolean => {
  try {
    return JSON.stringify(a) === JSON.stringify(b);
  } catch {
    return false;
  }
};

/** Insere ou substitui um item recebido pelo WebSocket, preservando a identidade se nada mudou. */
export const upsertById = <T extends { id: string }>(prev: T[], item: T, position: 'start' | 'end' = 'end'): T[] => {
  const index = prev.findIndex((p) => p.id === item.id);
  if (index === -1) return position === 'start' ? [item, ...prev] : [...prev, item];
  if (sameContent(prev[index], item)) return prev;
  const copy = prev.slice();
  copy[index] = item;
  return copy;
};
