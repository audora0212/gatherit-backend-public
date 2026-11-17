package compat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gatherit.api.auth.entity.RefreshToken;
import com.gatherit.api.auth.repository.RefreshTokenRepository;
import com.gatherit.api.auth.service.RefreshTokenStore;
import com.gatherit.api.common.entity.BaseEntity;
import com.gatherit.api.event.dto.EventResponse;
import com.gatherit.api.event.entity.Event;
import com.gatherit.api.event.entity.EventGroup;
import com.gatherit.api.exception.CustomException;
import com.gatherit.api.group.dto.GroupResponse;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.entity.MemberRole;
import com.gatherit.api.photo.dto.PhotoResponse;
import com.gatherit.api.photo.entity.Photo;
import com.gatherit.api.poll.dto.PollResponse;
import com.gatherit.api.poll.entity.ConfirmPolicy;
import com.gatherit.api.poll.entity.Poll;
import com.gatherit.api.poll.entity.PollSlot;
import com.gatherit.api.security.JwtUtils;
import com.gatherit.api.user.entity.OauthProvider;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.entity.UserRole;
import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic, isolated-DB fixtures shared verbatim by legacy and candidate JVMs.
 * Never starts the application, calls external providers, or prints credentials/tokens.
 * Actual project entities, DTO factories, JWT validation and refresh-store locks are used.
 */
public final class FixtureContract {
    static final int ENTITY_COUNT = 21;
    private static final String SIGNING_KEY = "db-compat-public-fixture-only-signing-key-at-least-32-bytes";
    private static final String PASSWORD = "db-compat-fixture-password-42!";
    private static final String LONG_PASSWORD = "b".repeat(73);
    // Public synthetic BCrypt 6.3.4 fixture; never a production account/password.
    private static final String LONG_HASH = "$2a$04$yRTkQaioAiYow4gIvrnaMu1bM28xC7IKNMgfKDv/jyJnh.IIXnHYq";
    private static final Instant FIXTURE_TIMESTAMP = Instant.parse("2026-01-01T00:00:00.123456Z");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private FixtureContract() {}

    public static List<Class<?>> entityTypes() {
        List<String> names = List.of(
                "com.gatherit.api.popup.Popup", "com.gatherit.api.announcement.Announcement",
                "com.gatherit.api.photo.entity.PhotoUploadReceipt", "com.gatherit.api.photo.entity.Photo",
                "com.gatherit.api.user.entity.User", "com.gatherit.api.group.entity.GroupMember",
                "com.gatherit.api.group.entity.Group",
                "com.gatherit.api.ai.entity.AiImportLog", "com.gatherit.api.event.entity.EventGroup",
                "com.gatherit.api.ai.entity.AiCommitReceipt", "com.gatherit.api.event.entity.DdayPin",
                "com.gatherit.api.event.entity.Event", "com.gatherit.api.auth.entity.RefreshToken",
                "com.gatherit.api.notification.entity.Notification",
                "com.gatherit.api.notification.entity.UserNotifPrefs",
                "com.gatherit.api.notification.entity.UserGroupNotifPref",
                "com.gatherit.api.poll.entity.Poll", "com.gatherit.api.poll.entity.PollGuestParticipant",
                "com.gatherit.api.poll.entity.PollGuestVote", "com.gatherit.api.poll.entity.PollSlot",
                "com.gatherit.api.poll.entity.PollVote");
        List<Class<?>> types = new ArrayList<>();
        for (String name : names) {
            try { types.add(Class.forName(name)); }
            catch (ClassNotFoundException error) {
                throw new IllegalStateException("Project entity missing from the isolated fixture classpath", error);
            }
        }
        return List.copyOf(types);
    }

