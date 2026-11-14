import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, ArrowRight, Search, Trash2, Video } from 'lucide-react';
import { api } from '../services/api';
import { Meeting } from '../types';
import { NewMeetingModal } from '../components/modals/NewMeetingModal';

export const MeetingsPage: React.FC = () => {
  const [meetings, setMeetings] = useState<Meeting[]>([]);
  const [filter, setFilter] = useState<string>('ALL');
  const [search, setSearch] = useState('');
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    loadMeetings();
  }, []);

  const loadMeetings = async () => {
    try {
      setLoading(true);
      const res = await api.get('/meetings?size=50');
      setMeetings(res.data.content || []);
    } catch (error) {
      console.error('Erro ao carregar reuniões:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleDeleteMeeting = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm('Tem certeza de que deseja excluir esta reunião definitivamente? Todas as gravações, transcrições e análises serão apagadas em conformidade com a LGPD.')) {
      return;
    }

    try {
      setDeletingId(id);
      await api.delete(`/meetings/${id}`);
      setMeetings((prev) => prev.filter((m) => m.id !== id));
    } catch (error) {
      console.error('Erro ao excluir reunião:', error);
      alert('Não foi possível excluir a reunião.');
    } finally {
      setDeletingId(null);
    }
  };

  const filtered = meetings.filter((m) => {
    const matchesFilter = filter === 'ALL' || m.status === filter;
    const matchesSearch = m.title.toLowerCase().includes(search.toLowerCase());
    return matchesFilter && matchesSearch;
  });

  const getStatusLabel = (status: string) => {
    switch (status) {
      case 'ALL': return 'Todas';
      case 'IN_PROGRESS': return 'Em Andamento';
      case 'SCHEDULED': return 'Agendadas';
      case 'COMPLETED': return 'Concluídas';
      default: return status;
    }
  };

  return (
    <div className="p-8 space-y-6 max-w-7xl mx-auto w-full">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-normal tracking-tight text-[#1f1f1f]">Minhas Reuniões</h1>
          <p className="text-sm text-[#444746] mt-1">
            Histórico completo de reuniões gravadas, transcritas e interpretadas.
          </p>
        </div>
        <button
          onClick={() => setIsModalOpen(true)}
          className="md-btn-filled"
        >
          <Plus className="w-4 h-4" />
          Nova Reunião
        </button>
      </div>

      {/* Barra de Filtros Material 3 Filter Chips + Busca */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-4 bg-[#f8fafd] p-3.5 rounded-2xl border border-[#e0e3e7]">
        <div className="flex items-center gap-2 w-full sm:w-auto flex-wrap">
          {['ALL', 'IN_PROGRESS', 'SCHEDULED', 'COMPLETED'].map((status) => (
            <button
              key={status}
              onClick={() => setFilter(status)}
              className={`px-4 py-1.5 rounded-lg text-xs font-medium transition-all border ${
                filter === status
                  ? 'bg-[#c2e7ff] text-[#001d35] border-[#c2e7ff] font-semibold'
                  : 'bg-white text-[#444746] border-[#c4c7c5] hover:bg-[#f0f4f9]'
              }`}
            >
              {getStatusLabel(status)}
            </button>
          ))}
        </div>

        <div className="relative w-full sm:w-72">
          <Search className="w-4 h-4 text-[#444746] absolute left-3.5 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            placeholder="Filtrar reuniões por título..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-10 pr-4 py-2 bg-white border border-[#c4c7c5] rounded-full text-xs text-[#1f1f1f] placeholder-[#747775] focus:outline-none focus:border-[#0b57d0] focus:ring-2 focus:ring-[#0b57d0]/15"
          />
        </div>
      </div>

      {/* Grid de Reuniões */}
      {loading ? (
        <div className="py-16 text-center text-sm text-[#444746]">Carregando reuniões...</div>
      ) : filtered.length === 0 ? (
        <div className="md-card p-12 text-center space-y-3">
          <div className="w-12 h-12 rounded-full bg-[#f0f4f9] flex items-center justify-center mx-auto text-[#0b57d0]">
            <Video className="w-6 h-6" />
          </div>
          <h3 className="text-base font-medium text-[#1f1f1f]">Nenhuma reunião encontrada</h3>
          <p className="text-xs text-[#444746] max-w-md mx-auto">
            Inicie uma nova captura de reunião ou sincronize sua agenda do Google Meet no painel principal.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {filtered.map((m) => (
            <div
              key={m.id}
              onClick={() => navigate(`/meetings/${m.id}/live`)}
              className="md-card p-5 cursor-pointer flex flex-col justify-between space-y-4 group relative"
            >
              <div>
                <div className="flex items-center justify-between gap-2 mb-3">
                  <span
                    className={`px-2.5 py-0.5 text-[11px] font-medium rounded-full ${
                      m.status === 'COMPLETED'
                        ? 'bg-[#e6f4ea] text-[#137333]'
                        : m.status === 'IN_PROGRESS'
                        ? 'bg-[#fef7e0] text-[#b06000]'
                        : 'bg-[#e8f0fe] text-[#1967d2]'
                    }`}
                  >
                    {getStatusLabel(m.status)}
                  </span>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-[#444746]">
                      {new Date(m.createdAt).toLocaleDateString('pt-BR')}
                    </span>
                    <button
                      onClick={(e) => handleDeleteMeeting(m.id, e)}
                      disabled={deletingId === m.id}
                      title="Excluir reunião definitivamente (LGPD)"
                      className="p-1.5 rounded-full text-[#444746] hover:text-[#b3261e] hover:bg-[#f9dedc] transition-colors"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>
                <h3 className="text-base font-medium text-[#1f1f1f] group-hover:text-[#0b57d0] transition-colors line-clamp-2">
                  {m.title}
                </h3>
                {m.description && (
                  <p className="text-xs text-[#444746] mt-1.5 line-clamp-2">{m.description}</p>
                )}
              </div>

              <div className="pt-3 border-t border-[#e0e3e7] flex items-center justify-between text-xs text-[#444746]">
                <span className="text-[11px] font-medium px-2 py-0.5 rounded bg-[#f0f4f9] text-[#444746]">
                  {m.captureMechanism}
                </span>
                <span className="flex items-center gap-1 text-[#0b57d0] font-medium group-hover:translate-x-0.5 transition-transform">
                  Abrir sala <ArrowRight className="w-3.5 h-3.5" />
                </span>
              </div>
            </div>
          ))}
        </div>
      )}

      <NewMeetingModal
        isOpen={isModalOpen}
        onClose={() => setIsModalOpen(false)}
        onCreated={() => loadMeetings()}
      />
    </div>
  );
};
