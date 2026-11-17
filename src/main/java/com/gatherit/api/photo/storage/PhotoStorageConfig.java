package com.gatherit.api.photo.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * gatherit.storage.type 으로 PhotoStorage 구현체 선택.
 *  - local (default, matchIfMissing): 로컬 디스크 (개발)
 *  - s3: AWS S3 (운영)
 *
 * S3Client 는 기존 com.gatherit.api.config.S3Config 가 만든 빈을 주입받아 사용.
 * (S3Presigner 도 같은 곳에서 등록됨)
 */
@Configuration
public class PhotoStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "gatherit.storage.type", havingValue = "local", matchIfMissing = true)
    public PhotoStorage localPhotoStorage(@Value("${gatherit.photo.local-dir:./uploads}") String dir) {
        return new LocalDiskPhotoStorage(dir);
    }

    @Bean
    @ConditionalOnProperty(name = "gatherit.storage.type", havingValue = "s3")
    public PhotoStorage s3PhotoStorage(S3Client s3, @Value("${aws.s3.bucket}") String bucket) {
        return new S3PhotoStorage(s3, bucket);
    }
}
