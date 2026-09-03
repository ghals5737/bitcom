package com.bitcom.portal.bgcheck.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * N3: PENDING 건을 주기적으로 GET 폴링. fixedDelay 이므로 한 바퀴가 끝난 뒤 다음 주기를 센다
 * (외부 응답이 30s 걸려도 겹치지 않음). 주기 값은 bgcheck.poll-interval-ms.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackgroundCheckPoller {
    private final BackgroundCheckService service;

    @Scheduled(fixedDelayString = "${bgcheck.poll-interval-ms}", initialDelayString = "${bgcheck.poll-interval-ms}")
    public void poll() {
        try {
            service.pollPending();
        } catch (Exception e) {
            log.error("bgcheck poll failed", e);
        }
    }
}
