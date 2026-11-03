package com.duriseo.server.common.persistence

import java.util.UUID

/**
 * PostgreSQL `uuid` 비교와 같은 순서(16바이트를 앞에서부터 부호 없이 비교)다. Kotlin의 `UUID.compareTo`는 상위·하위 64비트를
 * 부호 있는 수로 비교해 첫 비트가 1인 UUID(UUIDv4 절반)에서 순서가 달라진다. 사용자 행을 잠그는 순서를 SQL `order by`와 맞추려고 쓴다
 * (ARCHITECTURE 4장 "users(UUID 오름차순)").
 */
object UuidOrder : Comparator<UUID> {

	override fun compare(a: UUID, b: UUID): Int {
		val high = java.lang.Long.compareUnsigned(a.mostSignificantBits, b.mostSignificantBits)
		return if (high != 0) high else java.lang.Long.compareUnsigned(a.leastSignificantBits, b.leastSignificantBits)
	}
}

/** PostgreSQL `order by`와 같은 순서로 정렬한다 ([UuidOrder]) */
fun Iterable<UUID>.sortedAsPostgres(): List<UUID> = sortedWith(UuidOrder)
