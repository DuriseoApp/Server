package com.duriseo.server

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfig::class)
class ApplicationContextTest {

	@Test
	fun `애플리케이션이 PostgreSQL과 함께 뜬다`() {
	}
}
