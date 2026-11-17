package com.gatherit.api.user.service;

import com.gatherit.api.auth.service.RefreshTokenStore;
import com.gatherit.api.event.repository.EventRepository;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.group.service.GroupService;
import com.gatherit.api.photo.entity.Photo;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.photo.repository.PhotoRepository;
import com.gatherit.api.photo.storage.PhotoStorage;
import com.gatherit.api.photo.storage.StorageCleanup;
import com.gatherit.api.poll.repository.PollVoteRepository;
import com.gatherit.api.poll.repository.PollGuestParticipantRepository;
import com.gatherit.api.user.dto.MeStatsResponse;
import com.gatherit.api.user.dto.UpdateMeRequest;
import com.gatherit.api.user.dto.UserMeResponse;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final EventRepository eventRepository;
    private final PollVoteRepository pollVoteRepository;
    private final PollGuestParticipantRepository pollGuestParticipantRepository;
    private final com.gatherit.api.event.repository.DdayPinRepository ddayPinRepository;
    private final PhotoRepository photoRepository;
    private final PhotoStorage photoStorage;
    private final RefreshTokenStore refreshTokenStore;
    private final GroupService groupService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public UserMeResponse getMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserMeResponse.from(user);
    }

    /**
     * MY 탭 stats — 그룹/사진/이벤트 카운트만 단일 응답으로. 무거운 list endpoint 들 대체.
     * 사용자 진입 시 N+1 fetch (그룹마다 사진 list) 가 메인 스레드를 막던 문제 해결.
     */
    @Transactional(readOnly = true)
    public MeStatsResponse getStats(Long userId) {
        long groupCount = groupMemberRepository.countByUserIdExcludingPersonal(userId);
        long sharedPhotoCount = photoRepository.countSharedPhotosForUser(userId);
        long upcomingEventCount = eventRepository.countUpcomingForUser(userId, Instant.now());
        return new MeStatsResponse(groupCount, sharedPhotoCount, upcomingEventCount);
    }

    @Transactional
    public UserMeResponse updateMe(Long userId, UpdateMeRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (req.nickname() != null) user.updateNickname(req.nickname());
        if (req.profileImageUrl() != null) user.updateProfileImage(req.profileImageUrl());
        if (req.personalColor() != null) user.updatePersonalColor(req.personalColor());
        return UserMeResponse.from(user);
    }

    /** 네이티브 푸시 token 등록/갱신/제거. null/blank 이면 제거. platform = "ios"|"android"|null (전송 분기용). */
    @Transactional
    public void updateExpoPushToken(Long userId, String token, String platform) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        String normalized = (token == null || token.isBlank()) ? null : token.trim();
        user.updateExpoPushToken(normalized);
        // 토큰 등록(비-null)일 때만 플랫폼 갱신 — clear(null) 시엔 기존 플랫폼 유지.
        if (normalized != null && platform != null && !platform.isBlank()) {
            user.updatePushPlatform(platform);
        }
    }

    /**
     * 회원탈퇴 — 본인 모든 데이터 hard delete.
     *
     * 정책 (옵션 1 확정):
     *  - OWNER 그룹은 자동 삭제 (그룹 안 모든 멤버 데이터까지 cleanup) — GroupService.delete 위임.
     *  - 멤버인 그룹은 떠나기 — GroupService.leave 위임 (본인 데이터 cleanup 됨).
     *  - 위 두 흐름이 일정/폴/응답/사진/그림자 다 정리해줌.
     *
     * 잔여 안전망 + 토큰/User row 삭제만 본 메서드에서 직접 처리.
     */
    @Transactional
    public void deleteMyAccount(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 1. 모든 소속 그룹 정리 — OWNER 는 그룹 삭제 (개인 캘린더 포함), 그 외는 떠나기.
        //    각 그룹의 본인 일정/폴/응답/사진/그림자 자동 cleanup.
        for (GroupMember gm : new ArrayList<>(groupMemberRepository.findAllByUserId(userId))) {
            Group group = groupRepository.findById(gm.getGroupId()).orElse(null);
            if (group == null) continue;
            if (group.isOwner(userId)) {
                groupService.delete(userId, gm.getGroupId());
            } else {
                groupService.leave(userId, gm.getGroupId());
            }
        }

        // 2. 잔여 안전망 — 어떤 그룹에도 안 잡힌 dangling 데이터 청소 (보통 0개)
        //    S3 파일은 커밋 후 삭제 (P1-5: 풀 점유/롤백 정합 — StorageCleanup 주석 참조)
        pollVoteRepository.deleteAllByUserId(userId);
        // 공개 링크 응답은 익명 기록으로 보존하고, 탈퇴 계정과의 연결만 제거한다.
        pollGuestParticipantRepository.unlinkAllByLinkedUserId(userId);
        ddayPinRepository.deleteAllByUserId(userId);   // 디데이 핀 — 개인 설정이라 본인 행만
        java.util.List<String> danglingKeys = new java.util.ArrayList<>();
        for (Photo photo : photoRepository.findAllByUploadedBy(userId)) {
            danglingKeys.add(photo.getStorageKey());
            photoRepository.delete(photo);
        }
        StorageCleanup.deleteAfterCommit(photoStorage, danglingKeys);
        // 2b. 본인 수신 알림 전부 삭제(죽은 참조 포함). 본인 생성 poll/event 의 타 수신자 알림은 위 그룹 cleanup 에서 처리됨(v1.3.1 #1).
        notificationService.deleteAllForUser(userId);

        // 3. RefreshToken
        refreshTokenStore.invalidate(userId);

        // 4. User
        userRepository.delete(user);
    }
}
