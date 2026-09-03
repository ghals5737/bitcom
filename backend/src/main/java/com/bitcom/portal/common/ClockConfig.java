package com.bitcom.portal.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 규칙 3: 시간은 Service 안에서 만들지 않고 Clock 을 주입받는다. 테스트에서는 Clock.fixed 로 교체. */
@Configuration
public class ClockConfig {
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
