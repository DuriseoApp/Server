# 둘이서 서버 이미지 (ARM64, AWS t4g).
# Gradle 빌드는 이미지 밖(GitHub Actions)에서 하고, 만들어진 build/libs/app.jar만 담는다.
FROM eclipse-temurin:21-jre-noble

RUN groupadd --system app && useradd --system --gid app --home-dir /app --no-create-home app

WORKDIR /app
COPY build/libs/app.jar app.jar

# dev 앱의 프로필 사진 폴더 (Docker 볼륨이 이 폴더의 소유자를 물려받는다, API.md 8장). prod 앱은 S3를 쓴다
RUN mkdir -p /data/profile-images && chown app:app /data/profile-images

USER app
EXPOSE 8080

# 메모리 상한은 컨테이너 제한(compose mem_limit)의 비율로 정한다. 서버 1대에 앱 2개와 DB가 같이 있어 작게 잡는다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -Xss512k"

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
