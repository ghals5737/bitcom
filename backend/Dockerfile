# ---- 1단계: 빌드 (EC2 에 JDK 없어도 됨) ----
FROM gradle:8.14-jdk17 AS build
WORKDIR /src
# 의존성 레이어 캐시: 빌드 스크립트만 먼저 복사해 의존성 해석
COPY build.gradle.kts settings.gradle.kts ./
RUN gradle --no-daemon dependencies -q > /dev/null 2>&1 || true
COPY src ./src
RUN gradle --no-daemon bootJar -x test -q

# ---- 2단계: 실행 (JRE 만) ----
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 portal
COPY --from=build /src/build/libs/portal-*.jar /app/app.jar
USER portal
# 컨테이너 내부 포트. 호스트에서는 run.sh 가 8000 → 8080 으로 매핑한다.
EXPOSE 8080
ENV SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"
# 헬스체크는 이미지에 curl 이 없어 run.sh 가 호스트에서 수행한다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
