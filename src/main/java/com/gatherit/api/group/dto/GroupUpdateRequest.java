package com.gatherit.api.group.dto;

import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.entity.GroupMemberPolicy;
import com.gatherit.api.group.entity.PhotoDeletePolicy;
import jakarta.validation.constraints.Size;

public record GroupUpdateRequest(
        @Size(min = 1, max = 50) String name,
        GroupColor colorKey,
        PhotoDeletePolicy photoDeletePolicy,
        GroupMemberPolicy invitePolicy,
        GroupMemberPolicy eventCreatePolicy,
        GroupMemberPolicy pollCreatePolicy,
        Boolean publicPollJoinAllowed
) {}
