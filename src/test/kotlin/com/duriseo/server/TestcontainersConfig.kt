package com.duriseo.server

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** 테스트용 PostgreSQL 16 (ERD 1장). DB가 필요한 테스트는 이 설정을 @Import 한다 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfig {

	@Bean
	@ServiceConnection
	fun postgres(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
}
