package com.duriseo.server.common.persistence

import java.security.SecureRandom
import java.util.UUID

/**
 * 엔티티를 거치지 않고 행을 넣을 때(네이티브 upsert) 쓰는 UUIDv7 (RFC 9562).
 * 엔티티의 기본 키는 `UuidEntity`의 Hibernate 생성기가 만든다.
 */
object UuidV7 {

	private val random = SecureRandom()

	fun generate(epochMillis: Long): UUID {
		val msb = ((epochMillis and 0xFFFF_FFFF_FFFFL) shl 16) or 0x7000L or (random.nextLong() and 0x0FFFL)
		val lsb = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or Long.MIN_VALUE
		return UUID(msb, lsb)
	}
}