    public static Map<String, String> seed(EntityManager em, String label) {
        requireTransaction(em);
        if (label == null || !label.matches("[a-zA-Z][a-zA-Z0-9_-]{0,20}")) {
            throw new IllegalArgumentException("Use a short synthetic fixture label");
        }
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = persist(em, User.builder().username(label + "_user")
                .email(label + "@example.invalid").nickname("일정 친구 ☕ " + label)
                .passwordHash(encoder.encode(PASSWORD)).oauthProvider(OauthProvider.LOCAL).build());
        User longUser = persist(em, User.builder().username(label + "_long")
                .email(label + "_long@example.invalid").nickname("기존 장문 " + label)
                .passwordHash(LONG_HASH).oauthProvider(OauthProvider.LOCAL).build());
        User oauth = persist(em, User.builder().nickname("메일 없는 소셜 " + label)
                .oauthProvider(OauthProvider.GOOGLE).oauthId("fixture-" + label).build());
        user.updatePersonalColor("#8A76DF");

        Group group = persist(em, Group.builder().name("달력 공유 & 모임 ☕ " + label)
                .colorKey(GroupColor.team).createdBy(user.getId()).personal(false).build());
        persist(em, GroupMember.builder().groupId(group.getId())
                .userId(user.getId()).role(MemberRole.OWNER).colorKey(null).build());

        Event timed = persist(em, Event.builder().title("월 경계 마이크로초 " + label)
                .startAt(Instant.parse("2026-11-30T14:59:59.123456Z"))
                .endAt(Instant.parse("2026-11-30T15:30:00.654321Z"))
                .createdBy(user.getId()).colorKey(GroupColor.team).location(null).memo("한국어 & emoji ☕")
                .latitude(37.5665).longitude(126.978).allDay(false).reminderMinutesBefore(0).build());
        Event allDay = persist(em, Event.builder().title("새해 종일 " + label)
                .startAt(Instant.parse("2026-12-31T15:00:00Z"))
                .endAt(Instant.parse("2027-01-01T14:59:59Z"))
                .createdBy(user.getId()).colorKey(null).allDay(true).reminderAllDayDays(-1).build());
        Event recurring = persist(em, Event.builder().title("반복 일정 " + label)
                .startAt(Instant.parse("2026-01-31T15:00:00.123456Z"))
                .endAt(Instant.parse("2026-01-31T16:00:00.654321Z"))
                .createdBy(user.getId()).colorKey(GroupColor.family)
                .rrule("FREQ=WEEKLY;BYDAY=SU;COUNT=8").allDay(false).build());
        recurring.addExdate(Instant.parse("2026-02-07T15:00:00.123456Z"));
        recurring.addExdate(Instant.parse("2026-02-14T15:00:00.123456Z"));
        Event override = persist(em, Event.builder().title("변경 회차 " + label)
                .startAt(Instant.parse("2026-02-07T17:00:00.123456Z"))
                .endAt(Instant.parse("2026-02-07T18:00:00.654321Z"))
                .createdBy(user.getId()).recurrenceParentId(recurring.getId())
                .recurrenceId(Instant.parse("2026-02-07T15:00:00.123456Z")).allDay(false).build());
        for (Event event : List.of(timed, allDay, recurring, override)) {
            persist(em, EventGroup.builder().eventId(event.getId()).groupId(group.getId()).build());
        }

        Poll poll = persist(em, Poll.builder().groupId(group.getId()).createdBy(user.getId())
                .title("언제 만날까요? " + label).confirmPolicy(ConfirmPolicy.PROPOSER_ONLY)
                .deadlineAt(Instant.parse("2026-11-30T15:00:00.123456Z")).build());
        // Two seed labels need distinct public-link tokens under the real unique index.
        poll.enablePublicShare(HexFormat.of().formatHex(digest(label)).substring(0, 32),
                Instant.parse("2026-12-07T15:00:00.654321Z"));
        PollSlot slot = persist(em, PollSlot.builder().pollId(poll.getId())
                .startAt(timed.getStartAt()).endAt(timed.getEndAt()).build());
        Photo photo = persist(em, Photo.builder().groupId(group.getId()).uploadedBy(user.getId())
                .storageKey("fixture-only/" + label + "/photo.jpg").mimeType("image/jpeg")
                .originalName("모임 사진 ☕.jpg").sizeBytes(12345L)
                .uploadedAt(Instant.parse("2026-11-30T14:59:59.123456Z"))
                .expiresAt(Instant.parse("2026-12-07T14:59:59.654321Z")).build());

        JwtUtils jwt = jwt();
        String token = jwt.generateRefreshToken(user.getId());
        RefreshToken session = RefreshToken.builder().userId(user.getId()).token(token)
                .expiresAt(jwt.getExpiration(token)).build();
        persist(em, session);
        em.flush();

        Map<String, String> state = new LinkedHashMap<>();
        state.put("label", label);
        state.put("userId", user.getId().toString());
        state.put("legacyUserId", longUser.getId().toString());
        state.put("oauthUserId", oauth.getId().toString());
        state.put("groupId", group.getId().toString());
        state.put("timedEventId", timed.getId().toString());
        state.put("allDayEventId", allDay.getId().toString());
        state.put("recurringEventId", recurring.getId().toString());
        state.put("overrideEventId", override.getId().toString());
        state.put("pollId", poll.getId().toString());
        state.put("slotId", slot.getId().toString());
        state.put("photoId", photo.getId().toString());
        state.put("activeToken", token);
        state.put("accessToken", jwt.generateAccessToken(user.getId(), user.getEmail()));
        state.put("password", PASSWORD);
        state.put("longPassword", LONG_PASSWORD);
        state.put("clockBase", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        state.put("clockOffsetSeconds", "0");
        // Snapshot must observe actual database round-trips, not the pre-flush Java instances.
        em.clear();
        return state;
    }

    public static Map<String, Object> snapshot(EntityManager em, Map<String, String> state) {
        User user = load(em, User.class, state, "userId");
        User legacy = load(em, User.class, state, "legacyUserId");
        User oauth = load(em, User.class, state, "oauthUserId");
        Group group = load(em, Group.class, state, "groupId");
        Event timed = load(em, Event.class, state, "timedEventId");
        Event allDay = load(em, Event.class, state, "allDayEventId");
        Event recurring = load(em, Event.class, state, "recurringEventId");
        Event override = load(em, Event.class, state, "overrideEventId");
        Poll poll = load(em, Poll.class, state, "pollId");
        PollSlot slot = load(em, PollSlot.class, state, "slotId");
        Photo photo = load(em, Photo.class, state, "photoId");
        RefreshToken session = load(em, RefreshToken.class, state, "userId");
        GroupMember membership = em.find(GroupMember.class, new GroupMember.PK(group.getId(), user.getId()));
        require(membership != null, "Fixture group membership missing");

        require(timed.getStartAt().equals(Instant.parse("2026-11-30T14:59:59.123456Z")),
                "Timed Instant or microsecond precision changed");
        require(allDay.getStartAt().equals(Instant.parse("2026-12-31T15:00:00Z")),
                "KST all-day year boundary changed");
        require(recurring.getExdatesList().equals(List.of(Instant.parse("2026-02-07T15:00:00.123456Z"),
                Instant.parse("2026-02-14T15:00:00.123456Z"))), "Recurrence exclusions changed");

        List<Long> groups = List.of(group.getId());
        List<Map<String, Object>> events = List.of(timed, allDay, recurring, override).stream()
                .map(event -> json(EventResponse.ofList(event, groups, event.getColorKey())
                        .withCreator(user.getNickname(), user.getPersonalColor()))).toList();
        PollResponse pollResponse = PollResponse.of(poll, false, List.of(new PollResponse.SlotResult(
                slot.getId(), slot.getStartAt(), slot.getEndAt(), 1, List.of(user.getId()), List.of())),
                1, 1, List.of());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("users", List.of(userSnapshot(user), userSnapshot(legacy), userSnapshot(oauth)));
        result.put("group", json(GroupResponse.of(group, 1, membership.getColorKey())));
        result.put("events", events);
        result.put("poll", json(pollResponse));
        result.put("pollPersistence", values("version", poll.getVersion(),
                "publicShareToken", poll.getPublicShareToken(),
                "publicShareExpiresAt", iso(poll.getPublicShareExpiresAt()),
                "deadlineNotified", poll.isDeadlineNotified()));
        result.put("photo", json(PhotoResponse.of(photo, "/api/photos/" + photo.getId() + "/file")));
        result.put("session", values("userId", session.getUserId(),
                "tokenSha256", fingerprint(session.getToken()),
                "expiresAt", iso(session.getExpiresAt()),
                "previousTokenSha256", fingerprint(session.getPreviousToken()),
                "previousTokenExpiresAt", iso(session.getPreviousTokenExpiresAt()),
                "recoveryKeyHash", session.getRecoveryKeyHash(),
                "recoveryExpiresAt", iso(session.getRecoveryExpiresAt()),
                "createdAt", iso(session.getCreatedAt()), "updatedAt", iso(session.getUpdatedAt())));
        result.put("timestamps", values("userCreatedAt", iso(user.getCreatedAt()),
                "groupCreatedAt", iso(group.getCreatedAt()), "eventUpdatedAt", iso(timed.getUpdatedAt()),
                "pollUpdatedAt", iso(poll.getUpdatedAt()), "photoUpdatedAt", iso(photo.getUpdatedAt())));
        return result;
    }

    public static void verifyPasswords(EntityManager em, Map<String, String> state) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = load(em, User.class, state, "userId");
        User legacy = load(em, User.class, state, "legacyUserId");
        User oauth = load(em, User.class, state, "oauthUserId");
        require(encoder.matches(state.get("password"), user.getPasswordHash()), "Password compatibility failed");
        require(!encoder.matches("wrong-fixture-password", user.getPasswordHash()), "Wrong password accepted");
        require(encoder.matches(state.get("longPassword"), legacy.getPasswordHash()),
                "Legacy long BCrypt hash no longer matches");
        require(!encoder.matches("different-prefix", legacy.getPasswordHash()), "Wrong legacy password accepted");
        require(oauth.getEmail() == null && oauth.getUsername() == null && oauth.getPasswordHash() == null,
                "Nullable OAuth identity contract changed");
        require(user.getRole() == UserRole.USER, "Default user role changed");
        JwtUtils jwt = jwt();
        require(jwt.validateAccessToken(state.get("accessToken")), "Fixture access token no longer verifies");
        require(jwt.validateRefreshToken(state.get("activeToken")), "Fixture refresh token no longer verifies");
        require(jwt.getUserId(state.get("activeToken")).equals(user.getId()), "JWT principal changed");
        boolean rejected = false;
        try { jwt.validateAccessToken(state.get("activeToken")); }
        catch (CustomException expected) { rejected = true; }
        require(rejected, "Refresh token was accepted as an access credential");
    }

