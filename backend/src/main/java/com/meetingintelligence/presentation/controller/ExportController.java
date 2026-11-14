package com.meetingintelligence.presentation.controller;

import com.meetingintelligence.application.dto.MeetingMinutesDto;
import com.meetingintelligence.application.service.MeetingMinutesService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings/{meetingId}/export")
@RequiredArgsConstructor
@Tag(name = "Exportações & Atas", description = "Endpoints para geração e download de atas formais em múltiplos formatos (PDF, DOCX, TXT)")
public class ExportController {

    private final MeetingMinutesService minutesService;

    @GetMapping("/minutes")
    @Operation(summary = "Obter dados da ata em JSON", description = "Retorna a estrutura completa e consolidada da ata de reunião")
    public ResponseEntity<MeetingMinutesDto> getMinutesJson(@PathVariable UUID meetingId) {
        MeetingMinutesDto minutes = minutesService.generateMinutes(meetingId);
        return ResponseEntity.ok(minutes);
    }

    @GetMapping(value = "/txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "Exportar ata em TXT", description = "Download da ata formatada em arquivo de texto plano")
    public ResponseEntity<byte[]> exportTxt(@PathVariable UUID meetingId) {
        byte[] bytes = minutesService.exportAsTxt(meetingId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ata-reuniao-" + meetingId + ".txt\"")
                .body(bytes);
    }

    @GetMapping(value = "/docx")
    @Operation(summary = "Exportar ata em DOCX/Word", description = "Download da ata formatada com estilos corporativos")
    public ResponseEntity<byte[]> exportDocx(@PathVariable UUID meetingId) {
        byte[] bytes = minutesService.exportAsDocxHtml(meetingId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ata-reuniao-" + meetingId + ".doc\"")
                .contentType(MediaType.parseMediaType("application/msword"))
                .body(bytes);
    }

    @GetMapping(value = "/pdf")
    @Operation(summary = "Exportar relatório executivo em PDF/HTML", description = "Download do documento executivo formatado para impressão em PDF")
    public ResponseEntity<byte[]> exportPdf(@PathVariable UUID meetingId) {
        byte[] bytes = minutesService.exportAsDocxHtml(meetingId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"relatorio-reuniao-" + meetingId + ".html\"")
                .contentType(MediaType.TEXT_HTML)
                .body(bytes);
    }
}

