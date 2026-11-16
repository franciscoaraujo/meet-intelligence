package com.meetingintelligence.domain.ai;

import java.text.Normalizer;
import java.util.*;

/**
 * Relevância textual leve (sem embeddings) usada para selecionar trechos da
 * Base de Conhecimento: normaliza acentos, reduz palavras a um radical por prefixo
 * (ex.: "distribuídas" ≈ "distribuída" ≈ "distributed") e expande termos técnicos
 * do português para o inglês, já que muitos materiais de referência estão em inglês.
 */
public final class TextRelevance {

    private static final int STEM_LENGTH = 6;

    private static final Set<String> STOPWORDS = Set.of(
            "que", "para", "com", "uma", "como", "por", "mais", "dos", "das", "nos", "nas",
            "isso", "esse", "essa", "este", "esta", "voce", "sobre", "qual", "quais", "seu",
            "sua", "seus", "suas", "ele", "ela", "pelo", "pela", "entre", "quando", "onde",
            "porque", "tem", "ser", "foi", "sao", "the", "and", "for", "with", "what", "how",
            "you", "your", "pode", "fale", "explique", "conte", "nao", "sim", "muito",
            "tambem", "faria", "faz", "fazer", "projetaria", "projetar", "usaria", "usar",
            "garante", "garantir", "trabalha", "trabalhou", "experiencia", "this", "that",
            "are", "can", "will", "from", "into", "funciona",
            // Spec 011: palavras de função em inglês e francês (deduplicação de perguntas nesses idiomas)
            "would", "could", "should", "does", "did", "have", "has", "about", "which", "why", "when", "where",
            "who", "tell", "explain", "describe", "there", "they", "them", "then", "than", "been", "were",
            "vous", "pour", "avec", "dans", "est", "une", "des", "les", "aux", "sur", "pas", "plus", "comment",
            "quel", "quelle", "quels", "quelles", "pourquoi", "votre", "vos", "cette", "ces", "sont", "fait",
            "faire", "expliquez", "parlez"
    );

    /** Termos PT → equivalentes EN comuns em material técnico (já sem acento). */
    private static final Map<String, List<String>> PT_EN = Map.ofEntries(
            Map.entry("limitador", List.of("limiter", "limit")),
            Map.entry("limite", List.of("limit")),
            Map.entry("taxa", List.of("rate")),
            Map.entry("requisicoes", List.of("requests")),
            Map.entry("encurtador", List.of("shortener")),
            Map.entry("armazenamento", List.of("storage")),
            Map.entry("chave", List.of("key")),
            Map.entry("valor", List.of("value")),
            Map.entry("fila", List.of("queue")),
            Map.entry("filas", List.of("queue")),
            Map.entry("mensageria", List.of("messaging", "message", "queue")),
            Map.entry("mensagens", List.of("message")),
            Map.entry("escalabilidade", List.of("scalability", "scale")),
            Map.entry("escalar", List.of("scale")),
            Map.entry("notificacao", List.of("notification")),
            Map.entry("notificacoes", List.of("notification")),
            Map.entry("consistencia", List.of("consistency")),
            Map.entry("disponibilidade", List.of("availability")),
            Map.entry("particionamento", List.of("partition", "sharding")),
            Map.entry("replicacao", List.of("replication", "replica")),
            Map.entry("banco", List.of("database")),
            Map.entry("dados", List.of("data")),
            Map.entry("balanceador", List.of("balancer")),
            Map.entry("carga", List.of("load")),
            Map.entry("distribuido", List.of("distributed")),
            Map.entry("distribuidos", List.of("distributed")),
            Map.entry("distribuidas", List.of("distributed")),
            Map.entry("transacoes", List.of("transaction")),
            Map.entry("identificador", List.of("identifier", "unique")),
            Map.entry("unico", List.of("unique")),
            Map.entry("rastreador", List.of("crawler")),
            Map.entry("busca", List.of("search")),
            Map.entry("autocompletar", List.of("autocomplete")),
            Map.entry("feed", List.of("feed")),
            Map.entry("noticias", List.of("news")),
            Map.entry("bate", List.of("chat")),
            Map.entry("latencia", List.of("latency")),
            Map.entry("vazao", List.of("throughput")),
            Map.entry("falhas", List.of("failure")),
            Map.entry("tolerancia", List.of("tolerance")),
            Map.entry("estimativa", List.of("estimation")),
            Map.entry("seguranca", List.of("security")),
            Map.entry("criptografia", List.of("encryption", "cryptography")),
            Map.entry("milhoes", List.of("millions", "million")),
            Map.entry("usuarios", List.of("users", "user")),
            Map.entry("sistema", List.of("system")),
            Map.entry("servidores", List.of("server")),
            Map.entry("armazenar", List.of("store", "storage")),
            Map.entry("monitoramento", List.of("monitoring", "metrics")),
            Map.entry("arquitetura", List.of("architecture"))
    );

