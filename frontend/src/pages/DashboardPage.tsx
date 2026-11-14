import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Video,
  Clock,
  ClipboardCheck,
  Calendar,
  Plus,
  Play,
  ArrowRight,
  ExternalLink,
  RefreshCw,
  KeyRound,
  CheckCircle2,
  Activity
} from 'lucide-react';
import { api } from '../services/api';
import { Meeting, CalendarEvent } from '../types';
import { NewMeetingModal } from '../components/modals/NewMeetingModal';

export const DashboardPage: React.FC = () => {
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [calendarEvents, setCalendarEvents] = useState<CalendarEvent[]>([]);
  const [calendarConnected, setCalendarConnected] = useState<boolean>(false);
  const [metrics, setMetrics] = useState<any>(null);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isConnectCalendarOpen, setIsConnectCalendarOpen] = useState(false);
  const [googleAccessToken, setGoogleAccessToken] = useState('');
  const [connectingCalendar, setConnectingCalendar] = useState(false);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    loadDashboardData();
  }, []);

  const loadDashboardData = async () => {
    try {
      setLoading(true);
      const [meetingsRes, calendarRes, statusRes, metricsRes] = await Promise.allSettled([
        api.get('/meetings?size=10'),
        api.get('/calendar/events'),
        api.get('/calendar/status'),
        api.get('/dashboard/metrics')
      ]);

      if (meetingsRes.status === 'fulfilled') {
        setMeetings(meetingsRes.value.data.content || []);
      }
      if (calendarRes.status === 'fulfilled') {
        setCalendarEvents(calendarRes.value.data || []);
      }
      if (statusRes.status === 'fulfilled') {
        setCalendarConnected(Boolean(statusRes.value.data?.connected));
      }
      if (metricsRes.status === 'fulfilled') {
        setMetrics(metricsRes.value.data);
      }
    } catch (error) {
      console.error('Erro ao carregar dados do dashboard:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleConnectGoogleCalendar = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!googleAccessToken.trim()) return;
    try {
      setConnectingCalendar(true);
      const res = await api.post('/calendar/connect', {
        accessToken: googleAccessToken.trim()
      });
      setCalendarEvents(res.data || []);
      setCalendarConnected(true);
      setGoogleAccessToken('');
      setIsConnectCalendarOpen(false);
    } catch (error) {
      console.error('Erro ao sincronizar Google Calendar:', error);
      alert('Não foi possível sincronizar o Google Calendar. Verifique o Access Token OAuth2.');
    } finally {
      setConnectingCalendar(false);
    }
  };

  const startScheduledMeetingCapture = async (event: CalendarEvent) => {
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
        participantEmails: event.attendees || []
      };

      const res = await api.post('/meetings', payload);
      navigate(`/meetings/${res.data.id}/live`);
    } catch (error) {
      console.error('Erro ao abrir reunião agendada:', error);
    }
  };

  return (
    <div className="p-8 space-y-8 max-w-7xl mx-auto w-full">
      {/* Cabeçalho Corporativo Material Design 3 */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-2 border-b border-[#e0e3e7]">
        <div>
          <h1 className="text-[22px] font-normal tracking-tight text-[#1f1f1f]">
            Visão Geral do Workspace
          </h1>
          <p className="text-sm text-[#444746] mt-0.5">
            Acompanhamento de reuniões agendadas no Google Meet, transcrições auditáveis e atas executivas.
          </p>
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={() => loadDashboardData()}
            disabled={loading}
            className="md-btn-outlined"
          >
            <RefreshCw className={`w-4 h-4 ${loading ? 'animate-spin' : ''}`} />
            Sincronizar Google Meet
          </button>
          <button
            onClick={() => setIsModalOpen(true)}
            className="md-btn-filled"
          >
            <Plus className="w-4 h-4" />
            Vincular Reunião Agendada
          </button>
        </div>
      </div>

      {/* Indicadores Operacionais — Material 3 Outlined Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <div className="md-card p-5">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-[#444746]">Reuniões Agendadas / Gravadas</span>
            <div className="w-9 h-9 rounded-full bg-[#d3e3fd] text-[#041e49] flex items-center justify-center">
              <Video className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="text-2xl font-semibold text-[#1f1f1f]">
              {metrics?.totalMeetings ?? meetings.length}
            </span>
            <span className="text-xs text-[#444746]">
              ({calendarEvents.length} na agenda do Meet)
            </span>
          </div>
        </div>

        <div className="md-card p-5">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-[#444746]">Tempo de Áudio Processado</span>
            <div className="w-9 h-9 rounded-full bg-[#c2e7ff] text-[#001d35] flex items-center justify-center">
              <Clock className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="text-2xl font-semibold text-[#1f1f1f]">
              {metrics?.recordedHours ?? 0}h
            </span>
            <span className="text-xs text-[#444746]">
              {metrics?.transcribedHours ?? 0}h transcritas
            </span>
          </div>
        </div>

        <div className="md-card p-5">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-[#444746]">Deliberações & Tarefas</span>
            <div className="w-9 h-9 rounded-full bg-[#c4eed0] text-[#072711] flex items-center justify-center">
              <ClipboardCheck className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="text-2xl font-semibold text-[#1f1f1f]">
              {metrics?.totalInsightsGenerated ?? 0}
            </span>
            <span className="text-xs text-[#146c2e] font-medium">
              {metrics?.totalDecisions ?? 0} decisões • {metrics?.totalPendingTasks ?? 0} ações
            </span>
          </div>
        </div>

        <div className="md-card p-5">
          <div className="flex items-center justify-between">
            <span className="text-xs font-medium text-[#444746]">Processamento Analítico</span>
            <div className="w-9 h-9 rounded-full bg-[#edf2fc] text-[#0b57d0] flex items-center justify-center">
              <Activity className="w-4 h-4" />
            </div>
          </div>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="text-2xl font-semibold text-[#1f1f1f]">
              ${metrics?.totalCostUsd ?? '0.00'}
            </span>
            <span className="text-xs text-[#444746]">
              {(metrics?.totalTokensConsumed ?? 0).toLocaleString()} tokens
            </span>
          </div>
        </div>
      </div>

      {/* Painéis Principais: Agenda Google Meet & Reuniões Vinculadas */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Coluna 1: Agenda Oficial Google Meet */}
        <div className="md-card p-6 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2.5">
                <Calendar className="w-5 h-5 text-[#0b57d0]" />
                <h2 className="text-base font-medium text-[#1f1f1f]">
                  Agenda Google Calendar (Google Meet)
                </h2>
              </div>
              <button
                onClick={() => setIsConnectCalendarOpen(!isConnectCalendarOpen)}
                className={`text-xs px-3 py-1 rounded-full border font-medium flex items-center gap-1.5 transition-colors cursor-pointer ${
                  calendarConnected
                    ? 'text-[#146c2e] bg-[#e6f4ea] border-[#c4eed0]'
                    : 'text-[#0b57d0] bg-[#edf2fc] border-[#d3e3fd] hover:bg-[#d3e3fd]'
                }`}
              >
                {calendarConnected ? (
                  <>
                    <CheckCircle2 className="w-3.5 h-3.5" />
                    Conectado ao Google
                  </>
                ) : (
                  <>
                    <KeyRound className="w-3.5 h-3.5" />
                    Conectar Google Calendar
                  </>
                )}
              </button>
            </div>
            <p className="text-xs text-[#444746] mb-4">
              Lista exclusivamente reuniões reais agendadas na sua conta com link ativo do Google Meet.
            </p>

            {isConnectCalendarOpen && (
              <form
                onSubmit={handleConnectGoogleCalendar}
                className="mb-4 p-4 rounded-xl bg-[#f8fafd] border border-[#c4c7c5] space-y-3"
              >
                <div className="text-xs font-semibold text-[#1f1f1f]">
                  Sincronizar OAuth 2.0 Access Token (Google Calendar API)
                </div>
                <p className="text-xs text-[#444746]">
                  Informe seu Access Token OAuth 2.0 com escopo <code className="bg-white px-1 py-0.5 rounded border border-[#e0e3e7]">calendar.readonly</code> para carregar apenas os compromissos reais agendados no Google Meet.
                </p>
                <div className="flex gap-2">
                  <input
                    type="password"
                    required
                    placeholder="ya29.a0..."
                    value={googleAccessToken}
                    onChange={(e) => setGoogleAccessToken(e.target.value)}
                    className="md-input flex-1"
                  />
                  <button
                    type="submit"
                    disabled={connectingCalendar}
                    className="md-btn-filled shrink-0"
                  >
                    {connectingCalendar ? 'Sincronizando...' : 'Sincronizar'}
                  </button>
                </div>
              </form>
            )}

            <div className="space-y-2.5">
              {calendarEvents.length === 0 ? (
                <div className="text-center py-10 px-4 border border-dashed border-[#c4c7c5] rounded-2xl bg-[#f8fafd] space-y-2">
                  <p className="text-sm font-medium text-[#1f1f1f]">
                    Nenhuma reunião agendada no Google Meet encontrada
                  </p>
                  <p className="text-xs text-[#444746] max-w-sm mx-auto">
                    Conecte sua agenda do Google Workspace ou vincule o link de uma reunião já agendada.
                  </p>
                  <div className="flex items-center justify-center gap-3 pt-2">
                    <button
                      onClick={() => setIsConnectCalendarOpen(true)}
                      className="md-btn-tonal text-xs py-1.5 px-3.5"
                    >
                      Conectar Google Calendar
                    </button>
                    <button
                      onClick={() => setIsModalOpen(true)}
                      className="md-btn-outlined text-xs py-1.5 px-3.5"
                    >
                      Vincular por Link
                    </button>
                  </div>
                </div>
              ) : (
                calendarEvents.map((evt) => (
                  <div
                    key={evt.id}
                    className="p-4 rounded-xl bg-[#f8fafd] border border-[#e0e3e7] flex items-center justify-between gap-4 hover:border-[#747775] transition-all"
                  >
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-[#1f1f1f] truncate">{evt.title}</p>
                      <p className="text-xs text-[#444746] mt-0.5">
                        {new Date(evt.startTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} –{' '}
                        {new Date(evt.endTime).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} •{' '}
                        {new Date(evt.startTime).toLocaleDateString()}
                      </p>
                      {evt.googleMeetLink && (
                        <a
                          href={evt.googleMeetLink}
                          target="_blank"
                          rel="noreferrer"
                          className="inline-flex items-center gap-1 text-xs text-[#0b57d0] hover:underline mt-1 font-medium"
                        >
                          {evt.googleMeetLink}
                          <ExternalLink className="w-3 h-3" />
                        </a>
                      )}
                    </div>
                    <button
                      onClick={() => startScheduledMeetingCapture(evt)}
                      className="md-btn-filled text-xs py-2 px-4 shrink-0"
                    >
                      <Play className="w-3.5 h-3.5 fill-white" />
                      Abrir Sala
                    </button>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>

        {/* Coluna 2: Reuniões Vinculadas & Gravadas */}
        <div className="md-card p-6 flex flex-col justify-between">
          <div>
            <div className="flex items-center justify-between mb-2">
              <div className="flex items-center gap-2.5">
                <Video className="w-5 h-5 text-[#0b57d0]" />
                <h2 className="text-base font-medium text-[#1f1f1f]">
                  Reuniões Vinculadas & Histórico
                </h2>
              </div>
              <button
                onClick={() => navigate('/meetings')}
                className="text-xs text-[#0b57d0] hover:underline font-medium flex items-center gap-1 cursor-pointer"
              >
                Ver todas <ArrowRight className="w-3.5 h-3.5" />
              </button>
            </div>
            <p className="text-xs text-[#444746] mb-4">
              Acesse a transcrição auditável, ata executiva e busca contextual das reuniões registradas.
            </p>

            <div className="space-y-2.5">
              {meetings.length === 0 ? (
                <div className="text-center py-10 px-4 border border-dashed border-[#c4c7c5] rounded-2xl bg-[#f8fafd] space-y-2">
                  <p className="text-sm font-medium text-[#1f1f1f]">
                    Nenhuma reunião vinculada até o momento
                  </p>
                  <p className="text-xs text-[#444746]">
                    Selecione um evento agendado ao lado ou vincule uma reunião agendada do Google Meet.
                  </p>
                </div>
              ) : (
                meetings.map((m) => (
                  <div
                    key={m.id}
                    onClick={() => navigate(`/meetings/${m.id}/live`)}
                    className="p-4 rounded-xl bg-[#f8fafd] border border-[#e0e3e7] flex items-center justify-between gap-4 cursor-pointer hover:bg-[#edf2fc]/60 hover:border-[#0b57d0]/40 transition-all"
                  >
                    <div className="min-w-0">
                      <p className="text-sm font-medium text-[#1f1f1f] truncate">{m.title}</p>
                      <p className="text-xs text-[#444746] mt-0.5">
                        {new Date(m.scheduledStart || m.createdAt).toLocaleDateString()} •{' '}
                        {m.googleMeetLink || m.captureMechanism}
                      </p>
                    </div>
                    <div className="flex items-center gap-3">
                      <span
                        className={`px-2.5 py-0.5 text-[11px] rounded-full font-medium ${
                          m.status === 'COMPLETED'
                            ? 'bg-[#e6f4ea] text-[#146c2e]'
                            : m.status === 'IN_PROGRESS'
                            ? 'bg-[#fef7e0] text-[#b06000]'
                            : 'bg-[#d3e3fd] text-[#041e49]'
                        }`}
                      >
                        {m.status}
                      </span>
                      <ArrowRight className="w-4 h-4 text-[#444746]" />
                    </div>
                  </div>
                ))
              )}
            </div>
          </div>
        </div>
      </div>

      <NewMeetingModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onCreated={() => loadDashboardData()}
      />
    </div>
  );
};
