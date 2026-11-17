package com.gatherit.api.photo.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

@Slf4j
public class S3PhotoStorage implements PhotoStorage {

    /**
     * 비스트리밍 S3 호출(put/head/delete)에만 거는 요청별 타임아웃.
     * 이 호출들은 트랜잭션/요청 스레드를 잡은 채 외부 응답을 기다리므로 bound 가 필요.
     * getObject(스트리밍 본문)는 다운로드를 끊을 수 있어 일부러 제외 — 무제한 유지.
     */
    private static final AwsRequestOverrideConfiguration OP_TIMEOUT =
            AwsRequestOverrideConfiguration.builder()
                    .apiCallAttemptTimeout(Duration.ofSeconds(10))
                    .apiCallTimeout(Duration.ofSeconds(30))
                    .build();

    private final S3Client s3;
    private final String bucket;

    public S3PhotoStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            PutObjectRequest req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .overrideConfiguration(OP_TIMEOUT)
                    .build();
            s3.putObject(req, RequestBody.fromInputStream(in, file.getSize()));
        } catch (SdkException e) {
            // 타임아웃(ApiCallTimeoutException 등) 포함 모든 S3 실패를 IOException 으로 변환 →
            // 호출자(PhotoService)가 IOException 으로 잡아 이미 쓴 키를 cleanup 한다. open() 과 동일 정책.
            throw new IOException("S3 putObject failed key=" + key, e);
        }
    }

    @Override
    public InputStream open(String key) throws IOException {
        try {
            GetObjectRequest req = GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build();
            return s3.getObject(req);
        } catch (SdkException e) {
            throw new IOException("S3 getObject failed key=" + key, e);
        }
    }

    @Override
    public long size(String key) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket).key(key).overrideConfiguration(OP_TIMEOUT).build());
            return head.contentLength();
        } catch (NoSuchKeyException e) {
            return -1;
        } catch (SdkException e) {
            log.error("S3 headObject failed key={}", key, e);
            return -1;
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).overrideConfiguration(OP_TIMEOUT).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (SdkException e) {
            log.error("S3 headObject failed key={}", key, e);
            return false;
        }
    }

    @Override
    public void deleteSafe(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).overrideConfiguration(OP_TIMEOUT).build());
        } catch (SdkException e) {
            log.error("S3 delete failed key={}", key, e);
        }
    }
}
