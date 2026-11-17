package com.gatherit.api.poll.repository;

import com.gatherit.api.poll.entity.PollGuestParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PollGuestParticipantRepository extends JpaRepository<PollGuestParticipant, Long> {

    @Query("SELECT p FROM PollGuestParticipant p WHERE p.poll.id = :pollId ORDER BY p.createdAt ASC")
    List<PollGuestParticipant> findAllByPollId(@Param("pollId") Long pollId);

    @Query("SELECT p FROM PollGuestParticipant p WHERE p.poll.id IN :pollIds ORDER BY p.createdAt ASC")
    List<PollGuestParticipant> findAllByPollIdIn(@Param("pollIds") Collection<Long> pollIds);

    @Query("SELECT p FROM PollGuestParticipant p WHERE p.poll.id = :pollId AND p.editTokenHash = :hash")
    Optional<PollGuestParticipant> findByPollIdAndEditTokenHash(
            @Param("pollId") Long pollId, @Param("hash") String hash);

    @Query("SELECT p FROM PollGuestParticipant p WHERE p.poll.id = :pollId AND p.handoffCodeHash = :hash")
    Optional<PollGuestParticipant> findByPollIdAndHandoffCodeHash(
            @Param("pollId") Long pollId, @Param("hash") String hash);

    Optional<PollGuestParticipant> findByHandoffCodeHash(String handoffCodeHash);

    boolean existsByHandoffCodeHash(String handoffCodeHash);

    @Query("SELECT p FROM PollGuestParticipant p WHERE p.poll.id = :pollId AND p.linkedUserId = :userId")
    Optional<PollGuestParticipant> findByPollIdAndLinkedUserId(
            @Param("pollId") Long pollId, @Param("userId") Long userId);

    @Query("SELECT p FROM PollGuestParticipant p JOIN FETCH p.poll " +
            "WHERE p.linkedUserId = :userId ORDER BY p.createdAt DESC")
    List<PollGuestParticipant> findAllByLinkedUserId(@Param("userId") Long userId);

    @Query("SELECT p FROM PollGuestParticipant p JOIN FETCH p.poll " +
            "WHERE p.linkedUserId = :userId AND p.poll.groupId = :groupId ORDER BY p.createdAt ASC")
    List<PollGuestParticipant> findAllLinkedByUserIdAndGroupId(
            @Param("userId") Long userId, @Param("groupId") Long groupId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PollGuestParticipant p SET p.linkedUserId = null, " +
            "p.handoffCodeHash = null, p.handoffExpiresAt = null WHERE p.linkedUserId = :userId")
    int unlinkAllByLinkedUserId(@Param("userId") Long userId);

    @Query("SELECT COUNT(p) > 0 FROM PollGuestParticipant p " +
            "WHERE p.poll.id = :pollId AND LOWER(p.nickname) = LOWER(:nickname)")
    boolean existsNickname(@Param("pollId") Long pollId, @Param("nickname") String nickname);

    @Query("SELECT COUNT(p) > 0 FROM PollGuestParticipant p " +
            "WHERE p.poll.id = :pollId AND LOWER(p.nickname) = LOWER(:nickname) AND p.id <> :participantId")
    boolean existsNicknameExcept(@Param("pollId") Long pollId,
                                 @Param("nickname") String nickname,
                                 @Param("participantId") Long participantId);
}
