package com.gatherit.api.photo.service;

import com.gatherit.api.exception.CustomException;
import com.gatherit.api.exception.ErrorCode;
import com.gatherit.api.group.entity.Group;
import com.gatherit.api.group.entity.GroupMember;
import com.gatherit.api.group.entity.PhotoDeletePolicy;
import com.gatherit.api.group.repository.GroupMemberRepository;
import com.gatherit.api.group.repository.GroupRepository;
import com.gatherit.api.notification.entity.NotificationType;
import com.gatherit.api.notification.service.NotificationService;
import com.gatherit.api.photo.dto.PhotoResponse;
import com.gatherit.api.photo.entity.Photo;
import com.gatherit.api.photo.entity.PhotoUploadReceipt;
import com.gatherit.api.photo.repository.PhotoRepository;
import com.gatherit.api.photo.repository.PhotoUploadReceiptRepository;
import com.gatherit.api.photo.storage.PhotoStorage;
import com.gatherit.api.photo.storage.StorageCleanup;
import com.gatherit.api.user.entity.User;
import com.gatherit.api.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 사진 — PhotoStorage (local 디스크 / S3) 추상화 위에서 작동.
 *
 * 정책:
 *  - TTL: config 의 expiry-days (기본 7일). cron 이 주기적으로 row+파일 삭제.
 *    S3 의 경우 lifecycle rule 도 같은 기간으로 설정 → BE 가 깜빡해도 안전.
 *  - 그룹당 cap: 100장. 도달 시 업로드 거부.
 *  - 파일당 max: 10MB.
 *  - 한 번 업로드 batch: 30장.
 *  - ZIP 다운로드 batch: 100장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhotoService {

    public static final int GROUP_PHOTO_CAP = 100;
    public static final int UPLOAD_BATCH_LIMIT = 30;
    public static final int DOWNLOAD_BATCH_LIMIT = 100;
    public static final long FILE_SIZE_LIMIT = 10L * 1024 * 1024; // 10MB
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif", "image/webp"
    );

    private final PhotoRepository photoRepository;
    private final PhotoUploadReceiptRepository receiptRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final GroupRepository groupRepository;
    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final PhotoStorage storage;
    private final PlatformTransactionManager txManager;

    /** 메타데이터 INSERT 단계만 짧게 감쌀 트랜잭션 템플릿 — S3 업로드는 트랜잭션 밖에서 한다. */
    private TransactionTemplate txTemplate;

    @PostConstruct
    void initTx() {
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @Value("${gatherit.photo.expiry-days:7}")
    private int expiryDays;

    /**
     * 멀티파트 batch 업로드. 부분 실패 시 전체 롤백 (storage cleanup + DB 트랜잭션).
     *
     * ⚠️ @Transactional 아님(의도적): S3 putObject(외부 블로킹 I/O)를 트랜잭션 안에서 돌리면
     * 그동안 Hikari 커넥션 1개를 점유한다. 느린 업로드 몇 건이 풀(5)을 전부 묶으면 무관한 전 API 가
     * connection-timeout 으로 동반 실패한다(엣지 감사 P0-1). → S3 업로드는 트랜잭션 밖에서 먼저 끝내고,
     * DB 메타데이터 INSERT 만 짧은 트랜잭션(txTemplate)으로 감싼다. 커넥션 점유 시간 = INSERT 시간뿐.
     */
    public List<PhotoResponse> uploadBatch(Long userId, Long groupId, List<MultipartFile> files, String batchId) {
        assertMember(userId, groupId);
        // 멱등성 — 같은 (user, batchId) 재요청([다시 시도] 등 — 1차가 서버에선 성공했는데 응답만 유실된 경우)은
        // 다시 받지 않고 그때 만든 사진들을 그대로 반환 (P1-2).
        if (batchId != null && !batchId.isBlank()) {
            var receipt = receiptRepository.findByUserIdAndBatchId(userId, batchId.trim());
            if (receipt.isPresent()) {
                return photoRepository.findAllById(receipt.get().photoIdList()).stream()
                        .map(p -> PhotoResponse.of(p, viewUrl(p.getId()))).toList();
            }
        }
        if (files == null || files.isEmpty()) {
            throw new CustomException(ErrorCode.PHOTO_BATCH_TOO_LARGE);
        }
        if (files.size() > UPLOAD_BATCH_LIMIT) {
            throw new CustomException(ErrorCode.PHOTO_BATCH_TOO_LARGE);
        }

        long currentCount = photoRepository.countByGroupId(groupId);
        if (currentCount + files.size() > GROUP_PHOTO_CAP) {
            throw new CustomException(ErrorCode.PHOTO_QUOTA_EXCEEDED);
        }

        for (MultipartFile f : files) {
            if (f.getSize() > FILE_SIZE_LIMIT) {
                throw new CustomException(ErrorCode.PHOTO_TOO_LARGE);
            }
            String mime = f.getContentType();
            if (mime == null || !ALLOWED_TYPES.contains(mime.toLowerCase())) {
                throw new CustomException(ErrorCode.PHOTO_INVALID_TYPE);
            }
            // Magic byte 검증 — Content-Type 헤더는 클라이언트 조작 가능, 파일 시그니처로 실제 형식 확인.
            // 위조 파일 (악성 binary를 image/jpeg 헤더로 가장) 차단.
            if (!isImageSignatureValid(f, mime.toLowerCase())) {
                throw new CustomException(ErrorCode.PHOTO_INVALID_TYPE);
            }
        }

        Instant now = Instant.now();
        Instant expires = now.plus(Duration.ofDays(expiryDays));
        List<String> writtenKeys = new ArrayList<>();
        List<Photo> toSave = new ArrayList<>();

        // ── 1) S3 업로드 (트랜잭션 밖 — DB 커넥션 미점유) ──
        try {
            for (MultipartFile f : files) {
                String mime = f.getContentType().toLowerCase();
                String ext = extFromMime(mime);
                String key = "groups/%d/%s.%s".formatted(groupId, UUID.randomUUID().toString().replace("-", ""), ext);
                storage.put(key, f);
                writtenKeys.add(key);

                toSave.add(Photo.builder()
                        .groupId(groupId)
                        .uploadedBy(userId)
                        .storageKey(key)
                        .mimeType(mime)
                        .originalName(safeOriginalName(f.getOriginalFilename()))
                        .sizeBytes(f.getSize())
                        .uploadedAt(now)
                        .expiresAt(expires)
                        .build());
            }
        } catch (IOException e) {
            // storage 쓰기 실패 — 이미 쓰인 키들 cleanup (DB 에는 아직 아무것도 안 씀)
            for (String k : writtenKeys) {
                storage.deleteSafe(k);
            }
            log.error("Photo batch upload IO error groupId={}", groupId, e);
            throw new CustomException(ErrorCode.PHOTO_STORAGE_ERROR);
        }

        // ── 2) 메타데이터 INSERT + 알림 (짧은 트랜잭션) ──
        //     S3 업로드가 끝난 뒤에만 커넥션을 잡으므로 풀 점유 시간이 최소화된다.
        try {
            return txTemplate.execute(status -> {
                for (Photo photo : toSave) {
                    photoRepository.save(photo);
                }

                // PHOTO_UPLOADED — 그룹원(업로더 제외) 에게 push.
                Group group = groupRepository.findById(groupId).orElse(null);
                if (group != null && !toSave.isEmpty()) {
                    String uploaderName = userRepository.findById(userId)
                            .map(User::getNickname).orElse("멤버");
                    String title = "%s님이 사진 %d장을 올렸어요".formatted(uploaderName, toSave.size());
                    String subtitle = group.getName();
                    String refKey = "photo:" + groupId;
                    java.util.List<Long> photoCtx = java.util.List.of(groupId);
                    java.util.List<Long> photoTargets = groupMemberRepository.findAllByGroupId(groupId).stream()
                            .map(GroupMember::getUserId).filter(uid -> !uid.equals(userId)).toList();
                    notificationService.pushScopedToUsers(photoTargets, NotificationType.PHOTO_UPLOADED, title, subtitle, refKey, photoCtx);
                }

                if (batchId != null && !batchId.isBlank()) {
                    receiptRepository.save(new PhotoUploadReceipt(userId, batchId.trim(),
                            toSave.stream().map(Photo::getId).toList()));
                }
                return toSave.stream().map(p -> PhotoResponse.of(p, viewUrl(p.getId()))).toList();
            });
        } catch (RuntimeException e) {
            // DB 트랜잭션 롤백됨 — 이미 올린 S3 객체 cleanup (고아 방지)
            for (String k : writtenKeys) {
                storage.deleteSafe(k);
            }
            log.error("Photo batch metadata persist failed groupId={}", groupId, e);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<PhotoResponse> listByGroup(Long userId, Long groupId) {
        assertMember(userId, groupId);
        return photoRepository.findAllByGroupIdOrderByUploadedAtDesc(groupId).stream()
                .map(p -> PhotoResponse.of(p, viewUrl(p.getId())))
                .toList();
    }

    /** 단일 사진 파일 — 그룹 멤버만 접근 가능. */
    @Transactional(readOnly = true)
    public PhotoFile loadFile(Long userId, Long photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new CustomException(ErrorCode.PHOTO_NOT_FOUND));
        assertMember(userId, photo.getGroupId());
        if (!storage.exists(photo.getStorageKey())) {
            log.error("Photo file missing in storage id={} key={}", photoId, photo.getStorageKey());
            throw new CustomException(ErrorCode.PHOTO_NOT_FOUND);
        }
        try {
            long size = storage.size(photo.getStorageKey());
            return new PhotoFile(
                    new InputStreamResource(new BufferedInputStream(storage.open(photo.getStorageKey()))),
                    photo.getMimeType(),
                    size,
                    photo.getOriginalName()
            );
        } catch (IOException e) {
            throw new CustomException(ErrorCode.PHOTO_STORAGE_ERROR);
        }
    }

    /**
     * ZIP 스트리밍 — InputStreamResource 로 Controller 가 그대로 client 로 흘려보냄.
     * 메모리에 다 안 올리고 PipedStream 으로 zip 생성/전송 동시 진행.
     */
    @Transactional(readOnly = true)
    public PhotoFile downloadZip(Long userId, Long groupId, List<Long> photoIds) {
        assertMember(userId, groupId);
        if (photoIds == null || photoIds.isEmpty()) {
            throw new CustomException(ErrorCode.PHOTO_BATCH_TOO_LARGE);
        }
        if (photoIds.size() > DOWNLOAD_BATCH_LIMIT) {
            throw new CustomException(ErrorCode.PHOTO_BATCH_TOO_LARGE);
        }

        List<Photo> photos = photoRepository.findAllById(photoIds);
        // 같은 그룹 photos 만 허용 — 다른 그룹 사진 끼워팔기 차단
        for (Photo p : photos) {
            if (!p.getGroupId().equals(groupId)) {
                throw new CustomException(ErrorCode.PHOTO_FORBIDDEN);
            }
        }

        try {
            PipedOutputStream pos = new PipedOutputStream();
            PipedInputStream pis = new PipedInputStream(pos, 64 * 1024);

            Thread writer = new Thread(() -> {
                try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(pos))) {
                    Set<String> usedNames = new HashSet<>();
                    for (Photo photo : photos) {
                        if (!storage.exists(photo.getStorageKey())) continue;
                        String name = uniqueZipEntryName(photo, usedNames);
                        usedNames.add(name);
                        zos.putNextEntry(new ZipEntry(name));
                        try (InputStream in = storage.open(photo.getStorageKey())) {
                            in.transferTo(zos);
                        }
                        zos.closeEntry();
                    }
                } catch (IOException e) {
                    log.error("ZIP write failed groupId={}", groupId, e);
                }
            }, "photo-zip-writer-" + groupId);
            writer.setDaemon(true);
            writer.start();

            String zipName = "gatherit-photos-%d-%d.zip".formatted(groupId, Instant.now().getEpochSecond());
            return new PhotoFile(new InputStreamResource(pis), "application/zip", -1L, zipName);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.PHOTO_STORAGE_ERROR);
        }
    }

    @Transactional
    public void delete(Long userId, Long photoId) {
        Photo photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new CustomException(ErrorCode.PHOTO_NOT_FOUND));
        assertCanDelete(userId, photo);
        // S3 파일은 커밋 후 삭제 — tx 안 외부 I/O 회피 + 롤백 시 파일만 사라지는 정합 깨짐 방지 (P1-5)
        StorageCleanup.deleteAfterCommit(storage, List.of(photo.getStorageKey()));
        photoRepository.delete(photo);
    }

    /**
     * 일괄 삭제 — 권한 없는 사진은 skip, 결과로 실제 삭제된 수 반환.
     * 같은 그룹 검증 (다른 그룹 ID 끼워팔기 차단).
     */
    @Transactional
    public int deleteBatch(Long userId, Long groupId, List<Long> photoIds) {
        assertMember(userId, groupId);
        if (photoIds == null || photoIds.isEmpty()) return 0;
        if (photoIds.size() > DOWNLOAD_BATCH_LIMIT) {
            throw new CustomException(ErrorCode.PHOTO_BATCH_TOO_LARGE);
        }
        List<Photo> photos = photoRepository.findAllById(photoIds);
        Group group = groupRepository.findById(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        PhotoDeletePolicy policy = group.getPhotoDeletePolicy();
        int deleted = 0;
        List<String> keysToDelete = new ArrayList<>();
        for (Photo photo : photos) {
            if (!photo.getGroupId().equals(groupId)) continue;
            if (policy == PhotoDeletePolicy.UPLOADER_ONLY && !photo.getUploadedBy().equals(userId)) continue;
            keysToDelete.add(photo.getStorageKey());
            photoRepository.delete(photo);
            deleted++;
        }
        // S3 파일은 커밋 후 일괄 삭제 (P1-5)
        StorageCleanup.deleteAfterCommit(storage, keysToDelete);
        return deleted;
    }

    private void assertCanDelete(Long userId, Photo photo) {
        Group group = groupRepository.findById(photo.getGroupId())
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
        if (!groupMemberRepository.existsByGroupIdAndUserId(photo.getGroupId(), userId)) {
            throw new CustomException(ErrorCode.PHOTO_FORBIDDEN);
        }
        PhotoDeletePolicy policy = group.getPhotoDeletePolicy();
        if (policy == PhotoDeletePolicy.UPLOADER_ONLY && !photo.getUploadedBy().equals(userId)) {
            throw new CustomException(ErrorCode.PHOTO_FORBIDDEN);
        }
    }

    /**
     * 만료 사진 일괄 삭제 — 페이징 단위로 lock 폭주 방지.
     */
    @Transactional
    public int deleteExpiredBatch(int batchSize) {
        Instant now = Instant.now();
        List<Photo> expired = photoRepository.findExpiredBatch(now, PageRequest.of(0, batchSize));
        // S3 파일은 커밋 후 일괄 삭제 — cron 도 tx 안 S3 루프(최대 200회)로 커넥션 점유하지 않게 (P1-5)
        StorageCleanup.deleteAfterCommit(storage, expired.stream().map(Photo::getStorageKey).toList());
        photoRepository.deleteAll(expired);
        if (!expired.isEmpty()) log.info("Expired photo cleanup: {} rows", expired.size());
        return expired.size();
    }

    // ─── helpers ─────────────────────────────────────────

    private void assertMember(Long userId, Long groupId) {
        if (!groupMemberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new CustomException(ErrorCode.NOT_GROUP_MEMBER);
        }
    }

    private String viewUrl(Long photoId) {
        return "/api/photos/%d/file".formatted(photoId);
    }

    /**
     * 파일 시그니처(매직 바이트) 검증.
     * Content-Type 헤더는 신뢰 불가 → 첫 12바이트로 실제 형식 확인.
     *
     * 시그니처 참조:
     *   JPEG : FF D8 FF
     *   PNG  : 89 50 4E 47 0D 0A 1A 0A
     *   HEIC/HEIF : offset 4 에 "ftyp" + offset 8 에 "heic"/"heix"/"mif1"/"msf1" 등
     *   WEBP : "RIFF" .... "WEBP" (offset 0 + offset 8)
     */
    private boolean isImageSignatureValid(MultipartFile f, String declaredMime) {
        try (var in = f.getInputStream()) {
            byte[] head = in.readNBytes(12);
            if (head.length < 4) return false;
            int b0 = head[0] & 0xFF, b1 = head[1] & 0xFF, b2 = head[2] & 0xFF, b3 = head[3] & 0xFF;
            switch (declaredMime) {
                case "image/jpeg", "image/jpg" -> {
                    return b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF;
                }
                case "image/png" -> {
                    return head.length >= 8
                            && b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47
                            && (head[4] & 0xFF) == 0x0D && (head[5] & 0xFF) == 0x0A
                            && (head[6] & 0xFF) == 0x1A && (head[7] & 0xFF) == 0x0A;
                }
                case "image/heic", "image/heif" -> {
                    if (head.length < 12) return false;
                    // offset 4..8 == "ftyp"
                    boolean ftyp = head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
                    if (!ftyp) return false;
                    String brand = new String(head, 8, 4);
                    return brand.equals("heic") || brand.equals("heix") || brand.equals("hevc")
                            || brand.equals("heim") || brand.equals("heis") || brand.equals("hevm")
                            || brand.equals("hevs") || brand.equals("mif1") || brand.equals("msf1");
                }
                case "image/webp" -> {
                    if (head.length < 12) return false;
                    boolean riff = head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F';
                    boolean webp = head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
                    return riff && webp;
                }
                default -> {
                    return false;
                }
            }
        } catch (IOException e) {
            log.warn("Photo signature read failed", e);
            return false;
        }
    }

    private String extFromMime(String mime) {
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/heic", "image/heif" -> "heic";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }

    private String safeOriginalName(String raw) {
        if (raw == null) return null;
        // 경로 분리자 등 위험 문자 제거 — 대괄호/한글 등 허용.
        String cleaned = raw.replaceAll("[\\\\/ ]", "_").trim();
        if (cleaned.length() > 200) cleaned = cleaned.substring(cleaned.length() - 200);
        return cleaned.isEmpty() ? null : cleaned;
    }

    private String uniqueZipEntryName(Photo photo, Set<String> used) {
        String base = photo.getOriginalName();
        if (base == null || base.isBlank()) {
            base = "photo-" + photo.getId() + "." + extFromMime(photo.getMimeType());
        }
        if (!used.contains(base)) return base;
        // 중복 시 (id) 접미사
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        return "%s (%d)%s".formatted(stem, photo.getId(), ext);
    }

    /** Controller 가 ResponseEntity 로 streaming 할 때 쓰는 wrapper. */
    public record PhotoFile(
            InputStreamResource resource,
            String contentType,
            long contentLength,
            String filename
    ) {}
}
