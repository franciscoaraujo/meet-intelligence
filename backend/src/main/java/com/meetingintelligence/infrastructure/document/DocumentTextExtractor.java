package com.meetingintelligence.infrastructure.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Extrai texto puro de arquivos enviados para a Base de Conhecimento.
 * Formatos suportados: PDF, DOCX, TXT e MD.
 */
@Component
public class DocumentTextExtractor {

    /** Limite de bytes descompactados lidos de um DOCX (proteção contra zip bomb). */
    private static final int MAX_DOCX_XML_BYTES = 20 * 1024 * 1024;

    public String extract(String fileName, byte[] content) {
        String ext = extensionOf(fileName);
        String text = switch (ext) {
            case "pdf" -> extractPdf(content);
            case "docx" -> extractDocx(content);
            case "txt", "md", "markdown" -> new String(content, StandardCharsets.UTF_8);
            default -> throw new IllegalArgumentException(
                    "Formato não suportado: ." + ext + ". Envie PDF, DOCX, TXT ou MD.");
        };
        return normalize(text);
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot == -1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String extractPdf(byte[] content) {
        try (PDDocument doc = Loader.loadPDF(content)) {
            if (doc.isEncrypted()) {
                throw new IllegalArgumentException("O PDF está protegido por senha.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível ler o PDF: " + e.getMessage());
        }
    }

    private String extractDocx(byte[] content) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    String xml = readLimited(zip);
                    return docxXmlToText(xml);
                }
            }
            throw new IllegalArgumentException("Arquivo DOCX inválido (word/document.xml não encontrado).");
        } catch (IOException e) {
            throw new IllegalArgumentException("Não foi possível ler o DOCX: " + e.getMessage());
        }
    }

    private String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_DOCX_XML_BYTES) {
                throw new IllegalArgumentException("Conteúdo do DOCX excede o tamanho máximo suportado.");
            }
            out.write(buffer, 0, read);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    static String docxXmlToText(String xml) {
        String text = xml
                .replaceAll("<w:tab/>", "\t")
                .replaceAll("<w:br/>", "\n")
                .replaceAll("</w:p>", "\n")
                .replaceAll("<[^>]+>", "");
        return text
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** Remove espaços redundantes e linhas vazias em excesso, preservando parágrafos. */
    static String normalize(String text) {
        if (text == null) return "";
        return text
                .replace("\u0000", "")
                .replace("\r\n", "\n")
                .replaceAll("[ \\t\\x0B\\f]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
