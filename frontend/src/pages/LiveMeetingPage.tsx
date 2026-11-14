import React, { useState, useEffect, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Mic,
  Square,
  Search,
  Upload,
  Sparkles,
  MessageSquare,
  Volume2,
  Users,
  Check,
  Edit2,
  ShieldAlert,
  Send,
  Wifi,
  WifiOff,
  CheckSquare,
  Award,
  RefreshCw,
  ChevronRight,
  ArrowLeft
} from 'lucide-react';
import { api } from '../services/api';
import { MeetingWebSocketService } from '../services/websocket';
import { Meeting, TranscriptSegment, AIInsight, ChatMessage, MeetingSummary } from '../types';

export const LiveMeetingPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [meeting, setMeeting] = useState<Meeting | null>(null);
  const [isRecording, setIsRecording] = useState(false);
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
  const [summary, setSummary] = useState<MeetingSummary | null>(null);
  const [chatMessages, setChatMessages] = useState<ChatMessage[]>([]);
  const [chatInput, setChatInput] = useState('');
  const [chatLoading, setChatLoading] = useState(false);

  // Estados de captura de voz ao vivo (Web Speech API) e envio direto de falas
  const [interimTranscript, setInterimTranscript] = useState('');
  const [manualSpeechInput, setManualSpeechInput] = useState('');
  const [speakerRole, setSpeakerRole] = useState('Francisco Araújo');
  const recognitionRef = useRef<any>(null);

  // Tabs do Painel Central
  const [activeTab, setActiveTab] = useState<'insights' | 'summary' | 'tasks' | 'decisions'>('insights');

  const timerRef = useRef<number | null>(null);
  const transcriptEndRef = useRef<HTMLDivElement | null>(null);
  const chatEndRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    if (id) {
      loadMeetingData(id);

      const wsService = new MeetingWebSocketService(
        id,
        (newSegment) => {
          setTranscripts((prev) => {
            if (prev.some((s) => s.id === newSegment.id)) return prev;
            return [...prev, newSegment];
          });
        },
        (newInsight) => {
          setInsights((prev) => {
            if (prev.some((i) => i.id === newInsight.id)) return prev;
            return [newInsight, ...prev];
          });
        },
        (newSummary) => {
          setSummary(newSummary);
        },
        (connected) => setWsConnected(connected)
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
        setRecordingSeconds((prev) => prev + 1);
      }, 1000);
    } else if (timerRef.current) {
      clearInterval(timerRef.current);
    }
  }, [isRecording]);

  useEffect(() => {
    transcriptEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [transcripts]);

  useEffect(() => {
    chatEndRef.current?.scrollIntoView({ behavior: 'smooth' });
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
    }
  };

  const sendLiveSpeechSegment = async (text: string, speaker = speakerRole) => {
    if (!id || !text.trim()) return;
    try {
      const startMs = recordingSeconds * 1000;
      const res = await api.post(`/meetings/${id}/transcripts/live-text`, {
        speakerLabel: speaker,
        text: text.trim(),
        startTimeMs: startMs,
        endTimeMs: startMs + 4000,
      });
      if (res.data) {
        setTranscripts((prev) => {
          if (prev.some((s) => s.id === res.data.id)) return prev;
          return [...prev, res.data];
        });
      }
    } catch (e) {
      console.error('Erro ao registrar fala ao vivo:', e);
    }
  };

  const handleManualSpeechSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!manualSpeechInput.trim()) return;
    const text = manualSpeechInput;
    setManualSpeechInput('');
    await sendLiveSpeechSegment(text, speakerRole);
  };

  const startBrowserSpeechRecognition = () => {
    const SpeechRecognition =
      (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) return;

    try {
      const recognition = new SpeechRecognition();
      recognition.lang = meeting?.language || 'pt-BR';
      recognition.continuous = true;
      recognition.interimResults = true;

      recognition.onresult = (event: any) => {
        let interim = '';
        for (let i = event.resultIndex; i < event.results.length; ++i) {
          const transcriptPiece = event.results[i][0].transcript;
          if (event.results[i].isFinal) {
            setInterimTranscript('');
            sendLiveSpeechSegment(transcriptPiece, 'Microfone ao Vivo');
          } else {
            interim += transcriptPiece;
          }
        }
        if (interim) setInterimTranscript(interim);
      };

      recognition.onerror = () => {};
      recognition.start();
      recognitionRef.current = recognition;
    } catch (err) {
      console.warn('Reconhecimento de voz nativo não iniciado:', err);
    }
  };

  const toggleRecording = async () => {
    if (!isRecording) {
      try {
        await api.patch(`/meetings/${id}/status?status=IN_PROGRESS`).catch(() => {});
        setIsRecording(true);
        startBrowserSpeechRecognition();

        if (navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia) {
          const stream = await navigator.mediaDevices.getDisplayMedia({ video: true, audio: true });
          stream.getVideoTracks()[0].onended = () => stopRecording();
        }
      } catch (err) {
        console.warn('Captura de tela ignorada; mantendo escuta de microfone ativa:', err);
        setIsRecording(true);
      }
    } else {
      stopRecording();
    }
  };

  const stopRecording = async () => {
    setIsRecording(false);
    setInterimTranscript('');
    if (recognitionRef.current) {
      try {
        recognitionRef.current.stop();
      } catch (e) {}
      recognitionRef.current = null;
    }
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

  const filteredTranscripts = transcripts.filter((t) => {
    const text = t.correctedText || t.originalText;
    return text.toLowerCase().includes(searchTerm.toLowerCase());
  });

  const taskInsights = insights.filter((i) => i.category === 'TASK');
  const decisionInsights = insights.filter((i) => i.category === 'DECISION');

  return (
    <div className="flex flex-col h-full bg-white overflow-hidden">
      {/* Banner LGPD Material 3 */}
      <div className="bg-[#e8f0fe] border-b border-[#d3e3fd] px-6 py-1.5 flex items-center justify-between text-xs text-[#041e49]">
        <div className="flex items-center gap-2">
          <ShieldAlert className="w-3.5 h-3.5 text-[#0b57d0]" />
          <span>
            <strong>Aviso de Consentimento:</strong> Gravação e análise de IA em tempo real em conformidade com a LGPD.
          </span>
        </div>
        <div className="flex items-center gap-3">
          <span className="flex items-center gap-1.5 text-[11px] font-medium">
            {wsConnected ? (
              <span className="text-[#146c2e] flex items-center gap-1">
                <Wifi className="w-3 h-3" /> Sincronização em Tempo Real Ativa
              </span>
            ) : (
              <span className="text-[#444746] flex items-center gap-1">
                <WifiOff className="w-3 h-3" /> Offline
              </span>
            )}
          </span>
        </div>
      </div>

      {/* Top Header Google Meet Style */}
      <header className="h-16 px-6 border-b border-[#e0e3e7] bg-white flex items-center justify-between shrink-0">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/meetings')}
            className="p-2 rounded-full hover:bg-[#f0f4f9] text-[#444746] transition-colors"
            title="Voltar para reuniões"
          >
            <ArrowLeft className="w-4 h-4" />
          </button>
          <div>
            <h1 className="text-base font-medium text-[#1f1f1f] truncate max-w-md">
              {meeting?.title || 'Sala de Reunião'}
            </h1>
            <p className="text-xs text-[#444746] flex items-center gap-2">
              <span>{meeting?.captureMechanism}</span>
              <span>•</span>
              <span className="text-[#0b57d0] font-medium">{meeting?.language}</span>
            </p>
          </div>
        </div>

        <div className="flex items-center gap-2.5">
          <input type="file" ref={fileInputRef} onChange={handleFileUpload} accept="audio/*,video/*" className="hidden" />
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            className="md-btn-outlined !py-1.5 !px-3.5 !text-xs"
          >
            <Upload className="w-3.5 h-3.5" />
            <span>{uploading ? 'Enviando mídia...' : 'Upload de Mídia'}</span>
          </button>

          <button
            onClick={triggerAiAnalysis}
            disabled={analyzing}
            className="md-btn-tonal !py-1.5 !px-3.5 !text-xs"
          >
            <RefreshCw className={`w-3.5 h-3.5 ${analyzing ? 'animate-spin' : ''}`} />
            <span>{analyzing ? 'Consolidando...' : 'Consolidar com IA'}</span>
          </button>

          <div className="flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#f8fafd] border border-[#e0e3e7]">
            <span className={`w-2.5 h-2.5 rounded-full ${isRecording ? 'bg-[#b3261e] animate-ping' : 'bg-[#747775]'}`} />
            <span className="text-xs font-mono font-medium text-[#1f1f1f]">{formatTime(recordingSeconds)}</span>
            <span className="text-[10px] uppercase font-semibold text-[#444746]">
              {isRecording ? 'Gravando' : 'Pausado'}
            </span>
          </div>

          <button
            onClick={toggleRecording}
            className={`flex items-center gap-2 px-4 py-2 rounded-full text-xs font-medium shadow-sm transition-all ${
              isRecording
                ? 'bg-[#b3261e] hover:bg-[#8c1d18] text-white'
                : 'bg-[#0b57d0] hover:bg-[#0842a0] text-white'
            }`}
          >
            {isRecording ? (
              <>
                <Square className="w-3.5 h-3.5 fill-white" /> Encerrar Captura
              </>
            ) : (
              <>
                <Mic className="w-3.5 h-3.5" /> Iniciar Captura
              </>
            )}
          </button>
        </div>
      </header>

      {/* Grid Principal: 3 ÁREAS */}
      <div className="flex-1 grid grid-cols-12 min-h-0 divide-x divide-[#e0e3e7]">
        {/* ÁREA 1: TRANSCRIÇÃO AO VIVO (5 Colunas) */}
        <section className="col-span-5 flex flex-col min-h-0 bg-white">
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

          <div className="flex-1 p-4 overflow-y-auto space-y-3">
            {filteredTranscripts.length === 0 ? (
              <div className="text-center py-12 text-[#444746] text-xs">
                Nenhum áudio transcrito ainda. Inicie a captura ou envie um arquivo de mídia para começar.
              </div>
            ) : (
              filteredTranscripts.map((segment) => (
                <div
                  key={segment.id}
                  id={`segment-${segment.startTimeMs}`}
                  className={`p-3.5 rounded-2xl border transition-all space-y-1.5 group ${
                    highlightedTimeMs === segment.startTimeMs
                      ? 'bg-[#e8f0fe] border-[#0b57d0] shadow-sm'
                      : 'bg-[#f8fafd] border-[#e0e3e7] hover:border-[#c4c7c5]'
                  }`}
                >
                  <div className="flex items-center justify-between text-xs">
                    <span className="font-semibold text-[#0b57d0] flex items-center gap-1.5">
                      <Users className="w-3.5 h-3.5" />
                      {segment.speakerLabel || 'Participante'}
                    </span>
                    <div className="flex items-center gap-2">
                      <span className="text-[11px] font-mono text-[#444746]">{formatMs(segment.startTimeMs)}</span>
                      {editingSegmentId !== segment.id && (
                        <button
                          onClick={() => {
                            setEditingSegmentId(segment.id);
                            setEditText(segment.correctedText || segment.originalText);
                          }}
                          className="opacity-0 group-hover:opacity-100 p-1 text-[#444746] hover:text-[#0b57d0] transition-opacity"
                        >
                          <Edit2 className="w-3 h-3" />
                        </button>
                      )}
                    </div>
                  </div>

                  {editingSegmentId === segment.id ? (
                    <div className="mt-2 space-y-2">
                      <textarea
                        value={editText}
                        onChange={(e) => setEditText(e.target.value)}
                        className="md-input bg-white text-xs"
                        rows={2}
                      />
                      <div className="flex justify-end gap-2">
                        <button
                          onClick={() => setEditingSegmentId(null)}
                          className="px-2.5 py-1 text-xs text-[#444746] hover:bg-[#e0e3e7] rounded-full"
                        >
                          Cancelar
                        </button>
                        <button
                          onClick={() => saveCorrection(segment.id)}
                          className="px-3 py-1 text-xs bg-[#0b57d0] text-white font-medium rounded-full flex items-center gap-1"
                        >
                          <Check className="w-3 h-3" /> Salvar
                        </button>
                      </div>
                    </div>
                  ) : (
                    <div>
                      <p className="text-sm text-[#1f1f1f] leading-relaxed">
                        {segment.correctedText || segment.originalText}
                      </p>
                      {segment.correctedText && (
                        <p className="text-[10px] text-[#747775] italic mt-0.5">Original: "{segment.originalText}"</p>
                      )}
                    </div>
                  )}
                </div>
              ))
            )}
            {interimTranscript && (
              <div className="p-3.5 rounded-2xl border border-dashed border-[#0b57d0] bg-[#e8f0fe]/60 space-y-1 animate-pulse">
                <span className="text-[11px] font-semibold text-[#0b57d0] flex items-center gap-1.5">
                  <Mic className="w-3 h-3" /> Capturando voz do microfone...
                </span>
                <p className="text-xs text-[#1f1f1f] italic">{interimTranscript}</p>
              </div>
            )}
            <div ref={transcriptEndRef} />
          </div>

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
                  onChange={(e) => setSpeakerRole(e.target.value)}
                  className="bg-white border border-[#c4c7c5] rounded-full px-2.5 py-0.5 text-[11px] font-medium text-[#1f1f1f] focus:outline-none focus:border-[#0b57d0]"
                >
                  <option value="Francisco Araújo">Francisco Araújo (Você)</option>
                  <option value="Entrevistador Técnico">Entrevistador Técnico</option>
                  <option value="Participante Convidado">Participante Convidado</option>
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

        {/* ÁREA 2: AI INSIGHTS AUTOMÁTICOS (4 Colunas) */}
        <section className="col-span-4 flex flex-col min-h-0 bg-[#f8fafd]/60">
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

          <div className="flex-1 p-4 overflow-y-auto space-y-3">
            {activeTab === 'insights' && (
              <>
                {insights.length === 0 ? (
                  <div className="text-center py-12 text-[#444746] text-xs">
                    Nenhum insight gerado ainda. A IA analisa as falas a cada bloco semântico.
                  </div>
                ) : (
                  insights.map((ins) => (
                    <div
                      key={ins.id}
                      className="p-4 rounded-2xl bg-white border border-[#e0e3e7] space-y-1.5 shadow-2xs"
                    >
                      <div className="flex items-center justify-between">
                        <span
                          className={`text-[10px] font-semibold uppercase tracking-wider px-2.5 py-0.5 rounded-full ${
                            ins.category === 'DECISION'
                              ? 'bg-[#e6f4ea] text-[#137333]'
                              : ins.category === 'TASK'
                              ? 'bg-[#e8f0fe] text-[#1967d2]'
                              : ins.category === 'QUESTION'
                              ? 'bg-[#fef7e0] text-[#b06000]'
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
                      <p className="text-xs text-[#444746] leading-relaxed">{ins.description}</p>
                      {ins.speakerReference && (
                        <p className="text-[11px] text-[#747775]">Por: {ins.speakerReference}</p>
                      )}
                    </div>
                  ))
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
        <section className="col-span-3 flex flex-col min-h-0 bg-white">
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
          <div className="flex-1 p-3.5 overflow-y-auto space-y-3">
            {chatMessages.map((msg) => (
              <div key={msg.id} className={`flex flex-col ${msg.senderType === 'USER' ? 'items-end' : 'items-start'}`}>
                <div
                  className={`p-3 rounded-2xl max-w-[90%] text-xs leading-relaxed ${
                    msg.senderType === 'USER'
                      ? 'bg-[#0b57d0] text-white rounded-br-xs'
                      : 'bg-[#f0f4f9] text-[#1f1f1f] border border-[#e0e3e7] rounded-bl-xs'
                  }`}
                >
                  <p>{msg.content}</p>

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
      </div>
    </div>
  );
};
