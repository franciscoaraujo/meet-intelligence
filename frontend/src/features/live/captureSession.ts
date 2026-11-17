/**
 * Ciclo da sessão de captura na sala ao vivo (spec 020): um ponto de início e de fim, que funciona por
 * qualquer caminho que a pessoa comece. Separado da página para poder ser testado sem áudio real.
 */
export type SessionStatus = 'IN_PROGRESS' | 'COMPLETED';

export interface SessionDeps {
  /** Abre o compartilhamento; true se a fonte ficou ativa. */
  startTab: () => Promise<boolean>;
  stopTab: () => Promise<void>;
  /** Liga cronômetro e "Encerrar"; o microfone só com withMic. */
  startRecording: (opts: { withMic: boolean }) => Promise<void>;
  /** Para todas as fontes, enviando os trechos pendentes. */
  stopRecording: () => Promise<void>;
  isRecording: () => boolean;
  tabOn: () => boolean;
  meetingStatus: () => string | undefined;
  interviewMode: () => boolean;
  markStatus: (status: SessionStatus) => Promise<void>;
  triggerAnalysis: () => void;
}

export const createSessionActions = (d: SessionDeps) => {
  const beginSession = async (withMic: boolean) => {
    if (d.meetingStatus() !== 'IN_PROGRESS') await d.markStatus('IN_PROGRESS');
    // RN-03: o modo entrevista nunca liga o microfone
    await d.startRecording({ withMic: withMic && !d.interviewMode() });
  };

  /** RF-01: "Iniciar"/"Retomar" abre o compartilhamento e, se aceito, inicia a sessão. */
  const start = async () => {
    const shared = await d.startTab();
    if (!shared) return false;
    await beginSession(true);
    return true;
  };

  /** RF-03: encerra fontes, marca concluída e dispara a análise final. */
  const stop = async () => {
    await d.stopRecording();
    await d.markStatus('COMPLETED');
    d.triggerAnalysis();
  };

  /** Botão principal do cabeçalho. */
  const toggle = async () => (d.isRecording() ? stop() : start());

  /** RF-02: ligar o "Áudio do computador" com a sessão parada também inicia a sessão. */
  const toggleComputerAudio = async () => {
    if (d.tabOn()) {
      // RF-04: desligar uma fonte não encerra a sessão
      await d.stopTab();
      return;
    }
    const sessionWasRunning = d.isRecording();
    const shared = await d.startTab();
    if (shared && !sessionWasRunning) await beginSession(false);
  };

  /** RF-06: reunião deixada em andamento (sem captura neste navegador) é encerrada daqui. */
  const endWithoutCapture = async () => {
    await d.markStatus('COMPLETED');
    d.triggerAnalysis();
  };

  return { start, stop, toggle, toggleComputerAudio, endWithoutCapture };
};
