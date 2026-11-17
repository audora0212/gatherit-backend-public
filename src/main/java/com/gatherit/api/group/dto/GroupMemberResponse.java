package com.gatherit.api.group.dto;

import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.entity.MemberRole;

public record GroupMemberResponse(
        Long userId,
        MemberRole role,
        String nickname,
        String profileImageUrl
) {
    public static GroupMemberResponse of(GroupMember gm, String nickname, String profileImageUrl) {
        return new GroupMemberResponse(gm.getUserId(), gm.getRole(), nickname, profileImageUrl);
    }
}