    /** Índice das traduções pelo radical, para que "escalaria"/"escalar" ou "milhão"/"milhões" usem a mesma entrada. */
    private static final Map<String, List<String>> PT_EN_BY_STEM = new HashMap<>();
    static {
        PT_EN.forEach((pt, en) -> PT_EN_BY_STEM.computeIfAbsent(stem(pt), k -> new ArrayList<>()).addAll(en));
    }

    private TextRelevance() {}

    public static String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String stem(String token) {
        return token.length() > STEM_LENGTH ? token.substring(0, STEM_LENGTH) : token;
    }

    /** Radicais significativos do texto, sem expansões de tradução (para comparar textos entre si). */
    public static Set<String> contentStems(String text) {
        Set<String> stems = new HashSet<>();
        for (String token : normalize(text).split("[^a-z0-9]+")) {
            if (token.length() >= 3 && !STOPWORDS.contains(token)) stems.add(stem(token));
        }
        return stems;
    }

    /** Similaridade de Jaccard entre os radicais de dois textos (0 a 1). */
    public static double jaccard(String a, String b) {
        Set<String> sa = contentStems(a);
        Set<String> sb = contentStems(b);
        if (sa.isEmpty() && sb.isEmpty()) return 1.0;
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return (double) inter.size() / union.size();
    }

    /** Radicais significativos da consulta, incluindo expansões PT → EN. */
    public static Set<String> queryStems(String query) {
        Set<String> stems = new HashSet<>();
        for (String token : normalize(query).split("[^a-z0-9]+")) {
            if (token.length() < 3 || STOPWORDS.contains(token)) continue;
            stems.add(stem(token));
            for (String en : PT_EN_BY_STEM.getOrDefault(stem(token), List.of())) stems.add(stem(en));
        }
        return stems;
    }

    /**
     * Pontuação: cobertura (radicais distintos da consulta presentes, peso 2) somada a
     * log(ocorrências). Trechos com vários links (bibliografias, referências) são
     * penalizados, pois repetem palavras-chave sem explicar o assunto.
     */
    public static double score(String text, Set<String> queryStems) {
        if (queryStems.isEmpty() || text == null) return 0;
        Map<String, Integer> freq = new HashMap<>();
        String normalized = normalize(text);
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.length() < 3) continue;
            String s = stem(token);
            if (queryStems.contains(s)) freq.merge(s, 1, Integer::sum);
        }
        double score = 0;
        for (int count : freq.values()) score += 2 + Math.log(count);
        if (countOccurrences(normalized, "http") >= 2) score *= 0.2;
        return score;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i != -1; i = text.indexOf(needle, i + needle.length())) count++;
        return count;
    }

    /** Remove contatos (e-mail, telefone, URLs) — úteis no currículo, mas ruído numa resposta falada. */
    public static String stripContactInfo(String text) {
        if (text == null) return "";
        return text
                .replaceAll("[\\w.+-]+@[\\w-]+\\.[\\w.]+", "")
                .replaceAll("\\+?\\d{2}\\s?\\(?\\d{2}\\)?\\s?\\d{4,5}-?\\d{4}", "")
                .replaceAll("(https?://)?(www\\.)?(linkedin|github)\\.com/\\S*", "")
                .replaceAll("[📍📞📧🔗🐙]", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    /**
     * Divide texto em frases. Junta quebras de linha internas (comuns em PDFs) e corta
     * frases longas demais para que cada unidade seja pequena e específica.
     */
    public static List<String> sentences(String text, int maxLength) {
        List<String> result = new ArrayList<>();
        if (text == null) return result;
        String flat = text.replaceAll("\\s*\\n\\s*", " ").replaceAll("\\s{2,}", " ").trim();
        for (String s : flat.split("(?<=[.!?])\\s+|\\s+(?=•)")) {
            String sentence = s.trim();
            while (sentence.length() > maxLength) {
                int cut = sentence.lastIndexOf(' ', maxLength);
                if (cut < maxLength / 2) cut = maxLength;
                result.add(sentence.substring(0, cut).trim());
                sentence = sentence.substring(cut).trim();
            }
            if (sentence.length() >= 15) result.add(sentence);
        }
        return result;
    }
}
