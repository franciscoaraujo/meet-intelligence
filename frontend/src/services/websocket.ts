/**
 * ============================================================================
 * Serviço de Comunicação em Tempo Real via WebSocket STOMP (websocket.ts)
 * ============================================================================
 * Conecta o Frontend ao broker STOMP do Spring Boot (/ws via SockJS) para
 * receber eventos assíncronos durante uma reunião ao vivo:
 *  - /topic/meetings/{id}/transcripts : Novos trechos transcritos (STT)
 *  - /topic/meetings/{id}/insights    : Decisões, tarefas e tópicos detectados pela IA
 *  - /topic/meetings/{id}/summary     : Atualizações incrementais do resumo executivo
 */
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { TranscriptSegment, AIInsight, MeetingSummary } from '../types';

export class MeetingWebSocketService {
  private client: Client | null = null;
  private meetingId: string;
  private onTranscriptCallback: (segment: TranscriptSegment) => void;
  private onInsightCallback?: (insight: AIInsight) => void;
  private onSummaryCallback?: (summary: MeetingSummary) => void;
  private onConnectionStatusChange?: (connected: boolean) => void;

  constructor(
    meetingId: string,
    onTranscript: (segment: TranscriptSegment) => void,
    onInsight?: (insight: AIInsight) => void,
    onSummary?: (summary: MeetingSummary) => void,
    onConnectionChange?: (connected: boolean) => void
  ) {
    this.meetingId = meetingId;
    this.onTranscriptCallback = onTranscript;
    this.onInsightCallback = onInsight;
    this.onSummaryCallback = onSummary;
    this.onConnectionStatusChange = onConnectionChange;
  }

  public connect() {
    const socketUrl = import.meta.env.VITE_WS_URL || 'http://localhost:8085/ws';

    this.client = new Client({
      webSocketFactory: () => new SockJS(socketUrl),
      reconnectDelay: 5000,
      heartbeatIncoming: 4000,
      heartbeatOutgoing: 4000,
      onConnect: () => {
        if (this.onConnectionStatusChange) this.onConnectionStatusChange(true);

        // Canal de Transcrição em Tempo Real
        this.client?.subscribe(`/topic/meetings/${this.meetingId}/transcripts`, (message) => {
          if (message.body) {
            try {
              const segment: TranscriptSegment = JSON.parse(message.body);
              this.onTranscriptCallback(segment);
            } catch (err) {
              console.error('Erro ao deserializar transcrição via WS:', err);
            }
          }
        });

        // Canal de AI Insights Automáticos
        this.client?.subscribe(`/topic/meetings/${this.meetingId}/insights`, (message) => {
          if (message.body && this.onInsightCallback) {
            try {
              const insight: AIInsight = JSON.parse(message.body);
              this.onInsightCallback(insight);
            } catch (err) {
              console.error('Erro ao deserializar insight via WS:', err);
            }
          }
        });

        // Canal de Resumo Dinâmico Contínuo
        this.client?.subscribe(`/topic/meetings/${this.meetingId}/summary`, (message) => {
          if (message.body && this.onSummaryCallback) {
            try {
              const summaryData: MeetingSummary = JSON.parse(message.body);
              this.onSummaryCallback(summaryData);
            } catch (err) {
              console.error('Erro ao deserializar resumo via WS:', err);
            }
          }
        });
      },
      onDisconnect: () => {
        if (this.onConnectionStatusChange) this.onConnectionStatusChange(false);
      },
      onStompError: (frame) => {
        console.error('Erro STOMP:', frame.headers['message']);
        if (this.onConnectionStatusChange) this.onConnectionStatusChange(false);
      },
    });

    this.client.activate();
  }

  public disconnect() {
    if (this.client) {
      this.client.deactivate();
      this.client = null;
    }
  }
}
