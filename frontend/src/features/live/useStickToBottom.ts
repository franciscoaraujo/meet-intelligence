import { useCallback, useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react';

/** Distância do fim (px) em que o usuário ainda conta como "no fim" (spec 008, RF-11). */
export const STICK_THRESHOLD_PX = 80;

const isNearBottom = (el: HTMLElement, threshold: number) => el.scrollHeight - el.scrollTop - el.clientHeight <= threshold;

/**
 * Rolagem automática só quando o usuário já está no fim da lista.
 * @param itemCount número de itens (o aumento conta como "novas falas" quando o usuário está lendo acima)
 * @param contentKey qualquer valor que muda quando o conteúdo do fim muda (ex.: texto provisório)
 */
export const useStickToBottom = (ref: RefObject<HTMLElement | null>, itemCount: number, contentKey: unknown = null,
  threshold = STICK_THRESHOLD_PX) => {
  const atBottomRef = useRef(true);
  const [newCount, setNewCount] = useState(0);
  const lastCount = useRef(itemCount);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const onScroll = () => {
      atBottomRef.current = isNearBottom(el, threshold);
      if (atBottomRef.current) setNewCount(0);
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, [ref, threshold]);

  useLayoutEffect(() => {
    const el = ref.current;
    const added = itemCount - lastCount.current;
    lastCount.current = itemCount;
    if (!el) return;
    if (atBottomRef.current) {
      el.scrollTop = el.scrollHeight;
    } else if (added > 0) {
      setNewCount((n) => n + added);
    }
  }, [ref, itemCount, contentKey]);

  const scrollToBottom = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    atBottomRef.current = true;
    setNewCount(0);
    if (typeof el.scrollTo === 'function') {
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' });
    } else {
      el.scrollTop = el.scrollHeight;
    }
  }, [ref]);

  return { newCount, scrollToBottom };
};
