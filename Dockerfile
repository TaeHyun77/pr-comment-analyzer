# 빌드 스테이지 - Gradle 빌드로 bootJar 생성
FROM eclipse-temurin:8-jdk AS build
WORKDIR /workspace

# gradle 캐시 활용을 위해 빌드 정의 먼저 복사
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle build.gradle ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 빌드
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# 런타임 스테이지 - JRE만 포함된 가벼운 이미지
FROM eclipse-temurin:8-jre
WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

# 상태 파일 영속화 디렉터리 (docker-compose에서 볼륨 마운트)
RUN mkdir -p /app/data

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
