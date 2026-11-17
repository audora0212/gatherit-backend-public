package com.gatherit.api.poll.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;

public record PublicPollVoteRequest(
        @NotBlank(message = "닉네임을 입력해주세요")
        @Size(max = 20, message = "닉네임은 20자 이하로 입력해주세요")
        String nickname,

        @Size(min = 32, max = 64, message = "참여 정보가 올바르지 않습니다")
        String participantToken,

        @NotNull(message = "가능한 날짜를 선택해주세요")
        @Size(max = 366, message = "선택한 날짜가 너무 많습니다")
        Set<@NotNull Long> availableSlotIds
) {}
