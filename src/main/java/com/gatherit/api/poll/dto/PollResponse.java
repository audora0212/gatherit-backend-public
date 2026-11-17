package com.gatherit.api.poll.dto;

import com.gatherit.api.poll.entity.ConfirmPolicy;
import com.gatherit.api.poll.entity.Poll;

import java.time.Instant;
import java.util.List;

public record PollResponse(
        Long id,
        Long groupId,
        String title,
        Instant deadlineAt,
        ConfirmPolicy confirmPolicy,
        Long confirmedSlotId,
        Long linkedEventId,
        boolean linkedEventDeleted,
        Long createdBy,
        List<SlotResult> slots,
        int totalRespondents,
        int totalMembers,
        List<String> guestRespondentNames
) {
    public record SlotResult(
            Long id,
            Instant startAt,
            Instant endAt,
            int availableCount,
            List<Long> availableUserIds,
            List<String> availableGuestNames
    ) {}

    public static PollResponse of(Poll p, boolean linkedEventDeleted, List<SlotResult> slots,
                                  int totalRespondents, int totalMembers, List<String> guestRespondentNames) {
        return new PollResponse(
                p.getId(), p.getGroupId(), p.getTitle(), p.getDeadlineAt(),
                p.getConfirmPolicy(), p.getConfirmedSlotId(), p.getLinkedEventId(),
                linkedEventDeleted, p.getCreatedBy(),
                slots, totalRespondents, totalMembers, guestRespondentNames
        );
    }
}
