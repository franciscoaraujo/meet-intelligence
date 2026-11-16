/**
 * ============================================================================
 * Base de Conhecimento (KnowledgeBasePage.tsx)
 * ============================================================================
 * Upload de currículo e materiais de referência (PDF, DOCX, TXT, MD) cujo texto
 * é usado como contexto pelo Copiloto de Entrevista e pelo Chat da IA.
 */
import React, { useEffect, useRef, useState } from 'react';
import { ConfirmDialog } from '../components/common/ConfirmDialog';
import {
  BookMarked,
  UploadCloud,
  FileText,
  UserRound,
  Library,
  Trash2,
  Eye,
  EyeOff,
  AlertCircle,
  Loader2,
  X,
} from 'lucide-react';
import { api } from '../services/api';

type KnowledgeDocumentType = 'RESUME' | 'REFERENCE';

interface KnowledgeDocument {
  id: string;
  fileName: string;
  contentType?: string;
  documentType: KnowledgeDocumentType;
  charCount: number;
  active: boolean;
  preview: string;
  createdAt: string;
}

const ACCEPTED_EXTENSIONS = ['.pdf', '.docx', '.txt', '.md'];
const MAX_FILE_MB = 10;

const extractErrorMessage = (err: unknown): string => {
  const anyErr = err as { response?: { data?: { message?: string; error?: string } } };
  return (
    anyErr.response?.data?.message ||
    anyErr.response?.data?.error ||
    'Não foi possível processar o arquivo.'
  );
};

