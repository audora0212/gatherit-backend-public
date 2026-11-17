package com.gatherit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * gatherit API 진입점.
 * - 도메인: 그룹 캘린더 + 시간 투표 + 사진 공유
 * - API 코드를 도메인별로 나누고 각 도메인에서 HTTP 요청 처리, 비즈니스 로직과 데이터 접근을 분리합니다.
 */
@SpringBootApplication
@EnableScheduling
public class GatherItApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatherItApplication.class, args);
    }
}
