import React, { useState, useEffect } from 'react';
import { Sliders, Plus, Check, Sparkles, BookOpen, Briefcase, Code, GraduationCap, Building2, X } from 'lucide-react';
import { api } from '../services/api';
import { MeetingType } from '../types';

interface AIProfile {
  id?: string;
  name: string;
  meetingType: MeetingType;
  systemPromptOverride?: string;
  focusCategories?: string;
  isDefault: boolean;
}

export const AiProfilesPage: React.FC = () => {
  const [profiles, setProfiles] = useState<AIProfile[]>([]);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [name, setName] = useState('');
  const [meetingType, setMeetingType] = useState<MeetingType>('TECHNICAL');
  const [promptOverride, setPromptOverride] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    loadProfiles();
  }, []);

  const loadProfiles = async () => {
    try {
      setLoading(true);
      const res = await api.get('/ai-profiles');
      setProfiles(res.data);
    } catch (err) {
      console.error('Erro ao carregar perfis:', err);
    } finally {
      setLoading(false);
    }
  };

  const handleCreateProfile = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;

    try {
      await api.post('/ai-profiles', {
        name,
        meetingType,
        systemPromptOverride: promptOverride,
        focusCategories: '["DECISION","TASK","QUESTION","CONTENT_ANALYSIS"]',
        isDefault: false,
      });

      setIsModalOpen(false);
      setName('');
      setPromptOverride('');
      loadProfiles();
    } catch (err) {
      console.error('Erro ao criar perfil:', err);
    }
  };

  const getIconForType = (type: MeetingType) => {
    switch (type) {
      case 'TECHNICAL': return <Code className="w-5 h-5 text-[#0b57d0]" />;
      case 'INTERVIEW': return <Briefcase className="w-5 h-5 text-[#146c2e]" />;
      case 'SALES': return <Sparkles className="w-5 h-5 text-[#b06000]" />;
      case 'CORPORATE': return <Building2 className="w-5 h-5 text-[#00639b]" />;
      case 'CLASS': return <GraduationCap className="w-5 h-5 text-[#6b4ea2]" />;
      default: return <BookOpen className="w-5 h-5 text-[#444746]" />;
    }
  };

  return (
    <div className="p-8 space-y-6 max-w-7xl mx-auto w-full">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-normal tracking-tight text-[#1f1f1f] flex items-center gap-2.5">
            <Sliders className="w-6 h-6 text-[#0b57d0]" />
            Perfis de Inteligência Artificial
          </h1>
          <p className="text-sm text-[#444746] mt-1">
            Adapte as análises, categorias e prompts de sistema de acordo com a natureza de cada reunião.
          </p>
        </div>

        <button
          onClick={() => setIsModalOpen(true)}
          className="md-btn-filled"
        >
          <Plus className="w-4 h-4" />
          Novo Perfil Customizado
        </button>
      </div>

      {/* Grid de Perfis */}
      {loading ? (
        <div className="py-12 text-center text-sm text-[#444746]">Carregando perfis de IA...</div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-5">
          {profiles.map((p, idx) => (
            <div
              key={p.id || idx}
              className="md-card p-6 flex flex-col justify-between space-y-4"
            >
              <div>
                <div className="flex items-center justify-between gap-2 mb-3">
                  <div className="w-10 h-10 rounded-xl bg-[#f0f4f9] flex items-center justify-center">
                    {getIconForType(p.meetingType)}
                  </div>
                  {p.isDefault && (
                    <span className="px-2.5 py-0.5 rounded-full text-[10px] font-semibold bg-[#d3e3fd] text-[#041e49]">
                      PADRÃO DO SISTEMA
                    </span>
                  )}
                </div>
                <h3 className="text-base font-medium text-[#1f1f1f]">{p.name}</h3>
                <p className="text-xs text-[#0b57d0] font-medium mt-0.5">{p.meetingType}</p>

                <div className="mt-4 p-3.5 bg-[#f8fafd] border border-[#e0e3e7] rounded-xl text-xs text-[#444746]">
                  <span className="text-[10px] font-semibold text-[#747775] uppercase block mb-1">
                    Diretivas do Prompt:
                  </span>
                  <p className="line-clamp-3">
                    {p.systemPromptOverride || 'Diretivas balanceadas para acompanhamento geral de reuniões corporativas.'}
                  </p>
                </div>
              </div>

              <div className="pt-3 border-t border-[#e0e3e7] flex items-center justify-between text-xs text-[#444746]">
                <span>Análise Estruturada</span>
                <span className="text-[#146c2e] flex items-center gap-1 font-medium">
                  <Check className="w-3.5 h-3.5" /> Ativo
                </span>
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Modal Material Design 3 */}
      {isModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40">
          <div className="w-full max-w-lg bg-white rounded-[28px] shadow-2xl p-6 space-y-5">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-medium text-[#1f1f1f]">Criar Perfil de IA Customizado</h2>
              <button
                onClick={() => setIsModalOpen(false)}
                className="w-8 h-8 rounded-full hover:bg-[#f0f4f9] flex items-center justify-center text-[#444746]"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            <form onSubmit={handleCreateProfile} className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-[#444746] mb-1.5">
                  Nome do Perfil *
                </label>
                <input
                  type="text"
                  required
                  placeholder="Ex: Entrevista de Engenharia de Dados"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  className="md-input"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-[#444746] mb-1.5">
                  Tipo de Reunião Base
                </label>
                <select
                  value={meetingType}
                  onChange={(e) => setMeetingType(e.target.value as MeetingType)}
                  className="md-input"
                >
                  <option value="TECHNICAL">Técnica / Arquitetura</option>
                  <option value="INTERVIEW">Entrevista / Processo Seletivo</option>
                  <option value="SALES">Comercial / Vendas</option>
                  <option value="CORPORATE">Corporativa / Executiva</option>
                  <option value="CLASS">Treinamento / Aula</option>
                  <option value="GENERAL">Geral / Alinhamento</option>
                </select>
              </div>

              <div>
                <label className="block text-xs font-medium text-[#444746] mb-1.5">
                  Instruções Específicas para o Prompt da IA
                </label>
                <textarea
                  rows={4}
                  placeholder="Defina o que a IA deve priorizar ao analisar esta reunião..."
                  value={promptOverride}
                  onChange={(e) => setPromptOverride(e.target.value)}
                  className="md-input"
                />
              </div>

              <div className="pt-3 flex justify-end gap-2 border-t border-[#e0e3e7]">
                <button
                  type="button"
                  onClick={() => setIsModalOpen(false)}
                  className="px-4 py-2 text-sm font-medium text-[#0b57d0] hover:bg-[#f0f4f9] rounded-full"
                >
                  Cancelar
                </button>
                <button
                  type="submit"
                  className="md-btn-filled"
                >
                  Salvar Perfil
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
