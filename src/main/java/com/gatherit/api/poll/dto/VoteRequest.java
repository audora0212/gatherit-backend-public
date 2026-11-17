package com.gatherit.api.poll.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record VoteRequest(
        /** slotId → 가능 여부. 명시 안 된 슬롯은 변경 X. */
        @NotNull List<SlotVote> votes
) {
    public record SlotVote(@NotNull Long slotId, boolean available) {}
}
