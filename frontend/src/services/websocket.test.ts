import { beforeEach, describe, expect, it, vi } from 'vitest';

// Captura a configuração passada ao cliente STOMP, sem abrir conexão de verdade
const created: Array<{ config: Record<string, unknown>; instance: Record<string, unknown> }> = [];
vi.mock('@stomp/stompjs', () => ({
  Client: class {
    connectHeaders: Record<string, string> = {};
    constructor(config: Record<string, unknown>) {
      created.push({ config, instance: this as unknown as Record<string, unknown> });
    }
    activate() {}
    deactivate() {}
  },
}));
vi.mock('sockjs-client', () => ({ default: class {} }));

const { MeetingWebSocketService } = await import('./websocket');

describe('WebSocket autenticado (spec 007)', () => {
  beforeEach(() => {
    created.length = 0;
    localStorage.clear();
  });

  it('RF-07: o CONNECT leva o token do usuário logado, relido a cada conexão', () => {
    localStorage.setItem('token', 'tok-1');
    new MeetingWebSocketService('m1', () => {}).connect();
    const { config, instance } = created[0];

    (config.beforeConnect as () => void)();
    expect(instance.connectHeaders).toEqual({ Authorization: 'Bearer tok-1' });

    localStorage.setItem('token', 'tok-2');
    (config.beforeConnect as () => void)();
    expect(instance.connectHeaders).toEqual({ Authorization: 'Bearer tok-2' });
  });

  it('sem login, não envia cabeçalho (o servidor recusa)', () => {
    new MeetingWebSocketService('m1', () => {}).connect();
    const { config, instance } = created[0];
    (config.beforeConnect as () => void)();
    expect(instance.connectHeaders).toEqual({});
  });
});
