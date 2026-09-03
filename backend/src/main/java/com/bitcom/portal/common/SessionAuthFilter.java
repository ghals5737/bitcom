package com.bitcom.portal.common;

import com.bitcom.portal.employee.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * N1: 세션 쿠키 → sessions 테이블 조회 → SecurityContext 에 AuthenticatedUser 를 넣는다.
 * 퇴사자/잠금/만료는 AuthService.resolve 가 걸러 빈 값을 돌려주므로 여기서는 인증 없음으로 처리된다.
 * 임시 비밀번호 상태(mustChangePassword)는 비밀번호 변경·본인 확인·로그아웃 외 경로를 403 으로 막는다.
 */
@Component
@RequiredArgsConstructor
public class SessionAuthFilter extends OncePerRequestFilter {
    private static final Set<String> ALLOWED_WHEN_MUST_CHANGE = Set.of("/auth/me", "/auth/change-password", "/auth/logout");

    private final AuthService authService;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Optional<AuthenticatedUser> user = readCookie(req).flatMap(authService::resolve);
        if (user.isPresent()) {
            AuthenticatedUser u = user.get();
            if (u.mustChangePassword() && !ALLOWED_WHEN_MUST_CHANGE.contains(pathWithinApi(req))) {
                writeError(res, 403, "PASSWORD_CHANGE_REQUIRED", "임시 비밀번호를 변경한 뒤 이용할 수 있습니다.");
                return;
            }
            var auth = new UsernamePasswordAuthenticationToken(u, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.role().name())));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res);
    }

    private Optional<String> readCookie(HttpServletRequest req) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return Optional.empty();
        for (Cookie c : cookies) {
            if (props.session().cookieName().equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return Optional.of(c.getValue());
            }
        }
        return Optional.empty();
    }

    private static String pathWithinApi(HttpServletRequest req) {
        String p = req.getRequestURI();
        String ctx = req.getContextPath();
        return ctx != null && p.startsWith(ctx) ? p.substring(ctx.length()) : p;
    }

    private void writeError(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(res.getWriter(), new ErrorResponse(code, message));
    }
}
