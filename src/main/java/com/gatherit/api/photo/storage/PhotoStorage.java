package com.gatherit.api.photo.storage;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 사진 저장소 추상화 — local 디스크 / S3 둘 다 같은 코드로 다룬다.
 * 구현체는 application.yml 의 gatherit.storage.type (local | s3) 으로 선택.
 *
 * key 형식 (양쪽 동일): "groups/{groupId}/{uuid}.{ext}"
 */
public interface PhotoStorage {

    /** 단일 파일 저장. 부분 실패 시 caller 가 cleanup 책임 (writtenKeys 추적 후 deleteSafe). */
    void put(String key, MultipartFile file) throws IOException;

    /**
     * 단일 파일 InputStream — Controller 가 ResponseEntity 로 stream 또는
     * ZIP writer thread 가 zos 에 transferTo. 호출자가 close 책임.
     */
    InputStream open(String key) throws IOException;

    /** 파일 크기. 없거나 에러 시 -1. */
    long size(String key);

    /** 존재 여부. */
    boolean exists(String key);

    /** 안전 삭제 — 없으면 무시, 에러는 로그만. */
    void deleteSafe(String key);
}
