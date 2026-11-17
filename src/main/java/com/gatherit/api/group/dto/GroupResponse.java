package com.gatherit.api.group.dto;

import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.entity.GroupMemberPolicy;
import com.gatherit.api.group.entity.PhotoDeletePolicy;

public record GroupResponse(
        Long id,
        String name,
        GroupColor colorKey,
        Long createdBy,
        long memberCount,
        boolean personal,
        PhotoDeletePolicy photoDeletePolicy,
        GroupMemberPolicy invitePolicy,
        GroupMemberPolicy eventCreatePolicy,
        GroupMemberPolicy pollCreatePolicy,
        boolean publicPollJoinAllowed
) {
    public static GroupResponse of(Group g, long memberCount, GroupColor userColor) {
        GroupColor resolved = userColor != null ? userColor : (g.getColorKey() != null ? g.getColorKey() : GroupColor.team);
        PhotoDeletePolicy delete = g.getPhotoDeletePolicy() != null ? g.getPhotoDeletePolicy() : PhotoDeletePolicy.ANYONE;
        GroupMemberPolicy invite = g.getInvitePolicy() != null ? g.getInvitePolicy() : GroupMemberPolicy.ANYONE;
        GroupMemberPolicy eventCreate = g.getEventCreatePolicy() != null ? g.getEventCreatePolicy() : GroupMemberPolicy.ANYONE;
        GroupMemberPolicy pollCreate = g.getPollCreatePolicy() != null ? g.getPollCreatePolicy() : GroupMemberPolicy.ANYONE;
        return new GroupResponse(
                g.getId(), g.getName(), resolved, g.getCreatedBy(),
                memberCount, g.isPersonal(),
                delete, invite, eventCreate, pollCreate,
                g.isPublicPollJoinAllowed()
        );
    }
}
