import React, { useState } from 'react';
import { X, Video, Globe, Calendar } from 'lucide-react';
import { api } from '../../services/api';
import { CaptureMechanism, MeetingType } from '../../types';

interface NewMeetingModalProps {
  isOpen: boolean;
  onClose: () => void;
  onCreated: () => void;
}

export const NewMeetingModal: React.FC<NewMeetingModalProps> = ({ isOpen, onClose, onCreated }) => {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [googleMeetLink, setGoogleMeetLink] = useState('');
  const [scheduledStart, setScheduledStart] = useState('');
  const [captureMechanism, setCaptureMechanism] = useState<CaptureMechanism>('BROWSER_CAPTURE');
  const [meetingType, setMeetingType] = useState<MeetingType>('GENERAL');
  const [language, setLanguage] = useState('pt-BR');
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorMsg(null);

    if (!title.trim()) return;
    if (!googleMeetLink.trim() || !googleMeetLink.includes('meet.google.com/')) {
      setErrorMsg(
        'Informe o link válido de uma reunião existente agendada no Google Meet (ex: https://meet.google.com/abc-defg-hij).'
      );
      return;
    }

    try {
      setSubmitting(true);
      const startIso = scheduledStart ? new Date(scheduledStart).toISOString() : new Date().toISOString();
      const endIso = scheduledStart
        ? new Date(new Date(scheduledStart).getTime() + 60 * 60 * 1000).toISOString()
        : new Date(Date.now() + 60 * 60 * 1000).toISOString();

      await api.post('/meetings', {
        title,
        description,
        googleMeetLink: googleMeetLink.trim(),
        scheduledStart: startIso,
        scheduledEnd: endIso,
        captureMechanism,
        meetingType,
        language,
      });
      onCreated();
      onClose();
      setTitle('');
      setDescription('');
      setGoogleMeetLink('');
      setScheduledStart('');
    } catch (error) {
      console.error('Erro ao vincular reunião:', error);
      setErrorMsg('Não foi possível vincular a reunião agendada.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-[1px]">
      <div className="w-full max-w-lg bg-white border border-[#e0e3e7] rounded-[28px] shadow-xl overflow-hidden">
        <div className="flex items-center justify-between px-6 pt-6 pb-4 border-b border-[#e0e3e7]">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-full bg-[#d3e3fd] text-[#041e49] flex items-center justify-center">
              <Video className="w-5 h-5" />
            </div>
            <div>
              <h2 className="text-lg font-medium text-[#1f1f1f]">Vincular Reunião Agendada</h2>
              <p className="text-xs text-[#444746]">Apenas compromissos existentes no Google Meet</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 rounded-full text-[#444746] hover:bg-[#f0f4f9] transition-colors cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          {errorMsg && (
            <div className="p-3 rounded-xl bg-[#f9dedc] border border-[#b3261e]/20 text-xs text-[#410e0b] font-medium">
              {errorMsg}
            </div>
          )}

          <div>
            <label className="block text-xs font-medium text-[#444746] mb-1.5">
              Título da Reunião Agendada *
            </label>
            <input
              type="text"
              required
              placeholder="Ex: Reunião de Planejamento Trimestral"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              className="md-input"
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-[#444746] mb-1.5">
                Link do Google Meet *
              </label>
              <input
                type="url"
                required
                placeholder="https://meet.google.com/xxx-yyyy-zzz"
                value={googleMeetLink}
                onChange={(e) => setGoogleMeetLink(e.target.value)}
                className="md-input"
              />
            </div>

            <div>
              <label className="block text-xs font-medium text-[#444746] mb-1.5">
                Data e Hora Agendada *
              </label>
              <div className="relative">
                <Calendar className="w-4 h-4 text-[#444746] absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
                <input
                  type="datetime-local"
                  required
                  value={scheduledStart}
                  onChange={(e) => setScheduledStart(e.target.value)}
                  className="md-input pl-9!"
                />
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-[#444746] mb-1.5">
                Mecanismo de Captura
              </label>
              <select
                value={captureMechanism}
                onChange={(e) => setCaptureMechanism(e.target.value as CaptureMechanism)}
                className="md-input"
              >
                <option value="BROWSER_CAPTURE">Captura de Áudio (Navegador)</option>
                <option value="BOT">Bot Automatizado (WebRTC)</option>
                <option value="GOOGLE_DRIVE">Gravação Google Meet (Drive)</option>
                <option value="FILE_UPLOAD">Upload de Arquivo (Áudio/Vídeo)</option>
              </select>
            </div>

            <div>
              <label className="block text-xs font-medium text-[#444746] mb-1.5">
                Perfil de Análise
              </label>
              <select
                value={meetingType}
                onChange={(e) => setMeetingType(e.target.value as MeetingType)}
                className="md-input"
              >
                <option value="GENERAL">Geral / Alinhamento</option>
                <option value="TECHNICAL">Reunião Técnica / Arquitetura</option>
                <option value="INTERVIEW">Entrevista / Processo Seletivo</option>
                <option value="SALES">Comercial / Vendas</option>
                <option value="CORPORATE">Executiva / Governança</option>
                <option value="CLASS">Treinamento / Aula</option>
              </select>
            </div>
          </div>

          <div>
            <label className="block text-xs font-medium text-[#444746] mb-1.5">
              Idioma Principal da Reunião
            </label>
            <div className="flex items-center gap-2">
              <Globe className="w-4 h-4 text-[#444746]" />
              <select
                value={language}
                onChange={(e) => setLanguage(e.target.value)}
                className="md-input"
              >
                <option value="pt-BR">Português do Brasil (pt-BR)</option>
                <option value="en-US">English (en-US)</option>
                <option value="fr-FR">Français (fr-FR)</option>
                <option value="auto">Detecção Automática</option>
              </select>
            </div>
          </div>

          <div className="pt-4 flex items-center justify-end gap-2 border-t border-[#e0e3e7]">
            <button
              type="button"
              onClick={onClose}
              className="px-5 py-2.5 rounded-full text-sm font-medium text-[#0b57d0] hover:bg-[#edf2fc] transition-colors cursor-pointer"
            >
              Cancelar
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="md-btn-filled"
            >
              {submitting ? 'Vinculando...' : 'Vincular Reunião'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
