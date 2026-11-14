import React, { useState, useEffect } from 'react';
import {
  ClipboardList,
  Download,
  FileText,
  FileCode,
  Calendar,
  Users,
  Award,
  CheckSquare,
  Sparkles,
} from 'lucide-react';
import { api } from '../services/api';
import { Meeting } from '../types';

interface DecisionItem {
  description: string;
  justification?: string;
  responsible?: string;
  timeFormatted: string;
}

interface TaskItem {
  description: string;
  assignee: string;
  dueDate: string;
  priority: string;
  status: string;
}

interface MeetingMinutes {
  meetingId: string;
  title: string;
  organizationName: string;
  meetingType: string;
  date: string;
  participants: string[];
  executiveSummary: string;
  keyTopics: string[];
  decisions: DecisionItem[];
  tasks: TaskItem[];
  fullTranscriptText: string;
}

export const SummariesPage: React.FC = () => {
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [selectedMeetingId, setSelectedMeetingId] = useState<string>('');
  const [minutes, setMinutes] = useState<MeetingMinutes | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    loadMeetings();
  }, []);

  useEffect(() => {
    if (selectedMeetingId) {
      loadMinutes(selectedMeetingId);
    }
  }, [selectedMeetingId]);

  const loadMeetings = async () => {
    try {
      const res = await api.get('/meetings?size=50');
      const list: Meeting[] = res.data.content || [];
      setMeetings(list);
      if (list.length > 0) {
        setSelectedMeetingId(list[0].id);
      }
    } catch (err) {
      console.error('Erro ao carregar reuniões:', err);
    }
  };

  const loadMinutes = async (meetingId: string) => {
    try {
      setLoading(true);
      const res = await api.get(`/meetings/${meetingId}/export/minutes`);
      setMinutes(res.data);
    } catch (err) {
      console.error('Erro ao carregar ata:', err);
    } finally {
      setLoading(false);
    }
  };

  const downloadFile = (endpoint: string, filename: string) => {
    if (!selectedMeetingId) return;
    const token = localStorage.getItem('token');
    const url = `${api.defaults.baseURL}/meetings/${selectedMeetingId}/export/${endpoint}`;

    fetch(url, {
      headers: { Authorization: `Bearer ${token}` },
    })
      .then((res) => res.blob())
      .then((blob) => {
        const link = document.createElement('a');
        link.href = window.URL.createObjectURL(blob);
        link.download = filename;
        link.click();
      })
      .catch((err) => console.error('Erro no download:', err));
  };

  return (
    <div className="p-8 space-y-6 max-w-7xl mx-auto w-full">
      {/* Top Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-normal tracking-tight text-[#1f1f1f] flex items-center gap-2.5">
            <ClipboardList className="w-6 h-6 text-[#0b57d0]" />
            Atas Executivas & Exportações
          </h1>
          <p className="text-sm text-[#444746] mt-1">
            Geração consolidada de atas formais prontas para auditoria, relatórios e envio à diretoria.
          </p>
        </div>

        {/* Botões de Download */}
        <div className="flex items-center gap-2.5">
          <button
            onClick={() => downloadFile('txt', `ata-${selectedMeetingId}.txt`)}
            className="md-btn-outlined"
          >
            <FileText className="w-4 h-4 text-[#444746]" />
            <span>TXT</span>
          </button>
          <button
            onClick={() => downloadFile('docx', `ata-${selectedMeetingId}.doc`)}
            className="md-btn-tonal"
          >
            <FileCode className="w-4 h-4 text-[#001d35]" />
            <span>DOCX / Word</span>
          </button>
          <button
            onClick={() => downloadFile('pdf', `relatorio-${selectedMeetingId}.html`)}
            className="md-btn-filled"
          >
            <Download className="w-4 h-4" />
            <span>Exportar PDF</span>
          </button>
        </div>
      </div>

      {/* Seletor de Reunião */}
      <div className="bg-[#f8fafd] border border-[#e0e3e7] p-4 rounded-2xl flex flex-col sm:flex-row sm:items-center justify-between gap-3">
        <label className="text-xs font-medium text-[#444746]">
          Selecione a reunião para visualizar a ata formal:
        </label>
        <div className="relative w-full sm:w-96">
          <select
            value={selectedMeetingId}
            onChange={(e) => setSelectedMeetingId(e.target.value)}
            className="md-input bg-white"
          >
            {meetings.map((m) => (
              <option key={m.id} value={m.id}>
                {m.title} ({new Date(m.createdAt).toLocaleDateString('pt-BR')})
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Visualizador da Ata Formatada (Estilo Google Docs) */}
      {loading ? (
        <div className="text-center py-20 text-[#0b57d0] text-sm flex items-center justify-center gap-2">
          <Sparkles className="w-5 h-5 animate-spin" /> Consolidando ata formal da reunião...
        </div>
      ) : minutes ? (
        <div className="md-card p-8 sm:p-10 space-y-8 max-w-5xl mx-auto">
          {/* Cabeçalho da Ata */}
          <div className="border-b border-[#e0e3e7] pb-6 space-y-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-[#0b57d0] uppercase tracking-wider">
                {minutes.organizationName} • Documento de Ata Oficial
              </span>
              <span className="px-3 py-1 text-xs bg-[#e8f0fe] text-[#0b57d0] rounded-full font-medium">
                {minutes.meetingType}
              </span>
            </div>
            <h2 className="text-2xl font-normal text-[#1f1f1f]">{minutes.title}</h2>
            <div className="flex flex-wrap items-center gap-4 text-xs text-[#444746] pt-2">
              <span className="flex items-center gap-1.5">
                <Calendar className="w-4 h-4 text-[#444746]" />
                {new Date(minutes.date).toLocaleDateString('pt-BR')}
              </span>
              <span>•</span>
              <span className="flex items-center gap-1.5">
                <Users className="w-4 h-4 text-[#444746]" />
                {minutes.participants.length} Participantes
              </span>
            </div>
          </div>

          {/* Participantes */}
          <div className="space-y-3">
            <h3 className="text-sm font-medium text-[#1f1f1f] flex items-center gap-2">
              <Users className="w-4 h-4 text-[#0b57d0]" /> 1. Participantes Presentes
            </h3>
            <div className="flex flex-wrap gap-2">
              {minutes.participants.map((p, idx) => (
                <span
                  key={idx}
                  className="px-3 py-1 bg-[#f0f4f9] border border-[#e0e3e7] text-[#1f1f1f] text-xs rounded-full font-medium"
                >
                  {p}
                </span>
              ))}
            </div>
          </div>

          {/* Resumo Executivo */}
          <div className="space-y-3">
            <h3 className="text-sm font-medium text-[#1f1f1f] flex items-center gap-2">
              <Sparkles className="w-4 h-4 text-[#0b57d0]" /> 2. Resumo Executivo Consolidado
            </h3>
            <div className="p-5 bg-[#f8fafd] border border-[#e0e3e7] rounded-2xl text-sm text-[#1f1f1f] leading-relaxed">
              {minutes.executiveSummary}
            </div>
          </div>

          {/* Tópicos Principais */}
          {minutes.keyTopics.length > 0 && (
            <div className="space-y-3">
              <h3 className="text-sm font-medium text-[#1f1f1f]">
                3. Tópicos Chave Discutidos
              </h3>
              <div className="flex flex-wrap gap-2">
                {minutes.keyTopics.map((t, idx) => (
                  <span
                    key={idx}
                    className="px-3 py-1 bg-[#e8f0fe] text-[#0b57d0] rounded-full text-xs font-medium"
                  >
                    #{t}
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* Decisões Tomadas */}
          <div className="space-y-3">
            <h3 className="text-sm font-medium text-[#1f1f1f] flex items-center gap-2">
              <Award className="w-4 h-4 text-[#146c2e]" /> 4. Deliberações e Decisões Tomadas
            </h3>
            {minutes.decisions.length === 0 ? (
              <p className="text-xs text-[#444746] italic">Nenhuma decisão formal registrada.</p>
            ) : (
              <div className="space-y-3">
                {minutes.decisions.map((d, idx) => (
                  <div
                    key={idx}
                    className="p-4 bg-[#e6f4ea]/50 border border-[#c4eed0] rounded-xl space-y-1.5"
                  >
                    <div className="flex items-center justify-between text-xs">
                      <span className="font-semibold text-[#072711]">{d.description}</span>
                      <span className="text-[11px] font-mono text-[#146c2e]">{d.timeFormatted}</span>
                    </div>
                    {d.justification && (
                      <p className="text-xs text-[#1f1f1f]">
                        <strong>Justificativa:</strong> {d.justification}
                      </p>
                    )}
                    {d.responsible && (
                      <p className="text-[11px] text-[#444746]">Responsável: {d.responsible}</p>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Tarefas e Plano de Ação */}
          <div className="space-y-3">
            <h3 className="text-sm font-medium text-[#1f1f1f] flex items-center gap-2">
              <CheckSquare className="w-4 h-4 text-[#0b57d0]" /> 5. Plano de Ação (Tarefas e Prazos)
            </h3>
            {minutes.tasks.length === 0 ? (
              <p className="text-xs text-[#444746] italic">Nenhuma tarefa atribuída nesta reunião.</p>
            ) : (
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                {minutes.tasks.map((t, idx) => (
                  <div
                    key={idx}
                    className="p-4 bg-[#f8fafd] border border-[#d3e3fd] rounded-xl space-y-1.5"
                  >
                    <div className="flex items-center justify-between text-xs">
                      <span className="font-semibold text-[#1f1f1f]">{t.description}</span>
                      <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-[#d3e3fd] text-[#041e49]">
                        {t.priority}
                      </span>
                    </div>
                    <div className="flex items-center justify-between text-[11px] text-[#444746] pt-1">
                      <span>Responsável: {t.assignee}</span>
                      <span>Prazo: {t.dueDate}</span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      ) : (
        <div className="md-card text-center py-16 text-[#444746] text-xs">
          Nenhuma reunião selecionada ou dados insuficientes para gerar a ata.
        </div>
      )}
    </div>
  );
};