export const KnowledgeBasePage: React.FC = () => {
  const [documents, setDocuments] = useState<KnowledgeDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [uploadType, setUploadType] = useState<KnowledgeDocumentType>('RESUME');
  const [dragOver, setDragOver] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [previewDoc, setPreviewDoc] = useState<KnowledgeDocument | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const loadDocuments = async () => {
    try {
      setLoading(true);
      const res = await api.get<KnowledgeDocument[]>('/knowledge-base');
      setDocuments(res.data);
    } catch (err) {
      console.error('Erro ao carregar Base de Conhecimento:', err);
      setError('Não foi possível carregar seus documentos.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDocuments();
  }, []);

  const uploadFiles = async (files: FileList | File[]) => {
    setError(null);
    const list = Array.from(files);
    for (const file of list) {
      const lower = file.name.toLowerCase();
      if (!ACCEPTED_EXTENSIONS.some((ext) => lower.endsWith(ext))) {
        setError(`"${file.name}": formato não suportado. Envie PDF, DOCX, TXT ou MD.`);
        continue;
      }
      if (file.size > MAX_FILE_MB * 1024 * 1024) {
        setError(`"${file.name}" excede o limite de ${MAX_FILE_MB} MB.`);
        continue;
      }

      try {
        setUploading(true);
        const formData = new FormData();
        formData.append('file', file);
        formData.append('documentType', uploadType);
        const res = await api.post<KnowledgeDocument>('/knowledge-base/upload', formData, {
          headers: { 'Content-Type': 'multipart/form-data' },
        });
        setDocuments((prev) => [res.data, ...prev]);
      } catch (err) {
        setError(`"${file.name}": ${extractErrorMessage(err)}`);
      } finally {
        setUploading(false);
      }
    }
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (e.dataTransfer.files?.length) uploadFiles(e.dataTransfer.files);
  };

  const handleFileInput = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files?.length) uploadFiles(e.target.files);
    e.target.value = '';
  };

  const updateDocument = async (doc: KnowledgeDocument, changes: Partial<Pick<KnowledgeDocument, 'active' | 'documentType'>>) => {
    try {
      const body: Record<string, string> = {};
      if (changes.active !== undefined) body.active = String(changes.active);
      if (changes.documentType) body.documentType = changes.documentType;
      const res = await api.patch<KnowledgeDocument>(`/knowledge-base/${doc.id}`, body);
      setDocuments((prev) => prev.map((d) => (d.id === doc.id ? res.data : d)));
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  };

  // Documento aguardando confirmação de remoção (diálogo do app, no lugar de window.confirm)
  const [pendingRemoval, setPendingRemoval] = useState<KnowledgeDocument | null>(null);
  const [removing, setRemoving] = useState(false);
  const [removalError, setRemovalError] = useState<string | null>(null);

  const deleteDocument = (doc: KnowledgeDocument) => {
    setRemovalError(null);
    setPendingRemoval(doc);
  };

  const confirmRemoval = async () => {
    if (!pendingRemoval) return;
    const doc = pendingRemoval;
    try {
      setRemoving(true);
      await api.delete(`/knowledge-base/${doc.id}`);
      setDocuments((prev) => prev.filter((d) => d.id !== doc.id));
      setPendingRemoval(null);
    } catch (err) {
      setRemovalError(extractErrorMessage(err));
    } finally {
      setRemoving(false);
    }
  };

  const resumes = documents.filter((d) => d.documentType === 'RESUME');
  const references = documents.filter((d) => d.documentType === 'REFERENCE');

  const renderDocument = (doc: KnowledgeDocument) => (
    <div
      key={doc.id}
      className={`md-card p-4 flex items-start gap-3 ${doc.active ? '' : 'opacity-60'}`}
    >
      <div className="w-10 h-10 rounded-xl bg-[#f0f4f9] flex items-center justify-center shrink-0">
        <FileText className="w-5 h-5 text-[#0b57d0]" />
      </div>
      <div className="flex-1 min-w-0">
        <p className="text-sm font-medium text-[#1f1f1f] truncate" title={doc.fileName}>
          {doc.fileName}
        </p>
        <p className="text-xs text-[#444746] mt-0.5">
          {doc.charCount.toLocaleString('pt-BR')} caracteres extraídos ·{' '}
          {new Date(doc.createdAt).toLocaleDateString('pt-BR')}
          {!doc.active && ' · desativado'}
        </p>
        <div className="flex flex-wrap items-center gap-2 mt-2.5">
          <select
            value={doc.documentType}
            onChange={(e) => updateDocument(doc, { documentType: e.target.value as KnowledgeDocumentType })}
            className="text-xs px-2.5 py-1 rounded-full border border-[#c4c7c5] bg-white text-[#1f1f1f] cursor-pointer"
          >
            <option value="RESUME">Currículo</option>
            <option value="REFERENCE">Material de referência</option>
          </select>
          <button
            onClick={() => setPreviewDoc(doc)}
            className="text-xs px-2.5 py-1 rounded-full text-[#0b57d0] hover:bg-[#f0f4f9] cursor-pointer"
          >
            Ver texto extraído
          </button>
        </div>
      </div>
      <div className="flex items-center gap-1 shrink-0">
        <button
          onClick={() => updateDocument(doc, { active: !doc.active })}
          title={doc.active ? 'Desativar (a IA deixa de usar este documento)' : 'Ativar'}
          className="p-2 rounded-full text-[#444746] hover:bg-[#f0f4f9] cursor-pointer"
        >
          {doc.active ? <Eye className="w-4 h-4" /> : <EyeOff className="w-4 h-4" />}
        </button>
        <button
          onClick={() => deleteDocument(doc)}
          title="Remover"
          className="p-2 rounded-full text-[#444746] hover:text-[#b3261e] hover:bg-[#f9dedc]/60 cursor-pointer"
        >
          <Trash2 className="w-4 h-4" />
        </button>
      </div>
    </div>
  );

  return (
    <div className="p-8 space-y-6 max-w-5xl mx-auto w-full">
      <ConfirmDialog
        open={pendingRemoval !== null}
        destructive
        title="Remover documento?"
        description={
          <>
            <strong className="text-[#202124]">{pendingRemoval?.fileName}</strong> deixará de ser usado como contexto pela IA e
            será removido da Base de Conhecimento.
          </>
        }
        confirmLabel="Remover"
        busy={removing}
        busyLabel="Removendo…"
        error={removalError}
        onConfirm={() => void confirmRemoval()}
        onCancel={() => setPendingRemoval(null)}
      />
      <div>
        <h1 className="text-2xl font-normal tracking-tight text-[#1f1f1f] flex items-center gap-2.5">
          <BookMarked className="w-6 h-6 text-[#0b57d0]" />
          Base de Conhecimento
        </h1>
        <p className="text-sm text-[#444746] mt-1">
          Envie seu currículo e materiais de referência. A IA usa esses documentos para montar respostas
          personalizadas no Copiloto de Entrevista e no Chat da reunião.
        </p>
      </div>

      {/* Área de upload */}
      <div className="md-card p-6 space-y-4">
        <div className="flex flex-wrap items-center gap-2">
          <span className="text-xs font-medium text-[#444746] mr-1">Tipo do documento:</span>
          {([
            { value: 'RESUME', label: 'Currículo', icon: UserRound },
            { value: 'REFERENCE', label: 'Material de referência', icon: Library },
          ] as const).map((opt) => {
            const Icon = opt.icon;
            const selected = uploadType === opt.value;
            return (
              <button
                key={opt.value}
                onClick={() => setUploadType(opt.value)}
                className={`flex items-center gap-1.5 px-3.5 py-1.5 rounded-full text-xs font-medium border transition-colors cursor-pointer ${
                  selected
                    ? 'bg-[#d3e3fd] border-[#d3e3fd] text-[#041e49]'
                    : 'bg-white border-[#c4c7c5] text-[#444746] hover:bg-[#f0f4f9]'
                }`}
              >
                <Icon className="w-3.5 h-3.5" />
                {opt.label}
              </button>
            );
          })}
        </div>

        <div
          onDragOver={(e) => {
            e.preventDefault();
            setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
          onClick={() => !uploading && fileInputRef.current?.click()}
          className={`border-2 border-dashed rounded-2xl p-10 flex flex-col items-center justify-center text-center cursor-pointer transition-colors ${
            dragOver ? 'border-[#0b57d0] bg-[#edf2fc]' : 'border-[#c4c7c5] hover:border-[#0b57d0] hover:bg-[#f8fafd]'
          }`}
        >
          {uploading ? (
            <>
              <Loader2 className="w-9 h-9 text-[#0b57d0] animate-spin" />
              <p className="text-sm text-[#1f1f1f] mt-3">Extraindo texto do documento...</p>
            </>
          ) : (
            <>
              <UploadCloud className="w-9 h-9 text-[#0b57d0]" />
              <p className="text-sm text-[#1f1f1f] mt-3">
                Arraste arquivos aqui ou <span className="text-[#0b57d0] font-medium">clique para selecionar</span>
              </p>
              <p className="text-xs text-[#444746] mt-1">
                PDF, DOCX, TXT ou MD · até {MAX_FILE_MB} MB · enviando como{' '}
                <strong>{uploadType === 'RESUME' ? 'Currículo' : 'Material de referência'}</strong>
              </p>
            </>
          )}
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept={ACCEPTED_EXTENSIONS.join(',')}
            onChange={handleFileInput}
            className="hidden"
          />
        </div>

        {error && (
          <div className="flex items-start gap-2 p-3 rounded-xl bg-[#f9dedc] text-[#410e0b] text-xs">
            <AlertCircle className="w-4 h-4 shrink-0 mt-0.5" />
            <span className="flex-1">{error}</span>
            <button onClick={() => setError(null)} className="cursor-pointer">
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        <div className="text-xs text-[#444746] bg-[#f8fafd] border border-[#e0e3e7] rounded-xl p-3.5 space-y-1">
          <p>
            <strong>Currículo:</strong> enviado inteiro à IA. Ela responde perguntas como "fale sobre você" ou
            "qual sua experiência com X" em primeira pessoa, usando só fatos do seu currículo.
          </p>
          <p>
            <strong>Material de referência:</strong> a cada pergunta, a IA recebe os trechos mais relevantes
            (ex: um PDF de arquitetura) e baseia a resposta neles.
          </p>
        </div>
      </div>

      {/* Lista de documentos */}
      {loading ? (
        <div className="py-8 text-center text-sm text-[#444746]">Carregando documentos...</div>
      ) : documents.length === 0 ? (
        <div className="py-8 text-center text-sm text-[#444746]">
          Nenhum documento enviado ainda. Comece pelo seu currículo.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <section className="space-y-3">
            <h2 className="text-sm font-medium text-[#1f1f1f] flex items-center gap-2">
              <UserRound className="w-4 h-4 text-[#146c2e]" /> Currículo ({resumes.length})
            </h2>
            {resumes.length ? resumes.map(renderDocument) : (
              <p className="text-xs text-[#444746]">Nenhum currículo enviado.</p>
            )}
          </section>
          <section className="space-y-3">
            <h2 className="text-sm font-medium text-[#1f1f1f] flex items-center gap-2">
              <Library className="w-4 h-4 text-[#0b57d0]" /> Materiais de referência ({references.length})
            </h2>
            {references.length ? references.map(renderDocument) : (
              <p className="text-xs text-[#444746]">Nenhum material enviado.</p>
            )}
          </section>
        </div>
      )}

      {/* Modal de pré-visualização do texto extraído */}
      {previewDoc && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40"
          onClick={() => setPreviewDoc(null)}
        >
          <div
            className="w-full max-w-2xl bg-white rounded-[28px] shadow-2xl p-6 space-y-4"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-center justify-between gap-3">
              <h2 className="text-lg font-medium text-[#1f1f1f] truncate">{previewDoc.fileName}</h2>
              <button
                onClick={() => setPreviewDoc(null)}
                className="w-8 h-8 rounded-full hover:bg-[#f0f4f9] flex items-center justify-center text-[#444746] cursor-pointer"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
            <p className="text-xs text-[#444746]">
              Início do texto extraído ({previewDoc.charCount.toLocaleString('pt-BR')} caracteres no total). Confira
              se a leitura ficou correta. PDFs escaneados como imagem não têm texto extraível.
            </p>
            <pre className="whitespace-pre-wrap text-xs text-[#1f1f1f] bg-[#f8fafd] border border-[#e0e3e7] rounded-xl p-4 max-h-[50vh] overflow-y-auto font-sans">
              {previewDoc.preview}
            </pre>
          </div>
        </div>
      )}
    </div>
  );
};
