package com.bitcom.portal.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Session session, Auth auth, Seed seed) {
    public record Session(int idleMinutes, int absoluteHours, String cookieName, boolean cookieSecure) {}
    public record Auth(int lockThreshold) {}
    public record Seed(String adminPassword, String employeePassword) {}
}
