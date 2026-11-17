package com.gatherit.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class S3Config {

    @Value("${aws.s3.region:us-east-1}")
    private String region;

    @Value("${aws.s3.endpoint:}")
    private String endpoint; // 비어있으면 AWS, 값 있으면 MinIO 등 호환

    @Value("${aws.s3.access-key:}")
    private String accessKey;

    @Value("${aws.s3.secret-key:}")
    private String secretKey;

    @Value("${aws.s3.path-style:false}")
    private boolean pathStyle;

    // ⚠️ 타임아웃은 클라이언트 전역이 아니라 요청별(S3PhotoStorage)로 건다.
    // 전역 apiCallTimeout 은 getObject 의 스트리밍 본문 읽기까지 포함해, 사진 조회/ZIP 다운로드를
    // 느린 모바일 연결에서 중간에 끊을 수 있다. 그래서 put/head/delete(비스트리밍)만 요청별로 bound 하고
    // getObject(스트리밍)는 무제한으로 둔다. (엣지 감사 P0-1)
    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        applyAuth(builder);
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    private void applyAuth(S3ClientBuilder builder) {
        if (!accessKey.isBlank() && !secretKey.isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)));
        }
    }
}
