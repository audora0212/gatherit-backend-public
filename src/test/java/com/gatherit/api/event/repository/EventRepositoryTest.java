package com.gatherit.api.event.repository;

import com.gatherit.api.config.JpaConfig;
import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.entity.MemberRole;
import com.gatherit.api.group.repository.GroupMemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL 8 Testcontainers에서 EventGroup과 GroupMember를 조회하는 가시성 쿼리를 검증한다.
 * 일정이 조회되는 조건은 사용자가 해당 일정이 연결된 그룹의 멤버인지 여부다.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true) // 로컬에 Docker 없으면 skip, CI(Docker 有)에선 실행
class EventRepositoryTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @Autowired EventRepository eventRepository;
    @Autowired EventGroupRepository eventGroupRepository;
    @Autowired GroupMemberRepository groupMemberRepository;
    @Autowired TestEntityManager em;

    private static final long ME = 1L;
    private static final long SOMEONE_ELSE = 99L;
    private static final long MY_GROUP = 10L;
    private static final long OTHER_GROUP = 20L;

    private static final Instant FROM = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-01T00:00:00Z");

    // ---- helpers ----

    private Long event(long createdBy, long groupId, Instant start, Instant end) {
        return event(createdBy, groupId, start, end, null);
    }

    private Long event(long createdBy, long groupId, Instant start, Instant end, String rrule) {
        Event e = Event.builder()
                .title("일정").startAt(start).endAt(end)
                .createdBy(createdBy).allDay(false).rrule(rrule)
                .build();
        eventRepository.save(e);
        eventGroupRepository.save(EventGroup.builder().eventId(e.getId()).groupId(groupId).build());
        return e.getId();
    }

    private void member(long groupId, long userId) {
        groupMemberRepository.save(GroupMember.builder()
                .groupId(groupId).userId(userId).role(MemberRole.MEMBER).build());
    }

    private void sync() {
        em.flush();
        em.clear();
    }

    // ---- tests ----

    @Test
    @DisplayName("가시성은 그룹 멤버십 기준 — 내가 멤버인 그룹의 일정은 (남이 만들었어도) 보이고, 아닌 그룹 일정은 (내가 만들었어도) 안 보인다")
    void visibilityIsByGroupMembership_notCreator() {
        member(MY_GROUP, ME);                 // 나는 MY_GROUP 멤버, OTHER_GROUP 은 아님
        Long visible = event(SOMEONE_ELSE, MY_GROUP,    // 남이 만든 내 그룹 일정 → 보여야 함
                Instant.parse("2026-06-15T05:00:00Z"), Instant.parse("2026-06-15T06:00:00Z"));
        Long hidden = event(ME, OTHER_GROUP,            // 내가 만들었지만 멤버 아닌 그룹 → 안 보여야 함
                Instant.parse("2026-06-16T05:00:00Z"), Instant.parse("2026-06-16T06:00:00Z"));
        sync();

        List<Event> result = eventRepository.findVisibleEventsInRange(ME, FROM, TO);

        assertThat(result).extracting(Event::getId)
                .contains(visible)
                .doesNotContain(hidden);
    }

    @Test
    @DisplayName("범위 필터 — [from,to) 밖(과거 종료)의 일정은 제외")
    void excludesEventsOutsideRange() {
        member(MY_GROUP, ME);
        Long inRange = event(ME, MY_GROUP,
                Instant.parse("2026-06-15T05:00:00Z"), Instant.parse("2026-06-15T06:00:00Z"));
        Long past = event(ME, MY_GROUP,        // endAt < from → 제외
                Instant.parse("2026-05-01T05:00:00Z"), Instant.parse("2026-05-01T06:00:00Z"));
        sync();

        List<Event> result = eventRepository.findVisibleEventsInRange(ME, FROM, TO);

        assertThat(result).extracting(Event::getId)
                .contains(inRange)
                .doesNotContain(past);
    }

    @Test
    @DisplayName("반복 일정 원본은 지난달에 시작했어도 나중 달의 조회 결과에 포함한다")
    void includesRecurringSourceStartedBeforeRange() {
        member(MY_GROUP, ME);
        Instant juneStart = Instant.parse("2026-06-15T05:00:00Z");
        Instant juneEnd = Instant.parse("2026-06-15T06:00:00Z");
        Long recurring = event(ME, MY_GROUP, juneStart, juneEnd, "FREQ=WEEKLY;BYDAY=MO");
        Long oneOff = event(ME, MY_GROUP, juneStart, juneEnd);
        Long future = event(ME, MY_GROUP,
                Instant.parse("2026-10-15T05:00:00Z"), Instant.parse("2026-10-15T06:00:00Z"),
                "FREQ=WEEKLY;BYDAY=TH");
        sync();

        List<Event> result = eventRepository.findVisibleEventsInRangeIncludingRecurringSources(ME,
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(result).extracting(Event::getId)
                .contains(recurring)
                .doesNotContain(oneOff, future);
    }

    @Test
    @DisplayName("countUpcomingForUser — 내 그룹의 미래(startAt>now) 일정만 카운트")
    void countsOnlyUpcomingInMyGroups() {
        Instant now = Instant.parse("2026-06-10T00:00:00Z");
        member(MY_GROUP, ME);
        event(ME, MY_GROUP,                    // 미래 — 카운트
                Instant.parse("2026-06-15T05:00:00Z"), Instant.parse("2026-06-15T06:00:00Z"));
        event(ME, MY_GROUP,                    // 과거 — 제외
                Instant.parse("2026-06-05T05:00:00Z"), Instant.parse("2026-06-05T06:00:00Z"));
        event(ME, OTHER_GROUP,                 // 멤버 아닌 그룹의 미래 — 제외
                Instant.parse("2026-06-20T05:00:00Z"), Instant.parse("2026-06-20T06:00:00Z"));
        sync();

        long count = eventRepository.countUpcomingForUser(ME, now);

        assertThat(count).isEqualTo(1);
    }
}
