import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Calendar as CalendarIcon,
  Clock,
  Users,
  Video,
  ExternalLink,
  RefreshCw,
  Play,
  CheckCircle2,
  KeyRound,
  AlertCircle,
  ChevronDown,
  ChevronUp,
  Plus,
} from 'lucide-react';
import { api } from '../services/api';
import { CalendarEvent } from '../types';
import { NewMeetingModal } from '../components/modals/NewMeetingModal';

const DEFAULT_GOOGLE_CLIENT_ID =
  '726281906025-91lbpv9q3v65qqv2uf1117d10smgnm1u.apps.googleusercontent.com';

export const CalendarPage: React.FC = () => {
  const [events, setEvents] = useState<CalendarEvent[]>([]);
  const [calendarConnected, setCalendarConnected] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(true);
  const [syncing, setSyncing] = useState<boolean>(false);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [expandedEventId, setExpandedEventId] = useState<string | null>(null);
  const [isModalOpen, setIsModalOpen] = useState<boolean>(false);
  const navigate = useNavigate();

  useEffect(() => {
    loadCalendarSchedule();
  }, []);

  const loadCalendarSchedule = async () => {
    try {
      setLoading(true);
      setErrorMsg(null);
      const [eventsRes, statusRes] = await Promise.allSettled([
        api.get('/calendar/events'),
        api.get('/calendar/status'),
      ]);

      if (eventsRes.status === 'fulfilled') {
        setEvents(eventsRes.value.data || []);
      }
      if (statusRes.status === 'fulfilled') {
        setCalendarConnected(Boolean(statusRes.value.data?.connected));
      }
    } catch (err) {
      setErrorMsg('Não foi possível carregar os compromissos da agenda.');
    } finally {
      setLoading(false);
    }
  };

  const handleOAuthReconnect = () => {
    setErrorMsg(null);
    const clientId =
      localStorage.getItem('google_client_id') ||
      import.meta.env.VITE_GOOGLE_CLIENT_ID ||
      DEFAULT_GOOGLE_CLIENT_ID;

    if (!window.google?.accounts?.oauth2) {
      setErrorMsg('SDK do Google Identity Services indisponível no momento.');
      return;
    }

    const tokenClient = window.google.accounts.oauth2.initTokenClient({
      client_id: clientId,
      scope: 'https://www.googleapis.com/auth/calendar.events.readonly',
      prompt: 'consent',
      callback: async (tokenResponse: any) => {
        if (tokenResponse?.access_token) {
          try {
            setSyncing(true);
            const res = await api.post('/calendar/connect', {
              accessToken: tokenResponse.access_token,
            });
            setEvents(res.data || []);
            setCalendarConnected(true);
          } catch (e) {
            setErrorMsg('Falha ao sincronizar com a Google Calendar API.');
          } finally {
            setSyncing(false);
          }
        }
      },
    });

    tokenClient.requestAccessToken();
  };

  const openMeetingRoom = async (event: CalendarEvent) => {
    try {
      if (event.meetingId) {
        navigate(`/meetings/${event.meetingId}/live`);
        return;
      }

      const payload = {
        title: event.title,
        description: event.description || '',
        googleMeetLink: event.googleMeetLink || '',
        googleCalendarEventId: event.id,
        scheduledStart: event.startTime,
        scheduledEnd: event.endTime,
        captureMechanism: 'BROWSER_CAPTURE',
        language: 'pt-BR',
        participantEmails: event.attendees || [],
      };

      const res = await api.post('/meetings', payload);
      navigate(`/meetings/${res.data.id}/live`);
    } catch (error) {
      console.error('Erro ao abrir sala da reunião:', error);
    }
  };

  // Remove tags HTML da descrição do Google Calendar para exibição limpa
  const stripHtml = (html?: string) => {
    if (!html) return '';
    const doc = new DOMParser().parseFromString(html, 'text/html');
    return doc.body.textContent || '';
  };

  // Agrupa os compromissos futuros por dia (estilo Google Calendar Schedule View)
  const groupedByDate = events.reduce<{ [dateKey: string]: CalendarEvent[] }>((acc, evt) => {
    const d = new Date(evt.startTime);
    const key = d.toLocaleDateString('pt-BR', {
      weekday: 'long',
      day: '2-digit',
      month: 'long',
      year: 'numeric',
    });
    if (!acc[key]) acc[key] = [];
    acc[key].push(evt);
    return acc;
  }, {});

  return (
    <div className="p-8 space-y-6 max-w-6xl mx-auto w-full">
      {/* Top Header exclusivo da Agenda Sincronizada */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-[#e0e3e7]">
        <div>
          <div className="flex items-center gap-2.5">
            <div className="w-9 h-9 rounded-xl bg-[#e8f0fe] text-[#0b57d0] flex items-center justify-center">
              <CalendarIcon className="w-5 h-5" />
            </div>
            <div>
              <h1 className="text-2xl font-normal tracking-tight text-[#1f1f1f]">
                Agenda Sincronizada — Google Calendar
              </h1>
              <p className="text-xs text-[#444746] mt-0.5">
                Cronograma oficial dos seus próximos compromissos, entrevistas e reuniões agendadas.
              </p>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            onClick={handleOAuthReconnect}
            className={`text-xs px-3.5 py-2 rounded-full border font-medium flex items-center gap-1.5 transition-colors cursor-pointer ${
              calendarConnected
                ? 'text-[#146c2e] bg-[#e6f4ea] border-[#c4eed0]'
                : 'text-[#0b57d0] bg-[#edf2fc] border-[#d3e3fd] hover:bg-[#d3e3fd]'
            }`}
          >
            {calendarConnected ? (
              <>
                <CheckCircle2 className="w-3.5 h-3.5" />
                Google Calendar Conectado
              </>
            ) : (
              <>
                <KeyRound className="w-3.5 h-3.5" />
                Conectar Conta Google
              </>
            )}
          </button>

          <button
            onClick={loadCalendarSchedule}
            disabled={loading || syncing}
            className="md-btn-outlined"
          >
            <RefreshCw className={`w-4 h-4 ${loading || syncing ? 'animate-spin' : ''}`} />
            Atualizar Agenda
          </button>

          <button onClick={() => setIsModalOpen(true)} className="md-btn-filled">
            <Plus className="w-4 h-4" />
            Agendar / Vincular
          </button>
        </div>
      </div>

      {errorMsg && (
        <div className="p-4 rounded-2xl bg-[#f9dedc] border border-[#f2b8b5] text-[#b3261e] text-xs flex items-center gap-2.5">
          <AlertCircle className="w-4 h-4 shrink-0" />
          <span>{errorMsg}</span>
        </div>
      )}

      {/* Cronograma por Dia — Estilo Google Calendar Schedule */}
      {loading ? (
        <div className="py-16 text-center text-sm text-[#444746]">
          Sincronizando compromissos futuros com o Google Calendar...
        </div>
      ) : Object.keys(groupedByDate).length === 0 ? (
        <div className="md-card p-12 text-center space-y-3">
          <div className="w-12 h-12 rounded-full bg-[#e8f0fe] text-[#0b57d0] flex items-center justify-center mx-auto">
            <CalendarIcon className="w-6 h-6" />
          </div>
          <h3 className="text-base font-medium text-[#1f1f1f]">
            Nenhum compromisso futuro encontrado na sua agenda
          </h3>
          <p className="text-xs text-[#444746] max-w-md mx-auto">
            Todos os novos eventos e entrevistas adicionados ao seu Google Calendar aparecerão automaticamente aqui.
          </p>
        </div>
      ) : (
        <div className="space-y-8">
          {Object.entries(groupedByDate).map(([dateLabel, dayEvents]) => {
            const firstDate = new Date(dayEvents[0].startTime);
            const dayNum = firstDate.getDate();
            const weekdayShort = firstDate
              .toLocaleDateString('pt-BR', { weekday: 'short' })
              .replace('.', '')
              .toUpperCase();

            return (
              <div key={dateLabel} className="flex flex-col md:flex-row gap-4 md:gap-6 items-start">
                {/* Coluna da Data (Estilo Google Calendar) */}
                <div className="flex md:flex-col items-center md:items-center gap-2 md:w-24 shrink-0 pt-1">
                  <span className="text-[11px] font-semibold text-[#0b57d0] tracking-wider uppercase">
                    {weekdayShort}
                  </span>
                  <div className="w-11 h-11 rounded-full bg-[#0b57d0] text-white flex items-center justify-center text-lg font-medium shadow-xs">
                    {dayNum}
                  </div>
                  <span className="text-xs text-[#444746] capitalize md:hidden">{dateLabel}</span>
                </div>

                {/* Lista de Eventos do Dia */}
                <div className="flex-1 w-full space-y-3">
                  <div className="hidden md:block text-xs font-medium text-[#444746] capitalize pb-1 border-b border-[#e0e3e7]">
                    {dateLabel} ({dayEvents.length}{' '}
                    {dayEvents.length === 1 ? 'compromisso agendado' : 'compromissos agendados'})
                  </div>

                  {dayEvents.map((evt) => {
                    const isExpanded = expandedEventId === evt.id;
                    const cleanDesc = stripHtml(evt.description);

                    return (
                      <div
                        key={evt.id}
                        className="md-card p-5 space-y-3 hover:border-[#0b57d0]/50 transition-all"
                      >
                        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
                          <div className="space-y-1.5 min-w-0">
                            <div className="flex items-center gap-2 flex-wrap">
                              <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-semibold bg-[#e8f0fe] text-[#0b57d0]">
                                <Clock className="w-3.5 h-3.5" />
                                {new Date(evt.startTime).toLocaleTimeString([], {
                                  hour: '2-digit',
                                  minute: '2-digit',
                                })}{' '}
                                –{' '}
                                {new Date(evt.endTime).toLocaleTimeString([], {
                                  hour: '2-digit',
                                  minute: '2-digit',
                                })}
                              </span>

                              {evt.googleMeetLink ? (
                                <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-medium bg-[#e6f4ea] text-[#146c2e]">
                                  <Video className="w-3 h-3" />
                                  Google Meet Ativo
                                </span>
                              ) : (
                                <span className="inline-flex items-center gap-1 px-2.5 py-0.5 rounded-full text-[11px] font-medium bg-[#fef7e0] text-[#b06000]">
                                  Link da chamada a definir
                                </span>
                              )}
                            </div>

                            <h3 className="text-base font-medium text-[#1f1f1f] leading-snug">
                              {evt.title}
                            </h3>

                            {evt.attendees && evt.attendees.length > 0 && (
                              <div className="flex items-center gap-1.5 text-xs text-[#444746] flex-wrap pt-0.5">
                                <Users className="w-3.5 h-3.5 text-[#0b57d0] shrink-0" />
                                <span>Participantes ({evt.attendees.length}):</span>
                                {evt.attendees.map((email, idx) => (
                                  <span
                                    key={idx}
                                    className="px-2 py-0.5 rounded-full bg-[#f0f4f9] text-[#1f1f1f] text-[11px]"
                                  >
                                    {email}
                                  </span>
                                ))}
                              </div>
                            )}
                          </div>

                          <div className="flex items-center gap-2 shrink-0">
                            {evt.googleMeetLink && (
                              <a
                                href={evt.googleMeetLink}
                                target="_blank"
                                rel="noreferrer"
                                className="md-btn-outlined !py-2 !px-3.5 !text-xs"
                              >
                                <span>Abrir Meet</span>
                                <ExternalLink className="w-3.5 h-3.5" />
                              </a>
                            )}

                            <button
                              onClick={() => openMeetingRoom(evt)}
                              className="md-btn-filled !py-2 !px-4 !text-xs"
                            >
                              <Play className="w-3.5 h-3.5 fill-white" />
                              <span>Sala de IA</span>
                            </button>
                          </div>
                        </div>

                        {cleanDesc && (
                          <div className="pt-2 border-t border-[#e0e3e7]">
                            <button
                              onClick={() =>
                                setExpandedEventId(isExpanded ? null : evt.id)
                              }
                              className="text-xs text-[#0b57d0] hover:underline font-medium flex items-center gap-1 cursor-pointer"
                            >
                              {isExpanded ? (
                                <>
                                  <ChevronUp className="w-3.5 h-3.5" /> Ocultar pauta / instruções da entrevista
                                </>
                              ) : (
                                <>
                                  <ChevronDown className="w-3.5 h-3.5" /> Ver pauta / instruções da entrevista
                                </>
                              )}
                            </button>

                            {isExpanded && (
                              <div className="mt-2 p-3.5 rounded-xl bg-[#f8fafd] border border-[#e0e3e7] text-xs text-[#1f1f1f] whitespace-pre-line leading-relaxed">
                                {cleanDesc}
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              </div>
            );
          })}
        </div>
      )}

      <NewMeetingModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onCreated={() => loadCalendarSchedule()}
      />
    </div>
  );
};