    public static Map<String, String> rotate(EntityManager em, Map<String, String> state,
                                              String presentedToken, String attemptId) {
        requireTransaction(em);
        JwtUtils jwt = jwt();
        Long userId = Long.valueOf(state.get("userId"));
        require(jwt.validateRefreshToken(presentedToken) && jwt.getUserId(presentedToken).equals(userId),
                "Presented fixture refresh token failed purpose/principal checks");
        RefreshTokenRepository repository = new JpaRepositoryFactory(em).getRepository(RefreshTokenRepository.class);
        RefreshTokenStore store = new RefreshTokenStore(repository, em, jwt);
        Instant now = Instant.parse(state.get("clockBase"))
                .plusSeconds(Long.parseLong(state.getOrDefault("clockOffsetSeconds", "0")));
        set(store, "clock", Clock.fixed(now, ZoneOffset.UTC));
        set(store, "refreshGraceMs", 60_000L);
        set(store, "refreshRecoveryMs", 604_800_000L);
        RefreshTokenStore.RotationResult rotated = store.refresh(userId, presentedToken, attemptId);
        em.flush();
        em.clear();
        RefreshToken persisted = em.find(RefreshToken.class, userId);
        require(persisted != null && persisted.getToken().equals(rotated.token()),
                "Rotated session was not persisted");
        Map<String, String> result = new LinkedHashMap<>();
        result.put("activeToken", rotated.token());
        result.put("outcome", rotated.outcome());
        if (persisted.getPreviousToken() != null) result.put("previousToken", persisted.getPreviousToken());
        return result;
    }

