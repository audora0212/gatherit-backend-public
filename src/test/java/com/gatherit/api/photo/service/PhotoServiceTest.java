package com.gatherit.api.photo.service;

import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupColor;
import com.gatherit.api.group.entity.PhotoDeletePolicy;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.photo.dto.PhotoResponse;
import com.gatherit.api.photo.entity.Photo;
import com.gatherit.api.photo.entity.PhotoUploadReceipt;
import com.gatherit.api.photo.repository.PhotoRepository;
import com.gatherit.api.photo.repository.PhotoUploadReceiptRepository;
import com.gatherit.api.photo.storage.PhotoStorage;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * PhotoService 단위 테스트.
 *
 * 커버 항목:
 *  - uploadBatch: 비멤버 차단 / 멱등 batchId 재사용 / 빈 목록 / 30장 초과 / 100장 quota / 10MB 초과 / MIME 무효 /
 *                 magic byte 불일치 / 스토리지 실패 rollback / 정상 업로드
 *  - listByGroup: 비멤버 차단 / 정상 반환
 *  - loadFile: 사진 미존재 / 비멤버 차단 / 스토리지 파일 없음 / 정상
 *  - downloadZip: 비멤버 차단 / 빈 목록 / 100장 초과 / 다른 그룹 사진 끼워팔기 차단 / 정상
 *  - delete(단건): 사진 미존재 / 비멤버 차단 / UPLOADER_ONLY 정책에서 타인 차단 / ANYONE 정책 정상
 *  - deleteBatch: 비멤버 차단 / 100장 초과 / UPLOADER_ONLY 타인 사진 skip / 그룹 다른 사진 skip / 정상 삭제
 *  - deleteExpiredBatch: 만료 사진 삭제
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PhotoServiceTest {

    private static final long USER    = 1L;
    private static final long OTHER   = 2L;
    private static final long GROUP   = 10L;
    private static final long OTHER_GROUP = 20L;
    private static final long PHOTO_ID = 100L;

    @Mock PhotoRepository photoRepository;
    @Mock PhotoUploadReceiptRepository receiptRepository;
    @Mock GroupMemberRepository groupMemberRepository;
    @Mock GroupRepository groupRepository;
    @Mock NotificationService notificationService;
    @Mock UserRepository userRepository;
    @Mock PhotoStorage storage;
    @Mock PlatformTransactionManager txManager;

    PhotoService service;

    // ── setup ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() throws Exception {
        service = new PhotoService(
                photoRepository, receiptRepository, groupMemberRepository, groupRepository,
                notificationService, userRepository, storage, txManager);
        // initTx() 는 @PostConstruct — 직접 호출
        ReflectionTestUtils.invokeMethod(service, "initTx");
        // expiryDays 기본값
        ReflectionTestUtils.setField(service, "expiryDays", 7);

        // txTemplate.execute 는 콜백을 그대로 실행하는 stub (실 DB 없음)
        TransactionTemplate spyTx = spy(new TransactionTemplate(txManager));
        doAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            TransactionStatus status = mock(TransactionStatus.class);
            return cb.doInTransaction(status);
        }).when(spyTx).execute(any());
        ReflectionTestUtils.setField(service, "txTemplate", spyTx);

        // 기본: USER 는 GROUP 멤버
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, USER)).thenReturn(true);
        // 기본: GROUP 은 ANYONE 정책
        Group group = buildGroup(GROUP, PhotoDeletePolicy.ANYONE, USER);
        when(groupRepository.findById(GROUP)).thenReturn(Optional.of(group));
        // 기본: User 닉네임
        User user = User.builder().nickname("테스터").build();
        when(userRepository.findById(USER)).thenReturn(Optional.of(user));
        // 기본: 그룹 사진 0장
        when(photoRepository.countByGroupId(GROUP)).thenReturn(0L);
        // 기본: Photo.save 반환은 저장 인수 그대로
        when(photoRepository.save(any(Photo.class))).thenAnswer(inv -> {
            Photo p = inv.getArgument(0);
            ReflectionTestUtils.setField(p, "id", PHOTO_ID);
            return p;
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ErrorCode codeOf(Throwable t) {
        return ((CustomException) t).getErrorCode();
    }

    private Group buildGroup(Long id, PhotoDeletePolicy policy, Long ownerId) {
        Group g = Group.builder()
                .name("테스트그룹")
                .colorKey(GroupColor.team)
                .createdBy(ownerId)
                .personal(false)
                .photoDeletePolicy(policy)
                .build();
        ReflectionTestUtils.setField(g, "id", id);
        return g;
    }

    private Photo buildPhoto(Long id, Long groupId, Long uploadedBy) {
        Photo p = Photo.builder()
                .groupId(groupId)
                .uploadedBy(uploadedBy)
                .storageKey("groups/%d/abc.jpg".formatted(groupId))
                .mimeType("image/jpeg")
                .originalName("photo.jpg")
                .sizeBytes(1024L)
                .uploadedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(86400 * 7))
                .build();
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    /** JPEG 매직 바이트(FF D8 FF) + 패딩으로 12바이트 */
    private static byte[] jpegMagic() {
        return new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    }

    /** PNG 매직 바이트(89 50 4E 47 0D 0A 1A 0A) + 패딩 */
    private static byte[] pngMagic() {
        return new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    }

    private MultipartFile mockJpegFile(long sizeBytes) {
        MultipartFile f = mock(MultipartFile.class);
        when(f.getContentType()).thenReturn("image/jpeg");
        when(f.getSize()).thenReturn(sizeBytes);
        when(f.getOriginalFilename()).thenReturn("photo.jpg");
        try {
            when(f.getInputStream()).thenReturn(new ByteArrayInputStream(jpegMagic()));
        } catch (IOException ignored) {}
        return f;
    }

    private MultipartFile mockFileWithMime(String mime, byte[] magic, long sizeBytes) {
        MultipartFile f = mock(MultipartFile.class);
        when(f.getContentType()).thenReturn(mime);
        when(f.getSize()).thenReturn(sizeBytes);
        when(f.getOriginalFilename()).thenReturn("photo.bin");
        try {
            when(f.getInputStream()).thenReturn(new ByteArrayInputStream(magic));
        } catch (IOException ignored) {}
        return f;
    }

    // =========================================================================
    // uploadBatch
    // =========================================================================

    @Test
    @DisplayName("uploadBatch — 비멤버는 NOT_GROUP_MEMBER")
    void upload_nonMember_forbidden() throws Exception {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, OTHER)).thenReturn(false);

        assertThatThrownBy(() -> service.uploadBatch(OTHER, GROUP, List.of(mockJpegFile(1024)), null))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("uploadBatch — 같은 batchId 재요청 시 기존 사진 반환(멱등)")
    void upload_idempotent_batchId() throws Exception {
        Photo existing = buildPhoto(PHOTO_ID, GROUP, USER);
        PhotoUploadReceipt receipt = new PhotoUploadReceipt(USER, "batch-uuid-1", List.of(PHOTO_ID));
        when(receiptRepository.findByUserIdAndBatchId(USER, "batch-uuid-1")).thenReturn(Optional.of(receipt));
        when(photoRepository.findAllById(List.of(PHOTO_ID))).thenReturn(List.of(existing));

        List<PhotoResponse> result = service.uploadBatch(USER, GROUP, List.of(), "batch-uuid-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(PHOTO_ID);
        verify(storage, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("uploadBatch — 빈 파일 목록은 PHOTO_BATCH_TOO_LARGE")
    void upload_emptyList_batchTooLarge() throws Exception {
        assertThatThrownBy(() -> service.uploadBatch(USER, GROUP, List.of(), null))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_BATCH_TOO_LARGE);
    }

    @Test
    @DisplayName("uploadBatch — 31장 초과 시 PHOTO_BATCH_TOO_LARGE")
    void upload_over30Files_batchTooLarge() throws Exception {
        List<MultipartFile> files = java.util.stream.IntStream.range(0, 31)
                .mapToObj(i -> mockJpegFile(1024))
                .toList();

        assertThatThrownBy(() -> service.uploadBatch(USER, GROUP, files, null))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_BATCH_TOO_LARGE);
    }

    @Test
    @DisplayName("uploadBatch — 그룹 100장 quota 초과 시 PHOTO_QUOTA_EXCEEDED")
    void upload_quotaExceeded() throws Exception {
        when(photoRepository.countByGroupId(GROUP)).thenReturn(99L);
        List<MultipartFile> files = List.of(mockJpegFile(1024), mockJpegFile(1024));

        assertThatThrownBy(() -> service.uploadBatch(USER, GROUP, files, null))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_QUOTA_EXCEEDED);
    }

    @Test
    @DisplayName("uploadBatch — 10MB 초과 파일은 PHOTO_TOO_LARGE")
    void upload_fileTooLarge() throws Exception {
        long overLimit = PhotoService.FILE_SIZE_LIMIT + 1;
        assertThatThrownBy(() -> service.uploadBatch(USER, GROUP, List.of(mockJpegFile(overLimit)), null))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_TOO_LARGE);
    }

    @Test
    @DisplayName("uploadBatch — 허용되지 않는 MIME 타입은 PHOTO_INVALID_TYPE")
    void upload_invalidMimeType() throws Exception {
        MultipartFile f = mock(MultipartFile.class);
        when(f.getContentType()).thenReturn("video/mp4");
        when(f.getSize()).thenReturn(1024L);

        assertThatThrownBy(() -> service.uploadBatch(USER, GROUP, List.of(f), null))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_INVALID_TYPE);
    }

    @Test
    @DisplayName("uploadBatch — magic byte 불일치(JPEG 헤더인데 PNG 파일) → PHOTO_INVALID_TYPE")
    void upload_magicByteMismatch_invalidType() throws Exception {
        // MIME = image/jpeg 이지만 실제 바이트는 PNG
        MultipartFile f = mockFileWithMime("image/jpeg", pngMagic(), 1024);

        assertThatThrownBy(() -> service.uploadBatch(USER, GROUP, List.of(f), null))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_INVALID_TYPE);
    }

    @Test
    @DisplayName("uploadBatch — 스토리지 put 실패 시 이미 업로드된 키 cleanup 후 PHOTO_STORAGE_ERROR")
    void upload_storagePutFails_cleansUpAndThrows() throws IOException {
        MultipartFile f1 = mockJpegFile(1024);
        MultipartFile f2 = mockJpegFile(1024);

        // 첫 번째는 성공, 두 번째에서 IOException
        doNothing().when(storage).put(anyString(), eq(f1));
        doThrow(new IOException("S3 down")).when(storage).put(anyString(), eq(f2));

        assertThatThrownBy(() -> service.uploadBatch(USER, GROUP, List.of(f1, f2), null))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_STORAGE_ERROR);

        // 이미 업로드된 f1 key cleanup 검증
        verify(storage, times(1)).deleteSafe(anyString());
    }

    @Test
    @DisplayName("uploadBatch — 정상 업로드 시 PhotoResponse 목록 반환 + storage.put 호출")
    void upload_success_returnsResponses() throws IOException {
        MultipartFile f = mockJpegFile(1024);
        // groupMemberRepository.findAllByGroupId 로 알림 대상 조회 (알림은 부작용)
        when(groupMemberRepository.findAllByGroupId(GROUP)).thenReturn(List.of());

        List<PhotoResponse> result = service.uploadBatch(USER, GROUP, List.of(f), null);

        verify(storage, times(1)).put(anyString(), eq(f));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).groupId()).isEqualTo(GROUP);
        assertThat(result.get(0).url()).startsWith("/api/photos/");
    }

    @Test
    @DisplayName("uploadBatch — batchId 있으면 receipt 저장")
    void upload_withBatchId_savesReceipt() throws IOException {
        MultipartFile f = mockJpegFile(1024);
        when(groupMemberRepository.findAllByGroupId(GROUP)).thenReturn(List.of());

        service.uploadBatch(USER, GROUP, List.of(f), "my-batch-id");

        verify(receiptRepository).save(any(PhotoUploadReceipt.class));
    }

    // =========================================================================
    // listByGroup
    // =========================================================================

    @Test
    @DisplayName("listByGroup — 비멤버는 NOT_GROUP_MEMBER")
    void listByGroup_nonMember_forbidden() throws Exception {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, OTHER)).thenReturn(false);

        assertThatThrownBy(() -> service.listByGroup(OTHER, GROUP))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("listByGroup — 멤버는 그룹 사진 목록 반환")
    void listByGroup_member_returnsList() throws Exception {
        Photo p = buildPhoto(PHOTO_ID, GROUP, USER);
        when(photoRepository.findAllByGroupIdOrderByUploadedAtDesc(GROUP)).thenReturn(List.of(p));

        List<PhotoResponse> result = service.listByGroup(USER, GROUP);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(PHOTO_ID);
    }

    // =========================================================================
    // loadFile
    // =========================================================================

    @Test
    @DisplayName("loadFile — 사진 ID 없으면 PHOTO_NOT_FOUND")
    void loadFile_photoNotFound() throws Exception {
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadFile(USER, PHOTO_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_NOT_FOUND);
    }

    @Test
    @DisplayName("loadFile — 사진 그룹의 비멤버는 NOT_GROUP_MEMBER")
    void loadFile_nonMemberOfPhotoGroup_forbidden() throws Exception {
        Photo p = buildPhoto(PHOTO_ID, GROUP, USER);
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.of(p));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, OTHER)).thenReturn(false);

        assertThatThrownBy(() -> service.loadFile(OTHER, PHOTO_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("loadFile — 스토리지에 파일 없으면 PHOTO_NOT_FOUND")
    void loadFile_fileNotInStorage_notFound() throws Exception {
        Photo p = buildPhoto(PHOTO_ID, GROUP, USER);
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.of(p));
        when(storage.exists(p.getStorageKey())).thenReturn(false);

        assertThatThrownBy(() -> service.loadFile(USER, PHOTO_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_NOT_FOUND);
    }

    @Test
    @DisplayName("loadFile — 정상: PhotoFile 반환")
    void loadFile_success() throws IOException {
        Photo p = buildPhoto(PHOTO_ID, GROUP, USER);
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.of(p));
        when(storage.exists(p.getStorageKey())).thenReturn(true);
        when(storage.size(p.getStorageKey())).thenReturn(1024L);
        when(storage.open(p.getStorageKey())).thenReturn(new ByteArrayInputStream(new byte[10]));

        PhotoService.PhotoFile file = service.loadFile(USER, PHOTO_ID);

        assertThat(file.contentType()).isEqualTo("image/jpeg");
        assertThat(file.contentLength()).isEqualTo(1024L);
        assertThat(file.filename()).isEqualTo("photo.jpg");
    }

    // =========================================================================
    // downloadZip
    // =========================================================================

    @Test
    @DisplayName("downloadZip — 비멤버는 NOT_GROUP_MEMBER")
    void downloadZip_nonMember_forbidden() throws Exception {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, OTHER)).thenReturn(false);

        assertThatThrownBy(() -> service.downloadZip(OTHER, GROUP, List.of(PHOTO_ID)))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("downloadZip — 빈 목록은 PHOTO_BATCH_TOO_LARGE")
    void downloadZip_emptyList_batchTooLarge() throws Exception {
        assertThatThrownBy(() -> service.downloadZip(USER, GROUP, List.of()))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_BATCH_TOO_LARGE);
    }

    @Test
    @DisplayName("downloadZip — 101장 이상은 PHOTO_BATCH_TOO_LARGE")
    void downloadZip_over100_batchTooLarge() throws Exception {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();

        assertThatThrownBy(() -> service.downloadZip(USER, GROUP, ids))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_BATCH_TOO_LARGE);
    }

    @Test
    @DisplayName("downloadZip — 다른 그룹 사진 ID 포함 시 PHOTO_FORBIDDEN")
    void downloadZip_crossGroupPhoto_forbidden() throws Exception {
        Photo foreignPhoto = buildPhoto(PHOTO_ID, OTHER_GROUP, OTHER);
        when(photoRepository.findAllById(List.of(PHOTO_ID))).thenReturn(List.of(foreignPhoto));

        assertThatThrownBy(() -> service.downloadZip(USER, GROUP, List.of(PHOTO_ID)))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_FORBIDDEN);
    }

    @Test
    @DisplayName("downloadZip — 정상: PhotoFile(zip) 반환")
    void downloadZip_success_returnsZipFile() throws Exception {
        Photo p = buildPhoto(PHOTO_ID, GROUP, USER);
        when(photoRepository.findAllById(List.of(PHOTO_ID))).thenReturn(List.of(p));

        PhotoService.PhotoFile file = service.downloadZip(USER, GROUP, List.of(PHOTO_ID));

        assertThat(file.contentType()).isEqualTo("application/zip");
        assertThat(file.filename()).contains("gatherit-photos-%d".formatted(GROUP));
    }

    // =========================================================================
    // delete (단건)
    // =========================================================================

    @Test
    @DisplayName("delete — 사진 없으면 PHOTO_NOT_FOUND")
    void delete_photoNotFound() throws Exception {
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(USER, PHOTO_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_NOT_FOUND);
    }

    @Test
    @DisplayName("delete — 그룹 비멤버는 PHOTO_FORBIDDEN")
    void delete_nonMember_forbidden() throws Exception {
        Photo p = buildPhoto(PHOTO_ID, GROUP, USER);
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.of(p));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, OTHER)).thenReturn(false);

        assertThatThrownBy(() -> service.delete(OTHER, PHOTO_ID))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_FORBIDDEN);
    }

    @Test
    @DisplayName("delete — UPLOADER_ONLY 정책에서 타인이 삭제 시도 → PHOTO_FORBIDDEN")
    void delete_uploaderOnlyPolicy_otherUser_forbidden() throws Exception {
        Photo p = buildPhoto(PHOTO_ID, GROUP, USER);  // uploadedBy = USER
        Group uploaderOnlyGroup = buildGroup(GROUP, PhotoDeletePolicy.UPLOADER_ONLY, USER);
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.of(p));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, OTHER)).thenReturn(true);
        when(groupRepository.findById(GROUP)).thenReturn(Optional.of(uploaderOnlyGroup));

        assertThatThrownBy(() -> service.delete(OTHER, PHOTO_ID))  // OTHER != USER(uploader)
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_FORBIDDEN);
    }

    @Test
    @DisplayName("delete — ANYONE 정책에서 타인도 삭제 가능")
    void delete_anyonePolicy_otherUser_success() throws Exception {
        Photo p = buildPhoto(PHOTO_ID, GROUP, USER);  // uploadedBy = USER
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.of(p));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, OTHER)).thenReturn(true);
        // groupRepository.findById(GROUP) 이미 ANYONE 그룹으로 stubbed in setUp

        service.delete(OTHER, PHOTO_ID);

        verify(photoRepository).delete(p);
    }

    @Test
    @DisplayName("delete — UPLOADER_ONLY 정책에서 업로더 본인은 삭제 가능")
    void delete_uploaderOnlyPolicy_sameUser_success() throws Exception {
        Photo p = buildPhoto(PHOTO_ID, GROUP, USER);
        Group uploaderOnlyGroup = buildGroup(GROUP, PhotoDeletePolicy.UPLOADER_ONLY, USER);
        when(photoRepository.findById(PHOTO_ID)).thenReturn(Optional.of(p));
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, USER)).thenReturn(true);
        when(groupRepository.findById(GROUP)).thenReturn(Optional.of(uploaderOnlyGroup));

        service.delete(USER, PHOTO_ID);

        verify(photoRepository).delete(p);
    }

    // =========================================================================
    // deleteBatch
    // =========================================================================

    @Test
    @DisplayName("deleteBatch — 비멤버는 NOT_GROUP_MEMBER")
    void deleteBatch_nonMember_forbidden() throws Exception {
        when(groupMemberRepository.existsByGroupIdAndUserId(GROUP, OTHER)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteBatch(OTHER, GROUP, List.of(PHOTO_ID)))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.NOT_GROUP_MEMBER);
    }

    @Test
    @DisplayName("deleteBatch — 빈 목록은 0 반환")
    void deleteBatch_emptyList_returnsZero() throws Exception {
        int result = service.deleteBatch(USER, GROUP, List.of());
        assertThat(result).isZero();
    }

    @Test
    @DisplayName("deleteBatch — 101장 이상은 PHOTO_BATCH_TOO_LARGE")
    void deleteBatch_over100_batchTooLarge() throws Exception {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();

        assertThatThrownBy(() -> service.deleteBatch(USER, GROUP, ids))
                .isInstanceOf(CustomException.class)
                .extracting(PhotoServiceTest::codeOf)
                .isEqualTo(ErrorCode.PHOTO_BATCH_TOO_LARGE);
    }

    @Test
    @DisplayName("deleteBatch — UPLOADER_ONLY 정책에서 타인 사진은 skip, 본인 사진만 삭제")
    void deleteBatch_uploaderOnlyPolicy_skipsOthersPhotos() throws Exception {
        Photo myPhoto    = buildPhoto(PHOTO_ID,     GROUP, USER);   // uploadedBy = USER
        Photo theirPhoto = buildPhoto(PHOTO_ID + 1, GROUP, OTHER);  // uploadedBy = OTHER

        Group uploaderOnlyGroup = buildGroup(GROUP, PhotoDeletePolicy.UPLOADER_ONLY, USER);
        when(groupRepository.findById(GROUP)).thenReturn(Optional.of(uploaderOnlyGroup));
        when(photoRepository.findAllById(any())).thenReturn(List.of(myPhoto, theirPhoto));

        int deleted = service.deleteBatch(USER, GROUP, List.of(PHOTO_ID, PHOTO_ID + 1));

        assertThat(deleted).isEqualTo(1);
        verify(photoRepository).delete(myPhoto);
        verify(photoRepository, never()).delete(theirPhoto);
    }

    @Test
    @DisplayName("deleteBatch — 다른 그룹 사진은 skip")
    void deleteBatch_crossGroupPhoto_skipped() throws Exception {
        Photo sameGroup  = buildPhoto(PHOTO_ID,     GROUP,       USER);
        Photo otherGroup = buildPhoto(PHOTO_ID + 1, OTHER_GROUP, USER);
        when(photoRepository.findAllById(any())).thenReturn(List.of(sameGroup, otherGroup));

        int deleted = service.deleteBatch(USER, GROUP, List.of(PHOTO_ID, PHOTO_ID + 1));

        assertThat(deleted).isEqualTo(1);
        verify(photoRepository).delete(sameGroup);
        verify(photoRepository, never()).delete(otherGroup);
    }

    @Test
    @DisplayName("deleteBatch — ANYONE 정책에서 전체 삭제 성공")
    void deleteBatch_anyonePolicy_deletesAll() throws Exception {
        Photo p1 = buildPhoto(PHOTO_ID,     GROUP, USER);
        Photo p2 = buildPhoto(PHOTO_ID + 1, GROUP, OTHER);
        when(photoRepository.findAllById(any())).thenReturn(List.of(p1, p2));

        int deleted = service.deleteBatch(USER, GROUP, List.of(PHOTO_ID, PHOTO_ID + 1));

        assertThat(deleted).isEqualTo(2);
        verify(photoRepository).delete(p1);
        verify(photoRepository).delete(p2);
    }

    // =========================================================================
    // deleteExpiredBatch
    // =========================================================================

    @Test
    @DisplayName("deleteExpiredBatch — 만료 사진 N건 삭제 후 개수 반환")
    void deleteExpiredBatch_deletesAndReturnsCount() throws Exception {
        Photo p1 = buildPhoto(PHOTO_ID,     GROUP, USER);
        Photo p2 = buildPhoto(PHOTO_ID + 1, GROUP, OTHER);
        when(photoRepository.findExpiredBatch(any(Instant.class), any())).thenReturn(List.of(p1, p2));

        int result = service.deleteExpiredBatch(50);

        assertThat(result).isEqualTo(2);
        verify(photoRepository).deleteAll(List.of(p1, p2));
    }

    @Test
    @DisplayName("deleteExpiredBatch — 만료 사진 없으면 0 반환")
    void deleteExpiredBatch_noneExpired_returnsZero() throws Exception {
        when(photoRepository.findExpiredBatch(any(Instant.class), any())).thenReturn(List.of());

        int result = service.deleteExpiredBatch(50);

        assertThat(result).isZero();
        verify(photoRepository).deleteAll(List.of());
    }
}
