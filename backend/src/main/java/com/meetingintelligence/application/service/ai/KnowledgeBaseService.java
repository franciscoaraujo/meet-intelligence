package com.meetingintelligence.application.service.ai;

import com.meetingintelligence.application.dto.KnowledgeDocumentDto;
import com.meetingintelligence.domain.ai.TextRelevance;
import com.meetingintelligence.domain.entity.KnowledgeDocument;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.User;
import com.meetingintelligence.domain.enums.KnowledgeDocumentType;
import com.meetingintelligence.infrastructure.document.DocumentTextExtractor;
import com.meetingintelligence.infrastructure.persistence.repository.KnowledgeDocumentRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import com.meetingintelligence.infrastructure.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;

/**
 * Base de Conhecimento do usuário: currículo e materiais de referência cujo texto
 * é injetado nos prompts da IA (Copiloto de Entrevista e Chat RAG).
 *
 * Estratégia de contexto:
 *  - Currículos (RESUME) entram inteiros, até {@link #RESUME_CHAR_BUDGET}.
 *  - Materiais de referência (REFERENCE) são divididos em trechos e apenas os mais
 *    relevantes para a pergunta atual entram, até {@link #REFERENCE_CHAR_BUDGET}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final int MAX_STORED_CHARS = 1_500_000;
    static final int RESUME_CHAR_BUDGET = 8_000;
    static final int REFERENCE_CHAR_BUDGET = 6_000;
    static final int CHUNK_SIZE = 1_200;


    private final KnowledgeDocumentRepository documentRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final DocumentTextExtractor textExtractor;

    // ------------------------------------------------------------------ CRUD

    @Transactional
    public KnowledgeDocumentDto upload(MultipartFile file, KnowledgeDocumentType type, UserPrincipal currentUser) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Nenhum arquivo enviado.");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Arquivo excede o limite de 10 MB.");
        }

        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "documento";
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Falha ao ler o arquivo enviado.");
        }

        String text = textExtractor.extract(fileName, bytes);
        if (text.isBlank()) {
            throw new IllegalArgumentException(
                    "Não foi possível extrair texto deste arquivo. Se for um PDF escaneado (imagem), envie uma versão com texto selecionável.");
        }
        if (text.length() > MAX_STORED_CHARS) {
            text = text.substring(0, MAX_STORED_CHARS);
        }

        Organization org = organizationRepository.findById(currentUser.getOrganizationId())
                .orElseThrow(() -> new IllegalArgumentException("Organização não encontrada."));
        User user = userRepository.findById(currentUser.getId()).orElse(null);

        // saveAndFlush para que o @CreationTimestamp (createdAt) já volte preenchido na resposta
        KnowledgeDocument saved = documentRepository.saveAndFlush(KnowledgeDocument.builder()
                .organization(org)
                .uploadedBy(user)
                .fileName(fileName)
                .contentType(file.getContentType())
                .documentType(type != null ? type : KnowledgeDocumentType.REFERENCE)
                .extractedText(text)
                .charCount(text.length())
                .active(true)
                .build());

        log.info("Documento '{}' ({}, {} caracteres) adicionado à Base de Conhecimento da organização {}",
                fileName, saved.getDocumentType(), text.length(), org.getId());
        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentDto> list(UUID organizationId) {
        return documentRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId)
                .stream().map(this::toDto).toList();
    }

    @Transactional
    public KnowledgeDocumentDto update(UUID id, UUID organizationId, Boolean active, KnowledgeDocumentType type) {
        KnowledgeDocument doc = documentRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Documento não encontrado."));
        if (active != null) doc.setActive(active);
        if (type != null) doc.setDocumentType(type);
        return toDto(documentRepository.save(doc));
    }

    @Transactional
    public void delete(UUID id, UUID organizationId) {
        KnowledgeDocument doc = documentRepository.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Documento não encontrado."));
        documentRepository.delete(doc);
    }

    // ------------------------------------------------------- Contexto p/ IA

    /**
     * Monta o bloco {@code <knowledge_base>} a ser inserido no prompt, ou string vazia
     * se a organização não possui documentos ativos.
     *
     * @param query texto da pergunta/fala atual, usado para ranquear trechos de referência
     */
    @Transactional(readOnly = true)
    public String buildContext(UUID organizationId, String query) {
        if (organizationId == null) return "";
        List<KnowledgeDocument> docs = documentRepository.findByOrganizationIdAndActiveTrue(organizationId);
        if (docs.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();

        // 1. Currículos: inteiros, até o orçamento
        int resumeBudget = RESUME_CHAR_BUDGET;
        for (KnowledgeDocument doc : docs) {
            if (doc.getDocumentType() != KnowledgeDocumentType.RESUME || resumeBudget <= 0) continue;
            String text = truncate(doc.getExtractedText(), resumeBudget);
            resumeBudget -= text.length();
            appendDocument(sb, "CURRÍCULO DO CANDIDATO (o usuário)", doc.getFileName(), text);
        }

        // 2. Referências: trechos mais relevantes para a pergunta
        List<Chunk> chunks = new ArrayList<>();
        int totalReferenceChars = 0;
        for (KnowledgeDocument doc : docs) {
            if (doc.getDocumentType() != KnowledgeDocumentType.REFERENCE) continue;
            totalReferenceChars += doc.getExtractedText().length();
            List<String> parts = splitIntoChunks(doc.getExtractedText(), CHUNK_SIZE);
            for (int i = 0; i < parts.size(); i++) {
                chunks.add(new Chunk(doc.getFileName(), i, parts.get(i)));
            }
        }

        List<Chunk> selected = selectReferenceChunks(chunks, query, totalReferenceChars);
        for (Chunk c : selected) {
            appendDocument(sb, "MATERIAL DE REFERÊNCIA", c.fileName(), c.text());
        }

        if (sb.isEmpty()) return "";
        return "<knowledge_base>\n" + sb + "</knowledge_base>";
    }

    List<Chunk> selectReferenceChunks(List<Chunk> chunks, String query, int totalChars) {
        if (chunks.isEmpty()) return List.of();

        // Material pequeno: envia tudo, sem necessidade de ranqueamento
        if (totalChars <= REFERENCE_CHAR_BUDGET) return chunks;

        Set<String> queryTerms = TextRelevance.queryStems(query);
        if (queryTerms.isEmpty()) return List.of();

        List<Map.Entry<Chunk, Double>> scored = new ArrayList<>();
        for (Chunk c : chunks) {
            double score = TextRelevance.score(c.text(), queryTerms);
            if (score > 0) scored.add(Map.entry(c, score));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<Chunk> result = new ArrayList<>();
        int used = 0;
        for (Map.Entry<Chunk, Double> e : scored) {
            int len = e.getKey().text().length();
            if (used + len > REFERENCE_CHAR_BUDGET) break;
            result.add(e.getKey());
            used += len;
        }
        // Mantém a ordem original do documento para leitura coerente
        result.sort(Comparator.comparing(Chunk::fileName).thenComparingInt(Chunk::index));
        return result;
    }




    /** Divide o texto em trechos de ~size caracteres, preferindo quebrar em parágrafos/frases. */
    static List<String> splitIntoChunks(String text, int size) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            if (end < text.length()) {
                int paragraph = text.lastIndexOf("\n", end);
                int sentence = text.lastIndexOf(". ", end);
                int cut = Math.max(paragraph, sentence);
                if (cut > start + size / 2) end = cut + 1;
            }
            String part = text.substring(start, end).trim();
            if (!part.isEmpty()) chunks.add(part);
            start = end;
        }
        return chunks;
    }

    private static void appendDocument(StringBuilder sb, String label, String fileName, String text) {
        sb.append("<document tipo=\"").append(label).append("\" arquivo=\"")
                .append(fileName.replace("\"", "'")).append("\">\n")
                .append(sanitize(text))
                .append("\n</document>\n");
    }

    /** Impede que o conteúdo do documento feche as tags do prompt (prompt injection). */
    private static String sanitize(String text) {
        return text.replaceAll("(?i)</?\\s*(knowledge_base|document|transcript_chunk|transcript_context)[^>]*>", "");
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private KnowledgeDocumentDto toDto(KnowledgeDocument d) {
        String text = d.getExtractedText();
        return KnowledgeDocumentDto.builder()
                .id(d.getId())
                .fileName(d.getFileName())
                .contentType(d.getContentType())
                .documentType(d.getDocumentType())
                .charCount(d.getCharCount())
                .active(d.isActive())
                .preview(text.length() > 400 ? text.substring(0, 400) + "…" : text)
                .createdAt(d.getCreatedAt())
                .build();
    }

    record Chunk(String fileName, int index, String text) {}
}
