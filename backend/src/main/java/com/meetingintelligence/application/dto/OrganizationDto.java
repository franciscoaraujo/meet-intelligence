package com.meetingintelligence.application.dto;

import com.meetingintelligence.domain.enums.OrganizationRole;
import com.meetingintelligence.domain.enums.PlanType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrganizationDto {
    private UUID id;
    private String name;
    private String slug;
    private PlanType planType;
    private List<MemberItemDto> members;
    private OffsetDateTime createdAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MemberItemDto {
        private UUID userId;
        private String email;
        private String fullName;
        private String avatarUrl;
        private OrganizationRole role;
    }
}

