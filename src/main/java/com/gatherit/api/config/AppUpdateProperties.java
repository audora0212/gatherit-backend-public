package com.gatherit.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 앱 업데이트 안내 설정 — application.yml 의 app.update.* 바인딩.
 *
 * - latest: 최신 출시 버전. 앱이 이보다 낮으면 "소프트" 안내(나중에 가능).
 * - min: 최소 지원 버전. 앱이 이보다 낮으면 "강제" 업데이트(진행 차단).
 * - storeUrl: 플랫폼별 스토어 링크.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.update")
public class AppUpdateProperties {

    private Platform ios = new Platform();
    private Platform android = new Platform();

    @Getter
    @Setter
    public static class Platform {
        private String latest = "1.0.0";
        private String min = "1.0.0";
        private String storeUrl = "";
    }
}
