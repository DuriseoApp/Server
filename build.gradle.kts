plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
}

group = "com.duriseo"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	// access 토큰(HS256) 발급·검증, 2부의 Google ID 토큰 검증. Jackson에 의존하지 않는다 (Boot 4의 Jackson 3와 충돌 없음)
	implementation("com.nimbusds:nimbus-jose-jwt:10.0.2")
	// FCM 푸시·동기화 신호 (API.md 6장, 7장). 의존하는 Jackson 2(com.fasterxml)는 Boot 4의 Jackson 3(tools.jackson)와 패키지가 달라 함께 있어도 된다. CI에서 충돌이 나면 제외 규칙을 더한다
	implementation("com.google.firebase:firebase-admin:9.5.0")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Dockerfile이 이 이름으로 jar를 담는다
tasks.bootJar {
	archiveFileName = "app.jar"
}

// 실행 jar 하나만 만든다 (plain jar는 쓰지 않음)
tasks.jar {
	enabled = false
}
