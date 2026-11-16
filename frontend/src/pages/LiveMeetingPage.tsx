import React, { useState, useEffect, useRef, useReducer, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Mic,
  MonitorSpeaker,
  Search,
  Sparkles,
  MessageSquare,
  Volume2,
  Send,
  CheckSquare,
  Award,
  ChevronRight,
} from 'lucide-react';
import { api } from '../services/api';
import { MeetingWebSocketService } from '../services/websocket';
import { Meeting, TranscriptSegment, AIInsight, ChatMessage, MeetingSummary, ChunkTranscriptionResponse, InterviewQuestion } from '../types';
import { InterviewAnswerCard } from '../components/copilot/InterviewAnswerCard';
import { copilotStreamReducer, isDraftActive, type CopilotStreamEvent } from '../features/copilot/copilotStream';
import type { CopilotTranslation } from '../features/copilot/copilotAnswer';
import { TranscriptItem, type TranscriptItemActions } from '../components/live/TranscriptItem';
import { InterviewModeLayout } from '../components/live/InterviewModeLayout';
import { LiveMeetingHeader } from '../components/live/LiveMeetingHeader';
import { mergeById, upsertById } from '../features/live/mergeById';
import { useStickToBottom } from '../features/live/useStickToBottom';
import { loadInterviewModePreference, saveInterviewModePreference } from '../features/live/interviewMode';
import { saveLanguagePreference, toInterviewLanguage, type InterviewLanguageTag } from '../features/live/interviewLanguage';
import { saveAnswerStylePreference, toAnswerStyle, type AnswerStyle } from '../features/live/answerStyle';
import { useCaptureSession } from '../features/capture/useCaptureSession';
import { SourceStatusBadge } from '../components/capture/SourceStatusBadge';
import { manualSpeakerOptions, segmentSpeakerView, type SpeakerRole } from '../features/transcription/speaker';
import { useAuth } from '../context/AuthContext';

/**
 * Ordena a transcrição da fala mais antiga para a mais nova.
 *
 * Usa createdAt (instante real do registro no servidor) porque startTimeMs vem de relógios
 * diferentes (cronômetro da página para o microfone, contagem de segmentos para o áudio da
 * aba do Meet) e reinicia quando a página é recarregada. startTimeMs serve só de desempate.
 * Itens sem createdAt (exibição otimista, ainda não confirmada) vão para o fim; o sort é
 * estável, então entre eles a ordem de chegada é preservada.
 */
export const sortTranscriptsChronologically = (segments: TranscriptSegment[]): TranscriptSegment[] => {
  const timeOf = (s: TranscriptSegment) => {
    const t = s.createdAt ? Date.parse(s.createdAt) : NaN;
    return Number.isNaN(t) ? Number.POSITIVE_INFINITY : t;
  };
  return [...segments].sort((a, b) => {
    const ta = timeOf(a);
    const tb = timeOf(b);
    if (ta !== tb) return ta < tb ? -1 : 1;
    if (ta === Number.POSITIVE_INFINITY) return 0;
    return (a.startTimeMs ?? 0) - (b.startTimeMs ?? 0);
  });
};

