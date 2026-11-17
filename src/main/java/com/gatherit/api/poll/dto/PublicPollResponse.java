package com.gatherit.api.poll.dto;

import java.time.Instant;
import java.util.List;

public record PublicPollResponse(
        Long pollId,
        String shareToken,
        Long groupId,
        String groupName,
        boolean groupMember,
        boolean publicPollJoinAllowed,
        String title,
        Instant deadlineAt,
        Long confirmedSlotId,
        boolean closed,
        Instant shareExpiresAt,
        int totalRespondents,
        int memberRespondents,
        List<String> guestRespondentNames,
        String myNickname,
        List<Long> myAvailableSlotIds,
        List<PublicSlot> slots
) {
    public record PublicSlot(
            Long id,
            Instant startAt,
            Instant endAt,
            int availableCount,
            List<String> availableGuestNames
    ) {}
}
