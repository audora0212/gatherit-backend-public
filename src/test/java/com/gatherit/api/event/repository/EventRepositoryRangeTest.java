package com.gatherit.api.event.repository;

import com.gatherit.api.config.JpaConfig;
import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.entity.MemberRole;
import com.gatherit.api.group.repository.GroupMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** Docker 없는 개발 환경에서도 캘린더 범위 쿼리의 RRULE 원본 포함 여부를 검증한다. */
@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@Import(JpaConfig.class)
@ActiveProfiles("test")
class EventRepositoryRangeTest {

    @Autowired EventRepository events;
    @Autowired EventGroupRepository eventGroups;
    @Autowired GroupMemberRepository groupMembers;
    @Autowired TestEntityManager em;

    @Test
    void includesOldRecurringSourceButNotOldOneOffOrOtherGroups() {
        groupMembers.save(GroupMember.builder()
                .userId(1L).groupId(10L).role(MemberRole.MEMBER).build());
        Long recurring = save(10L, "FREQ=WEEKLY;BYDAY=MO");
        Long oneOff = save(10L, null);
        Long invisible = save(20L, "FREQ=WEEKLY;BYDAY=MO");
        em.flush();
        em.clear();

        var from = Instant.parse("2026-09-01T00:00:00Z");
        var to = Instant.parse("2026-10-01T00:00:00Z");
        var oldAppResult = events.findVisibleEventsInRange(1L, from, to);
        var newAppResult = events.findVisibleEventsInRangeIncludingRecurringSources(1L,
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(oldAppResult).isEmpty();
        assertThat(newAppResult).extracting(Event::getId)
                .containsExactly(recurring)
                .doesNotContain(oneOff, invisible);
    }

    @Test
    void sharedRecurringShadowIsVisibleToTargetGroupMemberInLaterMonth() {
        groupMembers.save(GroupMember.builder()
                .userId(2L).groupId(20L).role(MemberRole.MEMBER).build());
        Long original = save(10L, "FREQ=WEEKLY;BYDAY=MO");
        Event shadow = events.save(Event.builder()
                .title("공유된 일정")
                .startAt(Instant.parse("2026-06-15T05:00:00Z"))
                .endAt(Instant.parse("2026-06-15T06:00:00Z"))
                .createdBy(1L)
                .shadowOfEventId(original)
                .rrule("FREQ=WEEKLY;BYDAY=MO")
                .build());
        eventGroups.save(EventGroup.builder().eventId(shadow.getId()).groupId(20L).build());
        em.flush();
        em.clear();

        var from = Instant.parse("2026-09-01T00:00:00Z");
        var to = Instant.parse("2026-10-01T00:00:00Z");
        assertThat(events.findVisibleEventsInRange(2L, from, to)).isEmpty();
        assertThat(events.findVisibleEventsInRangeIncludingRecurringSources(2L, from, to))
                .extracting(Event::getId)
                .containsExactly(shadow.getId())
                .doesNotContain(original);
    }

    private Long save(long groupId, String rrule) {
        Event event = events.save(Event.builder()
                .title("매주 일정")
                .startAt(Instant.parse("2026-06-15T05:00:00Z"))
                .endAt(Instant.parse("2026-06-15T06:00:00Z"))
                .createdBy(1L)
                .rrule(rrule)
                .build());
        eventGroups.save(EventGroup.builder().eventId(event.getId()).groupId(groupId).build());
        return event.getId();
    }
}
