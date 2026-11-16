/** Relógio único da sessão de captura: ms desde o início da sessão (spec 001, RF-07). */
export interface SessionClock {
  now(): number;
}

export const createSessionClock = (perfNow: () => number = () => performance.now()): SessionClock => {
  const t0 = perfNow();
  return { now: () => Math.max(0, Math.round(perfNow() - t0)) };
};