export const LiveMeetingPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [meeting, setMeeting] = useState<Meeting | null>(null);
  const [recordingSeconds, setRecordingSeconds] = useState(0);
  const [searchTerm, setSearchTerm] = useState('');
  const [wsConnected, setWsConnected] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [analyzing, setAnalyzing] = useState(false);

  // Estados das 3 Áreas
  const [transcripts, setTranscripts] = useState<TranscriptSegment[]>([]);
  const [editingSegmentId, setEditingSegmentId] = useState<string | null>(null);
  const [editText, setEditText] = useState('');
  const [highlightedTimeMs, setHighlightedTimeMs] = useState<number | null>(null);

  const [insights, setInsights] = useState<AIInsight[]>([]);
  const insightsRef = useRef<AIInsight[]>([]);
  insightsRef.current = insights;
  // Resposta do copiloto chegando em partes, por pergunta (spec 008)
  const [copilotDrafts, dispatchCopilot] = useReducer(copilotStreamReducer, {});
  // Carregamento inicial: esqueletos em vez de listas vazias (spec 008, RF-13)
  const [initialLoading, setInitialLoading] = useState(true);
  // Modo entrevista (spec 008, RF-14/RF-15): null até a reunião carregar e a preferência ser lida
  const [interviewMode, setInterviewMode] = useState<boolean | null>(null);
  const [transcriptExpanded, setTranscriptExpanded] = useState(false);
  const [systemAudioNoticeDismissed, setSystemAudioNoticeDismissed] = useState(false);
  // "Pergunta terminou": aguardando a pergunta manual ser registrada (retorno visual no botão)
  const [manualPending, setManualPending] = useState(false);
  const manualPendingTimer = useRef<number | null>(null);
  const [questions, setQuestions] = useState<InterviewQuestion[]>([]);
  const [summary, setSummary] = useState<MeetingSummary | null>(null);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [chatLoading, setChatLoading] = useState(false);

  // Estados de captura de voz ao vivo (Web Speech API + áudio do computador) e envio direto de falas
  const [manualSpeechInput, setManualSpeechInput] = useState('');
  const { user } = useAuth();
  const speakerOptions = manualSpeakerOptions(user?.fullName);
  const [speakerRole, setSpeakerRole] = useState(speakerOptions[0].label);
  const speakerRoleRef = useRef<string>(speakerOptions[0].label);
  const recordingSecondsRef = useRef<number>(0);

  // Tabs do Painel Central
  const [activeTab, setActiveTab] = useState<'insights' | 'summary' | 'tasks' | 'decisions'>('insights');

  const timerRef = useRef<number | null>(null);
  const transcriptContainerRef = useRef<HTMLDivElement | null>(null);
  const transcriptEndRef = useRef<HTMLDivElement | null>(null);
  const chatContainerRef = useRef<HTMLDivElement | null>(null);
  const chatEndRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  // Sessão de captura (spec 001/009): áudio do computador (aba ou tela inteira) e microfone como fontes independentes
  const capture = useCaptureSession({
    sendChunk: async (payload) =>
      (await api.post<ChunkTranscriptionResponse>(`/meetings/${id}/transcripts/chunk`, payload)).data,
    onTranscribed: (segments) => {
      setTranscripts((prev) => {
        let next = [...prev];
        for (const seg of segments) {
          next = next.some((s) => s.id === seg.id) ? next.map((s) => (s.id === seg.id ? seg : s)) : [...next, seg];
        }
        return next;
      });
      if (id) setTimeout(() => refreshAiState(id), 1000);
    },
    onWebSpeechText: (text) => {
      // Web Speech: fala do microfone, atribuída ao usuário pelo backend (spec 003)
      void sendLiveSpeechSegment(text, speakerOptions[0].label, 'MIC', 'SELF');
    },
    micSpeakerLabel: () => speakerRoleRef.current,
    // Spec 008 RF-20: no modo entrevista só o participante remoto é capturado
    micBlocked: interviewMode === true,
    // Spec 011: idioma da entrevista também no microfone em modo navegador
    language: toInterviewLanguage(meeting?.language),
  });
  const isRecording = capture.isRecording;
  const interimTranscript = capture.interim;
  const tabCaptureOn = capture.tab.state !== 'INATIVA';

  useEffect(() => {
    if (id) {
      loadMeetingData(id);

      const wsService = new MeetingWebSocketService(
        id,
        (newSegment) => {
          setTranscripts((prev) => upsertById(prev, newSegment, 'end'));
        },
        (newInsight) => {
          setInsights((prev) => upsertById(prev, newInsight, 'start'));
        },
        (newSummary) => {
          setSummary(newSummary);
        },
        (connected) => {
          setWsConnected(connected);
          // Ao (re)conectar, recupera eventos que possam ter sido perdidos durante a queda
          if (connected) refreshAiState(id);
        },
        (question) => {
          setQuestions((prev) => upsertById(prev, question, 'end'));
          if (question.utteranceType === 'MANUAL_TRIGGER') setManualPending(false);
        },
        (event: CopilotStreamEvent) => {
          dispatchCopilot(event);
          // O COMPLETED não traz a resposta: ela chega por /insights. Se não chegar em 2 s, busca por REST.
          if (event.type === 'COMPLETED' && event.insightId) {
            window.setTimeout(() => {
              if (!insightsRef.current.some((i) => i.id === event.insightId)) void refreshAiState(id);
            }, 2000);
          }
        }
      );
      wsService.connect();

      return () => {
        wsService.disconnect();
        if (timerRef.current) clearInterval(timerRef.current);
      };
    }
  }, [id]);

  useEffect(() => {
    if (isRecording) {
      timerRef.current = window.setInterval(() => {
        setRecordingSeconds((prev) => {
          const next = prev + 1;
          recordingSecondsRef.current = next;
          return next;
        });
      }, 1000);
    } else if (timerRef.current) {
      clearInterval(timerRef.current);
    }
  }, [isRecording]);

  useEffect(() => {
    if (chatContainerRef.current) {
      chatContainerRef.current.scrollTop = chatContainerRef.current.scrollHeight;
    }
  }, [chatMessages, chatLoading]);

  const loadMeetingData = async (meetingId: string) => {
    try {
      const [meetingRes, transcriptsRes, insightsRes, summaryRes, chatRes] = await Promise.allSettled([
        api.get(`/meetings/${meetingId}`),
        api.get(`/meetings/${meetingId}/transcripts`),
        api.get(`/meetings/${meetingId}/insights`),
        api.get(`/meetings/${meetingId}/summary`),
        api.get(`/meetings/${meetingId}/chat`),
      ]);

      if (meetingRes.status === 'fulfilled') setMeeting(meetingRes.value.data);
      if (transcriptsRes.status === 'fulfilled') setTranscripts(transcriptsRes.value.data || []);
      if (insightsRes.status === 'fulfilled') setInsights(insightsRes.value.data || []);
      if (summaryRes.status === 'fulfilled' && summaryRes.value.data) setSummary(summaryRes.value.data);

      if (chatRes.status === 'fulfilled' && chatRes.value.data && chatRes.value.data.length > 0) {
        setChatMessages(chatRes.value.data);
      } else {
        setChatMessages([
          {
            id: 'msg-init',
            senderType: 'ASSISTANT',
            content: 'Olá! Estou acompanhando as falas e gerando insights em tempo real via RAG. Você pode me fazer perguntas sobre tudo o que for discutido.',
            createdAt: new Date().toISOString(),
          },
        ]);
      }
    } catch (error) {
      console.error('Erro ao carregar dados da reunião:', error);
    } finally {
      setInitialLoading(false);
    }
  };

  const refreshAiState = async (meetingId: string) => {
    try {
      const [insightsRes, summaryRes, transcriptsRes, questionsRes] = await Promise.allSettled([
        api.get(`/meetings/${meetingId}/insights`),
        api.get(`/meetings/${meetingId}/summary`),
        api.get(`/meetings/${meetingId}/transcripts`),
        api.get<InterviewQuestion[]>(`/meetings/${meetingId}/questions`),
      ]);
      if (questionsRes.status === 'fulfilled' && Array.isArray(questionsRes.value.data)) {
        const serverQuestions: InterviewQuestion[] = questionsRes.value.data;
        setQuestions((prev) => mergeById(prev, serverQuestions));
      }
      if (insightsRes.status === 'fulfilled' && Array.isArray(insightsRes.value.data)) {
        const serverInsights: AIInsight[] = insightsRes.value.data;
        setInsights((prev) => mergeById(prev, serverInsights));
      }
      if (summaryRes.status === 'fulfilled' && summaryRes.value.data) {
        setSummary(summaryRes.value.data);
      }
      if (transcriptsRes.status === 'fulfilled' && Array.isArray(transcriptsRes.value.data) && transcriptsRes.value.data.length > 0) {
        setTranscripts((prev) => {
          const serverItems: TranscriptSegment[] = transcriptsRes.value.data;
          const tempItems = prev.filter((item) => String(item.id).startsWith('temp-'));
          // Mescla por id (spec 008, RF-10): falas sem mudança mantêm o mesmo objeto e não são re-renderizadas
          return mergeById(prev, [...serverItems, ...tempItems]);
        });
      }
    } catch (e) {
      // Silencioso em background
    }
  };

  useEffect(() => {
    if (!id) return;
    const syncTimer = window.setInterval(() => {
      refreshAiState(id);
    }, 30000); // rede de segurança: o tempo real vem do WebSocket (P-18)
    return () => clearInterval(syncTimer);
  }, [id]);

  const sendLiveSpeechSegment = async (
    text: string,
    speaker = speakerRole,
    audioSource: 'MIC' | 'MANUAL' = 'MANUAL',
    role: SpeakerRole = speakerOptions.find((o) => o.label === speaker)?.role ?? 'UNKNOWN',
  ) => {
    if (!id || !text.trim()) return;
    const cleanText = text.trim();
    const startMs = recordingSecondsRef.current * 1000;
    const tempId = `temp-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;

    // Exibe imediatamente na tela (otimista) para nunca sumir após o fim da fala
    const optimisticSegment: TranscriptSegment = {
      id: tempId,
      meetingId: id,
      speakerLabel: speaker,
      originalText: cleanText,
      startTimeMs: startMs,
      endTimeMs: startMs + 4000,
      confidence: 0.99,
      audioSource,
      speakerRole: role,
    };
    setTranscripts((prev) => [...prev, optimisticSegment]);

    try {
      const res = await api.post(`/meetings/${id}/transcripts/live-text`, {
        speakerLabel: speaker,
        speakerRole: role,
        audioSource,
        text: cleanText,
        startTimeMs: startMs,
        endTimeMs: startMs + 4000,
      });
      if (res.data) {
        setTranscripts((prev) => {
          const withoutTemp = prev.filter((s) => s.id !== tempId);
          if (withoutTemp.some((s) => s.id === res.data.id)) {
            return withoutTemp.map((s) => (s.id === res.data.id ? res.data : s));
          }
          return [...withoutTemp, res.data];
        });
      }
      setTimeout(() => refreshAiState(id), 1200);
    } catch (e) {
      console.error('Erro ao registrar fala ao vivo:', e);
    }
  };

  const changeSpeakerRole = (role: string) => {
    speakerRoleRef.current = role;
    setSpeakerRole(role);
  };

  const handleManualSpeechSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!manualSpeechInput.trim()) return;
    const text = manualSpeechInput;
    setManualSpeechInput('');
    await sendLiveSpeechSegment(text, speakerRoleRef.current, 'MANUAL');
  };

  /**
   * "Pergunta terminou" (spec 005 RF-09, revisto): o candidato sinaliza que o entrevistador terminou de
   * perguntar; a fala desde a pergunta anterior vai para o copiloto, que identifica a pergunta e responde.
   */
  const answerLatestQuestion = async () => {
    if (!id || manualPending) return;
    setManualPending(true);
    if (manualPendingTimer.current) window.clearTimeout(manualPendingTimer.current);
    // Sem retorno do WebSocket em 8 s, libera o botão (a pergunta ainda pode chegar pela ressincronização)
    manualPendingTimer.current = window.setTimeout(() => setManualPending(false), 8000);
    try {
      await api.post(`/meetings/${id}/questions/answer-latest`);
    } catch (e) {
      console.error('Erro ao acionar o copiloto:', e);
      setManualPending(false);
    }
  };

  /** Spec 012: tradução do card do copiloto (gravada no card na primeira vez). */
  const translateAnswer = async (questionId: string, language: string): Promise<CopilotTranslation> => {
    const res = await api.post<CopilotTranslation>(`/meetings/${id}/questions/${questionId}/translate?language=${language}`);
    return res.data;
  };

  const regenerateAnswer = async (questionId: string) => {
    if (!id) return;
    setQuestions((prev) => prev.map((q) => (q.id === questionId ? { ...q, status: 'ANSWERING' } : q)));
    await api.post(`/meetings/${id}/questions/${questionId}/answer`).catch((e) => console.error('Erro ao gerar resposta:', e));
  };

  const dismissQuestion = async (questionId: string) => {
    if (!id) return;
    try {
      const res = await api.post<InterviewQuestion>(`/meetings/${id}/questions/${questionId}/dismiss`);
      setQuestions((prev) => prev.map((q) => (q.id === questionId ? res.data : q)));
      setInsights((prev) => prev.filter((i) => i.questionId !== questionId));
    } catch (e) {
      console.error('Erro ao descartar pergunta:', e);
    }
  };

  /** Papel do usuário na entrevista (spec 003, RF-04). */
  const updateInterviewRole = async (role: 'CANDIDATE' | 'INTERVIEWER') => {
    if (!id) return;
    try {
      const res = await api.patch<Meeting>(`/meetings/${id}/interview-role?role=${role}`);
      setMeeting(res.data);
    } catch (e) {
      console.error('Erro ao atualizar papel na entrevista:', e);
    }
  };

  const toggleRecording = async () => {
    if (!capture.isRecording) {
      await api.patch(`/meetings/${id}/status?status=IN_PROGRESS`).catch(() => {});
      await capture.startRecording();
    } else {
      await stopRecording();
    }
  };

  /** Encerra todas as fontes (enviando os trechos pendentes) e consolida a reunião com IA. */
  const stopRecording = async () => {
    await capture.stopRecording();
    if (id) {
      await api.patch(`/meetings/${id}/status?status=COMPLETED`).catch(() => {});
      triggerAiAnalysis();
    }
  };

  const triggerAiAnalysis = async () => {
    if (!id || analyzing) return;
    try {
      setAnalyzing(true);
      await api.post(`/meetings/${id}/insights/trigger`);
      setTimeout(() => refreshAiState(id), 1200);
    } catch (err) {
      console.error('Erro ao acionar IA:', err);
    } finally {
      setTimeout(() => setAnalyzing(false), 1500);
    }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !id) return;

    try {
      setUploading(true);
      const formData = new FormData();
      formData.append('file', file);

      await api.post(`/meetings/${id}/recordings/upload`, formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });

      const res = await api.get(`/meetings/${id}/transcripts`);
      setTranscripts(res.data);
    } catch (error) {
      console.error('Erro no upload:', error);
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const saveCorrection = async (segmentId: string) => {
    if (!id || !editText.trim()) return;

    try {
      await api.patch(`/meetings/${id}/transcripts/${segmentId}`, {
        correctedText: editText,
      });

      setTranscripts((prev) =>
        prev.map((s) => (s.id === segmentId ? { ...s, correctedText: editText } : s))
      );
      setEditingSegmentId(null);
      setEditText('');
    } catch (error) {
      console.error('Erro ao salvar correção:', error);
    }
  };

  const handleSendMessage = async (e?: React.FormEvent) => {
    if (e) e.preventDefault();
    if (!chatInput.trim() || chatLoading || !id) return;

    const query = chatInput;
    const tempUserMsg: ChatMessage = {
      id: 'usr-' + Date.now(),
      senderType: 'USER',
      content: query,
      createdAt: new Date().toISOString(),
    };

    setChatMessages((prev) => [...prev, tempUserMsg]);
    setChatInput('');
    setChatLoading(true);

    try {
      const response = await api.post(`/meetings/${id}/chat`, {
        message: query,
      });

      const assistantMsg: ChatMessage = {
        id: response.data.id || 'ai-' + Date.now(),
        senderType: 'ASSISTANT',
        content: response.data.content,
        citations: response.data.citations || [],
        createdAt: response.data.createdAt || new Date().toISOString(),
      };

      setChatMessages((prev) => [...prev, assistantMsg]);
    } catch (error) {
      console.error('Erro ao processar chat RAG:', error);
      setChatMessages((prev) => [
        ...prev,
        {
          id: 'err-' + Date.now(),
          senderType: 'ASSISTANT',
          content: 'Não foi possível consultar a IA neste momento. Verifique a conexão com o servidor.',
          createdAt: new Date().toISOString(),
        },
      ]);
    } finally {
      setChatLoading(false);
    }
  };

  const askAiToAnswerQuestion = async (questionText: string) => {
    if (!id || !questionText.trim() || chatLoading) return;
    const query = `Responda tecnicamente à pergunta do entrevistador: ${questionText.trim()}`;

    const tempUserMsg: ChatMessage = {
      id: 'usr-' + Date.now(),
      senderType: 'USER',
      content: `💡 Responder pergunta: "${questionText.trim()}"`,
      createdAt: new Date().toISOString(),
    };
    setChatMessages((prev) => [...prev, tempUserMsg]);
    setChatLoading(true);

    try {
      const response = await api.post(`/meetings/${id}/chat`, { message: query });
      const assistantMsg: ChatMessage = {
        id: response.data.id || 'ai-' + Date.now(),
        senderType: 'ASSISTANT',
        content: response.data.content,
        citations: response.data.citations || [],
        createdAt: response.data.createdAt || new Date().toISOString(),
      };
      setChatMessages((prev) => [...prev, assistantMsg]);
    } catch (error) {
      console.error('Erro ao responder pergunta técnica:', error);
    } finally {
      setChatLoading(false);
    }
  };

  const jumpToTimestamp = (startTimeMs: number) => {
    setHighlightedTimeMs(startTimeMs);
    const element = document.getElementById(`segment-${startTimeMs}`);
    if (element) {
      element.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }
    setTimeout(() => setHighlightedTimeMs(null), 3000);
  };

  const formatTime = (seconds: number) => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}`;
  };

  const formatMs = (ms: number) => formatTime(Math.floor(ms / 1000));

  // Ações da linha da transcrição com referência estável: o React.memo do TranscriptItem só
  // re-renderiza a fala que mudou (spec 008, RF-10). Os handlers reais são lidos no clique.
  const transcriptHandlers = useRef<TranscriptItemActions | null>(null);
  transcriptHandlers.current = {
    askAi: (text) => void askAiToAnswerQuestion(text),
    startEdit: (segment) => {
      setEditingSegmentId(segment.id);
      setEditText(segment.correctedText || segment.originalText);
    },
    cancelEdit: () => setEditingSegmentId(null),
    saveEdit: (segmentId) => void saveCorrection(segmentId),
    setEditText,
    formatMs,
  };
  const transcriptActions = useMemo<TranscriptItemActions>(
    () => ({
      askAi: (text) => transcriptHandlers.current?.askAi(text),
      startEdit: (segment) => transcriptHandlers.current?.startEdit(segment),
      cancelEdit: () => transcriptHandlers.current?.cancelEdit(),
      saveEdit: (segmentId) => transcriptHandlers.current?.saveEdit(segmentId),
      setEditText: (text) => transcriptHandlers.current?.setEditText(text),
      formatMs: (ms) => transcriptHandlers.current?.formatMs(ms) ?? '',
    }),
    []
  );

  // Modo entrevista: escolha salva no navegador; sem escolha, ativo quando o usuário é o candidato
  useEffect(() => {
    if (meeting && interviewMode === null) setInterviewMode(loadInterviewModePreference(meeting.userInterviewRole));
  }, [meeting, interviewMode]);

  /** Spec 011: idioma da entrevista (transcrição, detecção de perguntas e respostas do copiloto). */
  const [languageError, setLanguageError] = useState<string | null>(null);
  const changeLanguage = async (tag: InterviewLanguageTag) => {
    if (!id) return;
    setLanguageError(null);
    saveLanguagePreference(tag);
    try {
      const res = await api.patch<Meeting>(`/meetings/${id}/language?language=${tag}`);
      setMeeting(res.data);
    } catch (e) {
      console.error('Erro ao trocar o idioma:', e);
      setLanguageError('Não foi possível trocar o idioma.');
    }
  };

  /** Spec 014: estilo das respostas do copiloto (vale para as próximas respostas). */
  const changeAnswerStyle = async (style: AnswerStyle) => {
    if (!id) return;
    setLanguageError(null);
    saveAnswerStylePreference(style);
    try {
      const res = await api.patch<Meeting>(`/meetings/${id}/answer-style?style=${style}`);
      setMeeting(res.data);
    } catch (e) {
      console.error('Erro ao trocar o estilo das respostas:', e);
      setLanguageError('Não foi possível trocar o estilo.');
    }
  };

  const toggleInterviewMode = () => {
    const next = !interviewMode;
    saveInterviewModePreference(next);
    setInterviewMode(next);
    setTranscriptExpanded(false);
  };


  // Ordem cronológica: da fala mais antiga para a mais nova (pelo horário real de registro).
  // Itens ainda sem createdAt (otimistas ou recém-chegados) ficam no fim, na ordem de chegada.
  const chronologicalTranscripts = sortTranscriptsChronologically(transcripts);

  const filteredTranscripts = chronologicalTranscripts.filter((t) => {
    const text = t.correctedText || t.originalText;
    return text.toLowerCase().includes(searchTerm.toLowerCase());
  });

  const taskInsights = insights.filter((i) => i.category === 'TASK');
  const decisionInsights = insights.filter((i) => i.category === 'DECISION');
  // Modo entrevista: só a transcrição recente, salvo se o usuário expandir
  const visibleTranscripts = interviewMode && !transcriptExpanded && !searchTerm ? filteredTranscripts.slice(-3) : filteredTranscripts;
  const { newCount: newTranscriptCount, scrollToBottom: scrollTranscriptToBottom } = useStickToBottom(
    transcriptContainerRef,
    filteredTranscripts.length,
    `${visibleTranscripts[visibleTranscripts.length - 1]?.id ?? ''}|${interimTranscript ?? ''}`
  );

  // Perguntas ainda sem card de resposta gravado: gerando (com ou sem rascunho), falha ou aguardando confirmação
  const pendingQuestions = questions.filter(
    (q) =>
      !q.answerInsightId &&
      (isDraftActive(copilotDrafts[q.id]) || q.status === 'ANSWERING' || q.status === 'FAILED' || (q.status === 'DETECTED' && !q.autoAnswer))
  );

  return (
    <div className="flex flex-col h-[calc(100vh-76px)] max-h-[calc(100vh-76px)] bg-white overflow-hidden">
      <input type="file" ref={fileInputRef} onChange={handleFileUpload} accept="audio/*,video/*" className="hidden" />
      {/* Spec 013: cabeçalho limpo; ações secundárias no menu "⋯" */}
      <LiveMeetingHeader
        title={meeting?.title || 'Sala de reunião'}
        onBack={() => navigate('/meetings')}
        language={toInterviewLanguage(meeting?.language)}
        onLanguageChange={(tag) => void changeLanguage(tag)}
        languageError={languageError}
        answerStyle={toAnswerStyle(meeting?.answerStyle)}
        onAnswerStyleChange={(style) => void changeAnswerStyle(style)}
        role={meeting?.userInterviewRole ?? 'CANDIDATE'}
        onRoleChange={(r) => void updateInterviewRole(r)}
        connected={wsConnected}
        interviewMode={!!interviewMode}
        onToggleInterviewMode={toggleInterviewMode}
        computerAudioOn={tabCaptureOn}
        onToggleComputerAudio={() => void (tabCaptureOn ? capture.stopTab() : capture.startTab())}
        manualPending={manualPending}
        onQuestionFinished={() => void answerLatestQuestion()}
        analyzing={analyzing}
        onConsolidate={() => void triggerAiAnalysis()}
        uploading={uploading}
        onUpload={() => fileInputRef.current?.click()}
        micMode={capture.micMode}
        onMicModeChange={(mode) => void capture.setMicMode(mode)}
        isRecording={isRecording}
        elapsed={formatTime(recordingSeconds)}
        onToggleRecording={() => void toggleRecording()}
      />

      {/* Grid Principal: 3 ÁREAS */}
      <div className="flex-1 grid grid-cols-12 grid-rows-[minmax(0,1fr)] min-h-0 h-full overflow-hidden divide-x divide-[#e0e3e7]">
        {/* ÁREA 1: TRANSCRIÇÃO AO VIVO (5 Colunas) */}
        <section className={`${interviewMode ? 'col-span-4' : 'col-span-5'} flex flex-col h-full max-h-full min-h-0 overflow-hidden bg-white`}>
          <div className="p-3.5 border-b border-[#e0e3e7] flex items-center justify-between gap-3 shrink-0 bg-[#f8fafd]">
            <div className="flex items-center gap-2">
              <Volume2 className="w-4 h-4 text-[#0b57d0]" />
              <span className="text-sm font-medium text-[#1f1f1f]">Transcrição ao Vivo</span>
              <span className="text-[11px] text-[#041e49] bg-[#d3e3fd] px-2 py-0.5 rounded-full font-medium">
                {transcripts.length} falas
              </span>
            </div>
            <div className="relative w-44">
              <Search className="w-3.5 h-3.5 text-[#444746] absolute left-3 top-1/2 -translate-y-1/2" />
              <input
                type="text"
                placeholder="Buscar fala..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pl-8 pr-3 py-1 bg-white border border-[#c4c7c5] rounded-full text-xs text-[#1f1f1f] placeholder-[#747775] focus:outline-none focus:border-[#0b57d0]"
              />
            </div>
          </div>

          {/* Estado de cada fonte de áudio (spec 001, RF-04/RF-11) */}
          {!interviewMode && capture.micMode !== 'OFF' && (isRecording || capture.mic.state !== 'INATIVA') && <SourceStatusBadge kind="MIC" view={capture.mic} />}
          {tabCaptureOn && (
            <SourceStatusBadge
              kind="TAB"
              view={capture.tab}
              onRetry={() => capture.startTab()}
              retryLabel="Compartilhar de novo"
            />
          )}
          {/* Spec 009 RF-05: a tela inteira captura todo o som do PC */}
          {capture.tab.surface === 'monitor' && tabCaptureOn && !systemAudioNoticeDismissed && (
            <div role="status" data-testid="system-audio-notice" className="px-3.5 py-1.5 border-b border-[#d3e3fd] bg-[#e8f0fe] text-[11px] text-[#041e49] flex items-center justify-between gap-2 shrink-0">
              <span>
                Todo o som do computador está sendo capturado: notificações, músicas e vídeos também serão transcritos.
              </span>
              <button type="button" onClick={() => setSystemAudioNoticeDismissed(true)} className="font-semibold cursor-pointer">
                OK
              </button>
            </div>
          )}

          {capture.overlapWarning && (
            <div role="status" className="px-3.5 py-1.5 border-b border-[#fde293] bg-[#fef7e0] text-[11px] text-[#5f4300] flex items-center justify-between gap-2 shrink-0">
              <span>
                Seu microfone está captando a voz da reunião ao mesmo tempo que a aba. Use fone de ouvido para evitar transcrição duplicada.
              </span>
              <button type="button" onClick={capture.dismissOverlapWarning} className="font-semibold cursor-pointer">
                OK
              </button>
            </div>
          )}

          <div ref={transcriptContainerRef} className="flex-1 min-h-0 p-4 overflow-y-auto space-y-3">
            {initialLoading ? (
              <div className="space-y-3" aria-busy="true" data-testid="transcript-skeleton">
                {[0, 1, 2].map((k) => (
                  <div key={k} className="p-3.5 rounded-2xl border border-[#e0e3e7] bg-[#f8fafd] space-y-2">
                    <div className="h-2.5 w-1/3 rounded bg-[#e8eaed] motion-safe:animate-pulse" />
                    <div className="h-2.5 w-11/12 rounded bg-[#e8eaed] motion-safe:animate-pulse" />
                    <div className="h-2.5 w-8/12 rounded bg-[#e8eaed] motion-safe:animate-pulse" />
                  </div>
                ))}
              </div>
            ) : filteredTranscripts.length === 0 ? (
              <div className="text-center py-12 text-[#444746] text-xs">
                Nenhum áudio transcrito ainda. Inicie a captura ou envie um arquivo de mídia para começar.
              </div>
            ) : (
              <>
              {interviewMode && !searchTerm && filteredTranscripts.length > 3 && (
                <button
                  type="button"
                  onClick={() => setTranscriptExpanded((v) => !v)}
                  className="w-full text-[11px] font-medium text-[#0b57d0] hover:underline cursor-pointer"
                >
                  {transcriptExpanded ? 'Mostrar só as últimas falas' : `Mostrar todas as ${filteredTranscripts.length} falas`}
                </button>
              )}
              {visibleTranscripts.map((segment) => (
                <TranscriptItem
                  key={segment.id}
                  segment={segment}
                  highlighted={highlightedTimeMs === segment.startTimeMs}
                  isEditing={editingSegmentId === segment.id}
                  editText={editingSegmentId === segment.id ? editText : ''}
                  actions={transcriptActions}
                />
              ))}
              </>
            )}
            {/* Spec 008 RF-18: o VAD da aba tem um trecho de fala aberto (a transcrição chega quando ele fechar) */}
            {capture.tab.speaking && (
              <div className="mi-enter px-3.5 py-2 rounded-2xl border border-dashed border-[#c4c7c5] bg-[#f8fafd] flex items-center gap-2" role="status" data-testid="remote-speaking">
                <MonitorSpeaker className="w-3.5 h-3.5 text-[#444746]" />
                <span className="text-[11px] font-medium text-[#444746]">Participante remoto falando…</span>
                <span className="ml-auto h-1.5 w-16 rounded-full bg-[#e0e3e7] overflow-hidden" aria-hidden="true">
                  <span className="block h-full bg-[#0b57d0] transition-[width] duration-100" style={{ width: `${capture.tab.level}%` }} />
                </span>
              </div>
            )}
            {/* Spec 008 RF-17: texto provisório do microfone, só na tela (nunca enviado como segmento) */}
            {interimTranscript && (
              <div className="mi-enter p-3.5 rounded-2xl border border-dashed border-[#a8c7fa] bg-[#e8f0fe]/40 space-y-1" data-testid="mic-interim">
                <span className="text-[11px] font-semibold text-[#0b57d0] flex items-center gap-1.5">
                  <Mic className="w-3 h-3" /> Você (falando…)
                </span>
                <p className="text-xs text-[#5f6368] italic">{interimTranscript}</p>
              </div>
            )}
            <div ref={transcriptEndRef} />
          </div>
          {newTranscriptCount > 0 && (
            <div className="relative shrink-0">
              <button
                type="button"
                onClick={scrollTranscriptToBottom}
                className="absolute -top-11 left-1/2 -translate-x-1/2 px-3 py-1.5 rounded-full bg-[#0b57d0] text-white text-[11px] font-medium shadow-md cursor-pointer"
                data-testid="new-transcripts"
              >
                {newTranscriptCount === 1 ? '1 nova fala ↓' : `${newTranscriptCount} novas falas ↓`}
              </button>
            </div>
          )}

          {/* Barra inferior para ditar ou simular falas na reunião e enviar para a IA */}
          <form
            onSubmit={handleManualSpeechSubmit}
            className="p-3 border-t border-[#e0e3e7] bg-[#f8fafd] space-y-2 shrink-0"
          >
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-1.5 text-[11px] text-[#444746]">
                <span>Locutor:</span>
                <select
                  value={speakerRole}
                  onChange={(e) => changeSpeakerRole(e.target.value)}
                  className="bg-white border border-[#c4c7c5] rounded-full px-2.5 py-0.5 text-[11px] font-medium text-[#1f1f1f] focus:outline-none focus:border-[#0b57d0]"
                >
                  {speakerOptions.map((o) => (
                    <option key={o.label} value={o.label}>
                      {o.role === 'SELF' ? `${o.label} (Você)` : o.label}
                    </option>
                  ))}
                </select>
              </div>
              <span className="text-[10px] text-[#444746]">
                Envia para transcrição + análise automática de IA
              </span>
            </div>
            <div className="flex items-center gap-2">
              <input
                type="text"
                value={manualSpeechInput}
                onChange={(e) => setManualSpeechInput(e.target.value)}
                placeholder="Digite ou fale no microfone para testar a análise da IA em tempo real..."
                className="flex-1 px-3.5 py-2 bg-white border border-[#c4c7c5] rounded-full text-xs text-[#1f1f1f] placeholder-[#747775] focus:outline-none focus:border-[#0b57d0]"
              />
              <button
                type="submit"
                disabled={!manualSpeechInput.trim()}
                className="px-3.5 py-2 rounded-full bg-[#0b57d0] hover:bg-[#0842a0] disabled:opacity-40 text-white text-xs font-medium flex items-center gap-1.5 transition-colors cursor-pointer"
              >
                <Send className="w-3.5 h-3.5" />
                Registrar Fala
              </button>
            </div>
          </form>
        </section>

        {interviewMode ? (
          <section className="col-span-8 h-full max-h-full min-h-0 overflow-hidden bg-[#f8fafd]/60">
            <InterviewModeLayout
              questions={questions}
              insights={insights}
              drafts={copilotDrafts}
              formatMs={formatMs}
              onJump={jumpToTimestamp}
              onRegenerate={regenerateAnswer}
              onDismiss={dismissQuestion}
                        onTranslate={translateAnswer}
              onAnswerLatest={answerLatestQuestion}
              manualPending={manualPending}
            />
          </section>
        ) : (
        <>
        {/* ÁREA 2: AI INSIGHTS AUTOMÁTICOS (4 Colunas) */}
        <section className="col-span-4 flex flex-col h-full max-h-full min-h-0 overflow-hidden bg-[#f8fafd]/60">
          <div className="p-3 border-b border-[#e0e3e7] flex items-center justify-between shrink-0 bg-[#f8fafd]">
            <div className="flex items-center gap-1.5">
              <Sparkles className="w-4 h-4 text-[#0b57d0]" />
              <span className="text-sm font-medium text-[#1f1f1f]">Insights da IA</span>
            </div>

            <div className="flex items-center gap-1 bg-[#e8eef7] p-1 rounded-full text-xs">
              <button
                onClick={() => setActiveTab('insights')}
                className={`px-2.5 py-1 rounded-full font-medium transition-colors ${
                  activeTab === 'insights' ? 'bg-white text-[#0b57d0] shadow-xs' : 'text-[#444746] hover:text-[#1f1f1f]'
                }`}
              >
                Geral ({insights.length})
              </button>
              <button
                onClick={() => setActiveTab('summary')}
                className={`px-2.5 py-1 rounded-full font-medium transition-colors ${
                  activeTab === 'summary' ? 'bg-white text-[#0b57d0] shadow-xs' : 'text-[#444746] hover:text-[#1f1f1f]'
                }`}
              >
                Resumo
              </button>
              <button
                onClick={() => setActiveTab('tasks')}
                className={`px-2.5 py-1 rounded-full font-medium transition-colors ${
                  activeTab === 'tasks' ? 'bg-white text-[#0b57d0] shadow-xs' : 'text-[#444746] hover:text-[#1f1f1f]'
                }`}
              >
                Tarefas ({taskInsights.length})
              </button>
              <button
                onClick={() => setActiveTab('decisions')}
                className={`px-2.5 py-1 rounded-full font-medium transition-colors ${
                  activeTab === 'decisions' ? 'bg-white text-[#0b57d0] shadow-xs' : 'text-[#444746] hover:text-[#1f1f1f]'
                }`}
              >
                Decisões ({decisionInsights.length})
              </button>
            </div>
          </div>

          <div className="flex-1 min-h-0 p-4 overflow-y-auto space-y-3">
            {activeTab === 'insights' && (
              <>
                {insights.length === 0 && pendingQuestions.length === 0 ? (
                  <div className="text-center py-12 text-[#444746] text-xs">
                    Nenhum insight gerado ainda. A IA analisa as falas a cada bloco semântico.
                  </div>
                ) : (
                  <>
                  {pendingQuestions.map((q) => (
                      <InterviewAnswerCard
                        key={`q-${q.id}`}
                        question={q}
                        draft={copilotDrafts[q.id]}
                        formatMs={formatMs}
                        onJump={jumpToTimestamp}
                        onRegenerate={regenerateAnswer}
                        onDismiss={dismissQuestion}
                        onTranslate={translateAnswer}
                      />
                    ))}
                  {insights.map((ins) => ins.category === 'QUESTION' ? (
                    <InterviewAnswerCard
                      key={ins.id}
                      insight={ins}
                      question={questions.find((q) => q.id === ins.questionId)}
                      draft={ins.questionId ? copilotDrafts[ins.questionId] : undefined}
                      formatMs={formatMs}
                      onJump={jumpToTimestamp}
                      onRegenerate={regenerateAnswer}
                      onDismiss={dismissQuestion}
                        onTranslate={translateAnswer}
                    />
                  ) : (
                    <div
                      key={ins.id}
                      className="p-4 rounded-2xl border space-y-1.5 shadow-2xs bg-white border-[#e0e3e7]"
                    >
                      <div className="flex items-center justify-between">
                        <span
                          className={`text-[10px] font-semibold uppercase tracking-wider px-2.5 py-0.5 rounded-full ${
                            ins.category === 'DECISION'
                              ? 'bg-[#e6f4ea] text-[#137333]'
                              : ins.category === 'TASK'
                              ? 'bg-[#e8f0fe] text-[#1967d2]'
                              : 'bg-[#f3e8fd] text-[#6b4ea2]'
                          }`}
                        >
                          {ins.category}
                        </span>
                        {ins.startTimeMs && (
                          <button
                            onClick={() => jumpToTimestamp(ins.startTimeMs!)}
                            className="text-[11px] font-mono text-[#0b57d0] hover:underline flex items-center gap-0.5"
                          >
                            {formatMs(ins.startTimeMs)}
                            <ChevronRight className="w-3 h-3" />
                          </button>
                        )}
                      </div>
                      <h3 className="text-xs font-semibold text-[#1f1f1f]">{ins.title}</h3>
                      <p className="text-xs text-[#1f1f1f] leading-relaxed whitespace-pre-line">{ins.description}</p>
                      {ins.speakerReference && (
                        <p className="text-[11px] text-[#747775]">Por: {ins.speakerReference}</p>
                      )}
                    </div>
                  ))}
                  </>
                )}
              </>
            )}

            {activeTab === 'summary' && (
              <div className="space-y-4">
                <div className="p-4 rounded-2xl bg-white border border-[#e0e3e7] space-y-2">
                  <div className="flex items-center justify-between">
                    <span className="text-[10px] font-semibold text-[#0b57d0] uppercase tracking-wider block">
                      Resumo Dinâmico Contínuo (v{summary?.version || 1})
                    </span>
                    <span className="text-[10px] text-[#747775]">Atualizado via IA</span>
                  </div>
                  <p className="text-xs text-[#1f1f1f] leading-relaxed">
                    {summary?.executiveSummary || 'Acompanhando a reunião para consolidar o resumo executivo...'}
                  </p>
                </div>
              </div>
            )}

            {activeTab === 'tasks' && (
              <div className="space-y-3">
                {taskInsights.length === 0 ? (
                  <div className="text-center py-12 text-[#444746] text-xs">Nenhuma tarefa detectada ainda.</div>
                ) : (
                  taskInsights.map((t) => (
                    <div key={t.id} className="p-4 rounded-2xl bg-white border border-[#d3e3fd] space-y-1">
                      <div className="flex items-center justify-between text-xs">
                        <span className="font-semibold text-[#0b57d0] flex items-center gap-1.5">
                          <CheckSquare className="w-3.5 h-3.5" /> Tarefa
                        </span>
                        {t.speakerReference && (
                          <span className="text-[11px] text-[#444746]">Atribuído: {t.speakerReference}</span>
                        )}
                      </div>
                      <p className="text-xs text-[#1f1f1f]">{t.description || t.title}</p>
                    </div>
                  ))
                )}
              </div>
            )}

            {activeTab === 'decisions' && (
              <div className="space-y-3">
                {decisionInsights.length === 0 ? (
                  <div className="text-center py-12 text-[#444746] text-xs">Nenhuma decisão registrada ainda.</div>
                ) : (
                  decisionInsights.map((d) => (
                    <div key={d.id} className="p-4 rounded-2xl bg-white border border-[#c4eed0] space-y-1">
                      <div className="flex items-center justify-between text-xs">
                        <span className="font-semibold text-[#146c2e] flex items-center gap-1.5">
                          <Award className="w-3.5 h-3.5" /> Decisão Tomada
                        </span>
                        {d.startTimeMs && (
                          <button
                            onClick={() => jumpToTimestamp(d.startTimeMs!)}
                            className="text-[10px] font-mono text-[#0b57d0] hover:underline"
                          >
                            {formatMs(d.startTimeMs)}
                          </button>
                        )}
                      </div>
                      <h4 className="text-xs font-semibold text-[#1f1f1f]">{d.title}</h4>
                      <p className="text-xs text-[#444746]">{d.description}</p>
                    </div>
                  ))
                )}
              </div>
            )}
          </div>
        </section>

        {/* ÁREA 3: CHAT CONTEXTUAL COM IA (RAG) (3 Colunas) */}
        <section className="col-span-3 flex flex-col h-full max-h-full min-h-0 overflow-hidden bg-white">
          <div className="p-3.5 border-b border-[#e0e3e7] flex items-center justify-between shrink-0 bg-[#f8fafd]">
            <div className="flex items-center gap-2">
              <MessageSquare className="w-4 h-4 text-[#0b57d0]" />
              <span className="text-sm font-medium text-[#1f1f1f]">Chat RAG da Reunião</span>
            </div>
            <span className="text-[10px] bg-[#e8f0fe] text-[#0b57d0] px-2 py-0.5 rounded-full font-medium">
              pgvector
            </span>
          </div>

          {/* Histórico do Chat */}
          <div ref={chatContainerRef} className="flex-1 min-h-0 p-3.5 overflow-y-auto space-y-3">
            {chatMessages.map((msg) => (
              <div key={msg.id} className={`flex flex-col ${msg.senderType === 'USER' ? 'items-end' : 'items-start'}`}>
                <div
                  className={`p-3 rounded-2xl max-w-[90%] text-xs leading-relaxed ${
                    msg.senderType === 'USER'
                      ? 'bg-[#0b57d0] text-white rounded-br-xs'
                      : 'bg-[#f0f4f9] text-[#1f1f1f] border border-[#e0e3e7] rounded-bl-xs'
                  }`}
                >
                  <p className="whitespace-pre-line">{msg.content}</p>

                  {msg.citations && msg.citations.length > 0 && (
                    <div className="mt-2 pt-2 border-t border-[#c4c7c5]/50 space-y-1">
                      <span className="text-[10px] text-[#0b57d0] font-semibold block uppercase">
                        Referências Verificáveis:
                      </span>
                      {msg.citations.map((c, idx) => (
                        <div
                          key={idx}
                          onClick={() => jumpToTimestamp(c.startTimeMs)}
                          className="text-[11px] text-[#1f1f1f] bg-white hover:bg-[#e8f0fe] p-1.5 rounded-lg border border-[#e0e3e7] flex items-center justify-between cursor-pointer group transition-colors"
                        >
                          <span className="truncate italic">"{c.textSnippet}"</span>
                          <span className="font-mono text-[#0b57d0] ml-1 shrink-0 group-hover:underline">
                            {formatMs(c.startTimeMs)}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
                <span className="text-[9px] text-[#747775] mt-1 px-1">
                  {new Date(msg.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                </span>
              </div>
            ))}

            {chatLoading && (
              <div className="text-xs text-[#0b57d0] flex items-center gap-2">
                <Sparkles className="w-3.5 h-3.5 animate-spin" /> Consultando transcrição no pgvector...
              </div>
            )}
            <div ref={chatEndRef} />
          </div>

          {/* Perguntas Rápidas Sugeridas */}
          <div className="p-2 border-t border-[#e0e3e7] bg-[#f8fafd] flex items-center gap-1.5 overflow-x-auto text-[11px] shrink-0">
            <button
              onClick={() => setChatInput('Quais decisões foram tomadas nesta reunião?')}
              className="px-2.5 py-1 bg-white hover:bg-[#e8f0fe] border border-[#c4c7c5] rounded-full text-[#444746] whitespace-nowrap transition-colors"
            >
              Decisões?
            </button>
            <button
              onClick={() => setChatInput('Quais tarefas e prazos foram atribuídos?')}
              className="px-2.5 py-1 bg-white hover:bg-[#e8f0fe] border border-[#c4c7c5] rounded-full text-[#444746] whitespace-nowrap transition-colors"
            >
              Tarefas?
            </button>
            <button
              onClick={() => setChatInput('Resuma os tópicos principais discutidos.')}
              className="px-2.5 py-1 bg-white hover:bg-[#e8f0fe] border border-[#c4c7c5] rounded-full text-[#444746] whitespace-nowrap transition-colors"
            >
              Resumo?
            </button>
          </div>

          {/* Input do Chat */}
          <form onSubmit={handleSendMessage} className="p-3 border-t border-[#e0e3e7] flex gap-2 shrink-0 bg-white">
            <input
              type="text"
              placeholder="Pergunte sobre a reunião..."
              value={chatInput}
              onChange={(e) => setChatInput(e.target.value)}
              className="flex-1 px-3.5 py-2 bg-[#f0f4f9] border border-transparent rounded-full text-xs text-[#1f1f1f] placeholder-[#747775] focus:outline-none focus:bg-white focus:border-[#0b57d0]"
            />
            <button
              type="submit"
              disabled={!chatInput.trim() || chatLoading}
              className="w-9 h-9 bg-[#0b57d0] hover:bg-[#0842a0] text-white rounded-full flex items-center justify-center transition-colors disabled:opacity-40 shrink-0"
            >
              <Send className="w-4 h-4" />
            </button>
          </form>
        </section>
        </>
        )}
      </div>
    </div>
  );
};
