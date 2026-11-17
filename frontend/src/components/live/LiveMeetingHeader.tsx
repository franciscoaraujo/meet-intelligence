import React, { useEffect, useRef, useState } from 'react';
import {
  ArrowLeft, CheckCircle2, Loader2, Mic, MonitorSpeaker, MoreHorizontal, RefreshCw, ShieldCheck, Square, Target, Upload,
} from 'lucide-react';
import { INTERVIEW_LANGUAGES, type InterviewLanguageTag } from '../../features/live/interviewLanguage';
import { ANSWER_STYLES, type AnswerStyle } from '../../features/live/answerStyle';
import type { MicMode } from '../../features/capture/types';

interface Props {
  title: string;
  onBack: () => void;
  language: InterviewLanguageTag;
  onLanguageChange: (tag: InterviewLanguageTag) => void;
  languageError?: string | null;
  answerStyle: AnswerStyle;
  onAnswerStyleChange: (style: AnswerStyle) => void;
  role: 'CANDIDATE' | 'INTERVIEWER';
  onRoleChange: (role: 'CANDIDATE' | 'INTERVIEWER') => void;
  connected: boolean;
  interviewMode: boolean;
  onToggleInterviewMode: () => void;
  computerAudioOn: boolean;
  onToggleComputerAudio: () => void;
  manualPending: boolean;
  onQuestionFinished: () => void;
  analyzing: boolean;
  onConsolidate: () => void;
  uploading: boolean;
  onUpload: () => void;
  micMode: MicMode;
  onMicModeChange: (mode: MicMode) => void;
  isRecording: boolean;
  elapsed: string;
  onToggleRecording: () => void;
  /** Spec 020 RF-06: reunião em andamento sem captura neste navegador ("Retomar" + "Encerrar sessão"). */
  canResume?: boolean;
  onEndSession?: () => void;
}

const CONSENT_TEXT =
  'Grave e analise com IA somente com o consentimento de todos os participantes da reunião ou ligação (LGPD).';

const MIC_OPTIONS: Array<{ mode: MicMode; label: string }> = [
  { mode: 'BACKEND', label: 'Transcrição do servidor' },
  { mode: 'WEB_SPEECH', label: 'Reconhecimento do navegador' },
  { mode: 'OFF', label: 'Desligado (só o participante remoto)' },
];

const ghost =
  'inline-flex items-center gap-1.5 h-8 px-3 rounded-lg text-xs font-medium text-[#3c4043] border border-[#dadce0] bg-white hover:bg-[#f8f9fa] transition-colors cursor-pointer disabled:opacity-60 whitespace-nowrap';

/**
 * Cabeçalho da sala ao vivo (spec 013): controles principais em uma linha; ações secundárias no
 * menu "⋯". Nenhuma ação foi removida, só reorganizada.
 */
