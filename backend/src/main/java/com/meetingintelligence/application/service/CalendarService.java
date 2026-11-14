package com.meetingintelligence.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meetingintelligence.application.dto.CalendarEventDto;
import com.meetingintelligence.domain.entity.Meeting;
import com.meetingintelligence.domain.entity.Organization;
import com.meetingintelligence.domain.entity.User;
import com.meetingintelligence.domain.enums.CaptureMechanism;
import com.meetingintelligence.domain.enums.MeetingStatus;
import com.meetingintelligence.infrastructure.persistence.repository.MeetingRepository;
import com.meetingintelligence.infrastructure.persistence.repository.OrganizationRepository;
import com.meetingintelligence.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class CalendarService {

    private final UserRepository userRepository;
    private final MeetingRepository meetingRepository;
    private final OrganizationRepository organizationRepository;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    private static final Pattern MEET_LINK_PATTERN = Pattern.compile("https://meet\\.google\\.com/[a-z]{3}-[a-z]{4}-[a-z]{3}");

    @Transactional(readOnly = true)
    public boolean isGoogleCalendarConnected(UUID userId) {
        return userRepository.findById(userId)
                .map(u -> u.getGoogleRefreshToken() != null && !u.getGoogleRefreshToken().isBlank())
                .orElse(false);
    }

    @Transactional
    public List<CalendarEventDto> connectAndSyncGoogleCalendar(UUID userId, UUID organizationId, String googleAccessToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Usuário não encontrado: " + userId));

        if (googleAccessToken != null && !googleAccessToken.isBlank()) {
            user.setGoogleRefreshToken(googleAccessToken.trim());
            userRepository.save(user);
        }

        return listUpcomingEvents(userId, organizationId);
    }

    @Transactional
    public List<CalendarEventDto> listUpcomingEvents(UUID userId, UUID organizationId) {
        log.info("Sincronizando reuniões futuras agendadas no Google Calendar para usuário {} / organização {}", userId, organizationId);

        List<CalendarEventDto> result = new ArrayList<>();
        Set<String> seenEventIds = new HashSet<>();

        // 1. Consulta a API real do Google Calendar se o usuário possuir token OAuth conectado
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isPresent() && userOpt.get().getGoogleRefreshToken() != null && !userOpt.get().getGoogleRefreshToken().isBlank()) {
            String accessToken = userOpt.get().getGoogleRefreshToken();
            try {
                List<CalendarEventDto> googleEvents = fetchFromGoogleCalendarApi(accessToken, organizationId, userOpt.get());
                for (CalendarEventDto evt : googleEvents) {
                    result.add(evt);
                    if (evt.getId() != null) seenEventIds.add(evt.getId());
                }
            } catch (Exception e) {
                log.warn("Não foi possível consultar a API do Google Calendar com o token atual: {}", e.getMessage());
            }
        }

        // 2. Inclui reuniões de hoje e futuras agendadas (SCHEDULED) no banco da organização
        OffsetDateTime startOfToday = LocalDate.now(ZoneOffset.ofHours(-3))
                .atStartOfDay()
                .atOffset(ZoneOffset.ofHours(-3));
        if (organizationId != null) {
            List<Meeting> scheduledMeetings = meetingRepository.findByOrganizationIdAndStatusOrderByScheduledStartAsc(
                    organizationId, MeetingStatus.SCHEDULED
            );
            for (Meeting m : scheduledMeetings) {
                if (m.getScheduledEnd() != null && m.getScheduledEnd().isBefore(startOfToday)) {
                    continue; // Ignora reuniões de dias anteriores que já passaram
                }
                String calId = m.getGoogleCalendarEventId() != null && !m.getGoogleCalendarEventId().isBlank()
                        ? m.getGoogleCalendarEventId()
                        : m.getId().toString();

                if (seenEventIds.contains(calId)) {
                    continue;
                }

                result.add(CalendarEventDto.builder()
                        .id(calId)
                        .meetingId(m.getId())
                        .title(m.getTitle())
                        .description(m.getDescription())
                        .startTime(m.getScheduledStart() != null ? m.getScheduledStart() : m.getCreatedAt())
                        .endTime(m.getScheduledEnd() != null ? m.getScheduledEnd() : m.getCreatedAt().plusHours(1))
                        .googleMeetLink(m.getGoogleMeetLink())
                        .hasMeetLink(m.getGoogleMeetLink() != null && m.getGoogleMeetLink().contains("meet.google.com"))
                        .build());
            }
        }

        result.sort(Comparator.comparing(CalendarEventDto::getStartTime));
        return result;
    }

    private List<CalendarEventDto> fetchFromGoogleCalendarApi(String accessToken, UUID organizationId, User hostUser) throws Exception {
        OffsetDateTime startOfToday = LocalDate.now(ZoneOffset.ofHours(-3))
                .atStartOfDay()
                .atOffset(ZoneOffset.ofHours(-3));
        String timeMin = startOfToday.toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String url = "https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin=" + timeMin
                + "&singleEvents=true&orderBy=startTime&maxResults=50";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
        List<CalendarEventDto> upcomingEvents = new ArrayList<>();

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode items = root.path("items");
            Organization org = organizationId != null ? organizationRepository.findById(organizationId).orElse(null) : null;

            if (items.isArray()) {
                // Primeiro identifica quais horários já possuem convite oficial com link do Google Meet
                Set<OffsetDateTime> startTimesWithMeetLink = new HashSet<>();
                for (JsonNode item : items) {
                    String meetLink = extractGoogleMeetLink(item);
                    OffsetDateTime st = parseGoogleDateTime(item.path("start"));
                    if (meetLink != null && !meetLink.isBlank() && st != null) {
                        startTimesWithMeetLink.add(st);
                    }
                }

                for (JsonNode item : items) {
                    String status = item.path("status").asText("");
                    if ("cancelled".equalsIgnoreCase(status)) {
                        continue;
                    }

                    OffsetDateTime startTime = parseGoogleDateTime(item.path("start"));
                    OffsetDateTime endTime = parseGoogleDateTime(item.path("end"));
                    if (startTime == null) continue;
                    if (endTime == null) endTime = startTime.plusHours(1);

                    // Ignora reuniões de dias anteriores
                    if (endTime.isBefore(startOfToday)) {
                        continue;
                    }

                    String meetLink = extractGoogleMeetLink(item);
                    String description = item.path("description").asText("");

                    // Se houver no mesmo horário (ex: 13h) tanto o convite oficial com link do Meet quanto um lembrete pessoal sem link, ignora apenas o lembrete duplicado daquele mesmo horário
                    if ((meetLink == null || meetLink.isBlank())
                            && startTimesWithMeetLink.contains(startTime)
                            && description.toLowerCase().contains("lembrete")) {
                        continue;
                    }

                    String eventId = item.path("id").asText();
                    String rawSummary = item.path("summary").asText("").trim();
                    String organizerEmail = item.path("organizer").path("email").asText("");
                    String summary = !rawSummary.isBlank()
                            ? rawSummary
                            : (!organizerEmail.isBlank()
                                ? "Reunião Google Meet (" + organizerEmail + ")"
                                : "Reunião Google Meet Agendada");

                    List<String> attendees = new ArrayList<>();
                    JsonNode attendeesNode = item.path("attendees");
                    if (attendeesNode.isArray()) {
                        for (JsonNode att : attendeesNode) {
                            String email = att.path("email").asText(null);
                            if (email != null) attendees.add(email);
                        }
                    }

                    UUID existingMeetingId = null;
                    if (organizationId != null && eventId != null && org != null) {
                        Optional<Meeting> existingOpt = meetingRepository
                                .findByOrganizationIdAndGoogleCalendarEventId(organizationId, eventId);
                        if (existingOpt.isPresent()) {
                            existingMeetingId = existingOpt.get().getId();
                        } else {
                            Meeting syncedMeeting = meetingRepository.save(Meeting.builder()
                                    .organization(org)
                                    .createdBy(hostUser)
                                    .title(summary)
                                    .description(description)
                                    .status(MeetingStatus.SCHEDULED)
                                    .captureMechanism(CaptureMechanism.BROWSER_CAPTURE)
                                    .googleMeetLink(meetLink)
                                    .googleCalendarEventId(eventId)
                                    .scheduledStart(startTime)
                                    .scheduledEnd(endTime)
                                    .language("pt-BR")
                                    .build());
                            existingMeetingId = syncedMeeting.getId();
                        }
                    }

                    upcomingEvents.add(CalendarEventDto.builder()
                            .id(eventId)
                            .meetingId(existingMeetingId)
                            .title(summary)
                            .description(description)
                            .startTime(startTime)
                            .endTime(endTime)
                            .googleMeetLink(meetLink)
                            .hasMeetLink(meetLink != null && !meetLink.isBlank())
                            .attendees(attendees)
                            .build());
                }
            }
        }

        return upcomingEvents;
    }

    private String extractGoogleMeetLink(JsonNode item) {
        String hangoutLink = item.path("hangoutLink").asText(null);
        if (hangoutLink != null && hangoutLink.contains("meet.google.com")) {
            return hangoutLink;
        }

        JsonNode entryPoints = item.path("conferenceData").path("entryPoints");
        if (entryPoints.isArray()) {
            for (JsonNode ep : entryPoints) {
                String uri = ep.path("uri").asText("");
                if (uri.contains("meet.google.com")) {
                    return uri;
                }
            }
        }

        String location = item.path("location").asText("");
        Matcher locMatcher = MEET_LINK_PATTERN.matcher(location);
        if (locMatcher.find()) {
            return locMatcher.group(0);
        }

        String description = item.path("description").asText("");
        Matcher descMatcher = MEET_LINK_PATTERN.matcher(description);
        if (descMatcher.find()) {
            return descMatcher.group(0);
        }

        return null;
    }

    private OffsetDateTime parseGoogleDateTime(JsonNode dateNode) {
        if (dateNode == null || dateNode.isMissingNode()) return null;
        String dateTimeStr = dateNode.path("dateTime").asText(null);
        if (dateTimeStr != null && !dateTimeStr.isBlank()) {
            try {
                return OffsetDateTime.parse(dateTimeStr);
            } catch (Exception ignored) {}
        }
        String dateStr = dateNode.path("date").asText(null);
        if (dateStr != null && !dateStr.isBlank()) {
            try {
                return LocalDate.parse(dateStr).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (Exception ignored) {}
        }
        return null;
    }
}