    private static Map<String, Object> userSnapshot(User user) {
        return values("id", user.getId(), "email", user.getEmail(), "username", user.getUsername(),
                "nickname", user.getNickname(), "provider", user.getOauthProvider().name(),
                "oauthId", user.getOauthId(), "role", user.getRole().name(),
                "passwordHashSha256", fingerprint(user.getPasswordHash()),
                "personalColor", user.getPersonalColor(), "createdAt", iso(user.getCreatedAt()),
                "updatedAt", iso(user.getUpdatedAt()));
    }

    private static JwtUtils jwt() {
        JwtUtils jwt = new JwtUtils();
        set(jwt, "secret", SIGNING_KEY);
        set(jwt, "expiration", 3_600_000L);
        set(jwt, "refreshExpiration", 31_536_000_000L);
        jwt.init();
        return jwt;
    }

    private static <T> T persist(EntityManager em, T entity) {
        if (entity instanceof BaseEntity) {
            set(entity, "createdAt", FIXTURE_TIMESTAMP);
            set(entity, "updatedAt", FIXTURE_TIMESTAMP);
        }
        em.persist(entity);
        em.flush();
        return entity;
    }

    private static <T> T load(EntityManager em, Class<T> type, Map<String, String> state, String idKey) {
        T entity = em.find(type, Long.valueOf(state.get(idKey)));
        require(entity != null, "A synthetic fixture row is missing");
        return entity;
    }

    private static Map<String, Object> json(Object value) { return JSON.convertValue(value, JSON_MAP); }

    private static Map<String, Object> values(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    private static String iso(Instant value) { return value == null ? null : value.toString(); }
    private static String fingerprint(String value) {
        return value == null ? null : HexFormat.of().formatHex(digest(value));
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static void set(Object target, String fieldName, Object value) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Continue to the mapped superclass for timestamp fields.
            } catch (IllegalAccessException error) { throw new IllegalStateException(error); }
        }
        throw new IllegalStateException("Fixture field missing: " + fieldName);
    }

    private static void requireTransaction(EntityManager em) {
        require(em.getTransaction().isActive(), "Fixture writes require an explicit isolated transaction");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
