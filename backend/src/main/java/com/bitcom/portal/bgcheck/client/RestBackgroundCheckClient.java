package com.bitcom.portal.bgcheck.client;

import com.bitcom.portal.bgcheck.dto.BgcDtos.ExternalCheck;
import com.bitcom.portal.bgcheck.dto.BgcDtos.ExternalCreateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;

/**
 * 외부 Background Check API 실제 호출.
 * 실측(MEASUREMENTS) 반영:
 *  - 응답이 ~30s 부근에 몰리므로 read timeout 은 그보다 길게 (yml).
 *  - 503 본문의 retryAfter(30) 는 헤더로는 오지 않고, 값이 항상 30 이라 재시도 간격 근거로 쓰지 않는다.
 *  - POST 는 실측상 거의 항상 201 이지만 명세의 5xx 대비 짧은 재시도를 둔다. GET 은 폴링 주기가 재시도 역할.
 */
@Slf4j
@Component
public class RestBackgroundCheckClient implements BackgroundCheckClient {
    private final RestClient rest;
    private final BgcProperties props;
    private final ObjectMapper objectMapper;

    public RestBackgroundCheckClient(BgcProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(props.connectTimeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(props.readTimeoutMs()));
        this.rest = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public Result create(ExternalCreateRequest request) {
        Result last = null;
        for (int attempt = 1; attempt <= props.postMaxAttempts(); attempt++) {
            last = exchange(() -> rest.post().uri("/background-checks")
                    .contentType(MediaType.APPLICATION_JSON).body(request)
                    .retrieve().body(String.class), attempt);
            if (last.ok() || last.badRequest()) return last;     // 4xx 는 재시도 무의미
            if (attempt < props.postMaxAttempts()) sleep(props.postRetryBackoffMs());
        }
        return last;
    }

    @Override
    public Result get(String checkId) {
        return exchange(() -> rest.get().uri("/background-checks/{id}", checkId).retrieve().body(String.class), 1);
    }

    private interface Call { String run(); }

    private Result exchange(Call call, int attempt) {
        try {
            String body = call.run();
            ExternalCheck parsed = body == null ? null : objectMapper.readValue(body, ExternalCheck.class);
            return new Result(200, parsed, null, attempt);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            String msg = "HTTP " + code + " " + summarize(e.getResponseBodyAsString());
            log.warn("bgcheck api {} (attempt {})", msg, attempt);
            return new Result(code, null, msg, attempt);
        } catch (ResourceAccessException e) {
            String msg = "timeout/network: " + e.getMostSpecificCause().getClass().getSimpleName();
            log.warn("bgcheck api {} (attempt {})", msg, attempt);
            return new Result(0, null, msg, attempt);
        } catch (Exception e) {
            String msg = "parse error: " + e.getClass().getSimpleName();
            log.warn("bgcheck api {} (attempt {})", msg, attempt);
            return new Result(0, null, msg, attempt);
        }
    }

    private static String summarize(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