export const LiveMeetingHeader: React.FC<Props> = (p) => {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const close = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) setMenuOpen(false);
    };
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && setMenuOpen(false);
    document.addEventListener('mousedown', close);
    document.addEventListener('keydown', esc);
    return () => {
      document.removeEventListener('mousedown', close);
      document.removeEventListener('keydown', esc);
    };
  }, [menuOpen]);

  return (
    <header className="px-5 py-2.5 border-b border-[#e8eaed] bg-white flex items-center justify-between gap-4 shrink-0" data-testid="live-header">
      {/* Esquerda: título e contexto da sessão */}
      <div className="flex items-center gap-3 min-w-0">
        <button onClick={p.onBack} className="p-1.5 rounded-lg hover:bg-[#f1f3f4] text-[#5f6368] cursor-pointer" title="Voltar para reuniões" aria-label="Voltar">
          <ArrowLeft className="w-4 h-4" />
        </button>
        <div className="min-w-0">
          <h1 className="text-[15px] font-semibold text-[#202124] truncate max-w-[22rem]">{p.title}</h1>
          <div className="flex items-center gap-2.5 mt-0.5 text-[11px] text-[#5f6368]">
            <select
              value={p.language}
              onChange={(e) => p.onLanguageChange(e.target.value as InterviewLanguageTag)}
              className="h-6 pl-1.5 pr-5 rounded-md border border-[#dadce0] bg-white text-[11px] font-medium text-[#3c4043] cursor-pointer"
              aria-label="Idioma da entrevista"
              title="Idioma da entrevista: captura, perguntas e respostas do copiloto"
              data-testid="interview-language"
            >
              {INTERVIEW_LANGUAGES.map((l) => (
                <option key={l.tag} value={l.tag}>
                  {l.short} · {l.label}
                </option>
              ))}
            </select>
            <select
              value={p.answerStyle}
              onChange={(e) => p.onAnswerStyleChange(e.target.value as AnswerStyle)}
              className="h-6 pl-1.5 pr-5 rounded-md border border-[#dadce0] bg-white text-[11px] font-medium text-[#3c4043] cursor-pointer"
              aria-label="Estilo das respostas"
              title={`Estilo das respostas do copiloto: ${ANSWER_STYLES.find((s) => s.value === p.answerStyle)?.hint ?? ''}`}
              data-testid="answer-style"
            >
              {ANSWER_STYLES.map((s) => (
                <option key={s.value} value={s.value}>
                  Estilo: {s.label}
                </option>
              ))}
            </select>
            <div className="inline-flex h-6 rounded-md border border-[#dadce0] overflow-hidden" role="group" aria-label="Seu papel na entrevista">
              {(['CANDIDATE', 'INTERVIEWER'] as const).map((r) => (
                <button
                  key={r}
                  type="button"
                  onClick={() => p.onRoleChange(r)}
                  aria-pressed={p.role === r}
                  className={`px-2 text-[11px] font-medium cursor-pointer ${p.role === r ? 'bg-[#e8f0fe] text-[#1a73e8]' : 'bg-white text-[#5f6368] hover:bg-[#f8f9fa]'}`}
                >
                  {r === 'CANDIDATE' ? 'Candidato' : 'Entrevistador'}
                </button>
              ))}
            </div>
            <span className="inline-flex items-center gap-1" title={p.connected ? 'Sincronização em tempo real ativa' : 'Sem conexão em tempo real'}>
              <span className={`w-1.5 h-1.5 rounded-full ${p.connected ? 'bg-[#1e8e3e]' : 'bg-[#9aa0a6]'}`} />
              {p.connected ? 'Ao vivo' : 'Offline'}
            </span>
            <span className="hidden xl:inline-flex items-center gap-1" title={CONSENT_TEXT}>
              <ShieldCheck className="w-3 h-3" /> Consentimento obrigatório (LGPD)
            </span>
            {p.languageError && <span role="alert" className="text-[#d93025]">{p.languageError}</span>}
          </div>
        </div>
      </div>

      {/* Direita: ações */}
      <div className="flex items-center gap-2 shrink-0">
        <button
          type="button"
          onClick={p.onToggleComputerAudio}
          className={`${ghost} ${p.computerAudioOn ? '!border-[#1e8e3e] !text-[#137333] !bg-[#e6f4ea]' : ''}`}
          title={
            'Captura o som de qualquer fonte do computador:\n' +
            '• Uma aba (Meet, WhatsApp Web, YouTube…): marque "Compartilhar áudio da guia".\n' +
            '• Todo o som do PC (WhatsApp, Teams, Zoom): escolha "Tela inteira" e marque "Compartilhar áudio do sistema".'
          }
          data-testid="computer-audio-toggle"
        >
          <MonitorSpeaker className="w-3.5 h-3.5" />
          {p.computerAudioOn ? 'Parar áudio do computador' : 'Áudio do computador'}
        </button>

        {!p.interviewMode && (
          <button type="button" onClick={p.onQuestionFinished} disabled={p.manualPending} className={ghost}
            title="Clique quando o entrevistador terminar de perguntar" data-testid="question-finished">
            {p.manualPending ? <Loader2 className="w-3.5 h-3.5 motion-safe:animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
            {p.manualPending ? 'Enviando…' : 'Pergunta terminou'}
          </button>
        )}

        <button
          type="button"
          onClick={p.onToggleInterviewMode}
          aria-pressed={p.interviewMode}
          className={`${ghost} ${p.interviewMode ? '!border-[#1a73e8] !text-[#1a73e8] !bg-[#e8f0fe]' : ''}`}
          title={p.interviewMode ? 'Sair do modo entrevista' : 'Destaca a pergunta atual e a resposta sugerida'}
          data-testid="interview-mode-toggle"
        >
          <Target className="w-3.5 h-3.5" />
          Modo entrevista
        </button>

        <div className="relative" ref={menuRef}>
          <button type="button" onClick={() => setMenuOpen((v) => !v)} className={`${ghost} !px-2`} aria-haspopup="menu"
            aria-expanded={menuOpen} aria-label="Mais ações" title="Mais ações" data-testid="more-actions">
            <MoreHorizontal className="w-4 h-4" />
          </button>
          {menuOpen && (
            <div role="menu" className="absolute right-0 z-30 mt-1.5 w-72 rounded-xl border border-[#e8eaed] bg-white shadow-lg py-1.5 text-xs">
              <button type="button" role="menuitem" disabled={p.uploading}
                onClick={() => { setMenuOpen(false); p.onUpload(); }}
                className="w-full flex items-center gap-2.5 px-3.5 py-2 text-left text-[#3c4043] hover:bg-[#f8f9fa] cursor-pointer disabled:opacity-60">
                <Upload className="w-3.5 h-3.5 text-[#5f6368]" /> {p.uploading ? 'Enviando mídia…' : 'Enviar gravação (áudio ou vídeo)'}
              </button>
              <button type="button" role="menuitem" disabled={p.analyzing}
                onClick={() => { setMenuOpen(false); p.onConsolidate(); }}
                className="w-full flex items-center gap-2.5 px-3.5 py-2 text-left text-[#3c4043] hover:bg-[#f8f9fa] cursor-pointer disabled:opacity-60">
                <RefreshCw className={`w-3.5 h-3.5 text-[#5f6368] ${p.analyzing ? 'motion-safe:animate-spin' : ''}`} />
                {p.analyzing ? 'Consolidando…' : 'Consolidar análise com IA'}
              </button>
              <div className="my-1.5 border-t border-[#f1f3f4]" />
              <p className="px-3.5 pt-1 pb-1.5 text-[10px] font-semibold uppercase tracking-wider text-[#80868b] flex items-center gap-1.5">
                <Mic className="w-3 h-3" /> Seu microfone
              </p>
              {p.interviewMode && (
                <p className="px-3.5 pb-1.5 text-[11px] text-[#5f6368]">Desligado no modo entrevista: só o participante remoto é captado.</p>
              )}
              {MIC_OPTIONS.map((o) => {
                const selected = (p.interviewMode ? 'OFF' : p.micMode) === o.mode;
                return (
                  <button key={o.mode} type="button" role="menuitemradio" aria-checked={selected} disabled={p.interviewMode}
                    onClick={() => { setMenuOpen(false); p.onMicModeChange(o.mode); }}
                    className="w-full flex items-center gap-2.5 px-3.5 py-1.5 text-left text-[#3c4043] hover:bg-[#f8f9fa] cursor-pointer disabled:opacity-50 disabled:cursor-default">
                    <span className={`w-3 h-3 rounded-full border ${selected ? 'border-[#1a73e8] bg-[#1a73e8] ring-2 ring-inset ring-white' : 'border-[#9aa0a6]'}`} />
                    {o.label}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {p.canResume && !p.isRecording && p.onEndSession && (
          <button
            type="button"
            onClick={p.onEndSession}
            className={ghost}
            title="Marca a reunião como concluída e gera a análise final, sem capturar mais áudio"
            data-testid="end-session"
          >
            <Square className="w-3 h-3" /> Encerrar sessão
          </button>
        )}

        <button
          type="button"
          onClick={p.onToggleRecording}
          title={p.isRecording ? undefined : 'Compartilha o áudio da reunião e começa a transcrever'}
          className={`inline-flex items-center gap-2 h-8 px-3.5 rounded-lg text-xs font-semibold text-white transition-colors cursor-pointer whitespace-nowrap ${
            p.isRecording ? 'bg-[#d93025] hover:bg-[#b3261e]' : 'bg-[#1a73e8] hover:bg-[#1765cc]'
          }`}
          data-testid="recording-toggle"
        >
          {p.isRecording ? (
            <>
              <span className="w-2 h-2 rounded-full bg-white motion-safe:animate-pulse" />
              <span className="font-mono tabular-nums">{p.elapsed}</span>
              <span className="w-px h-3.5 bg-white/40" />
              <Square className="w-3 h-3 fill-white" /> Encerrar
            </>
          ) : (
            <>
              <Mic className="w-3.5 h-3.5" /> {p.canResume ? 'Retomar' : 'Iniciar'}
            </>
          )}
        </button>
      </div>
    </header>
  );
};
