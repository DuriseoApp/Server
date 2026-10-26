package com.duriseo.server.common.persistence

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.UuidGenerator
import java.util.UUID

/**
 * 기본 키가 UUIDv7인 엔티티의 부모 (ERD 1장). persist 하는 순간 ID가 정해진다.
 * 기본 키가 다른 값(예: notification_settings.user_id)인 테이블은 이 클래스를 쓰지 않는다.
 */
@MappedSuperclass
abstract class UuidEntity {

	@Id
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	var id: UUID? = null
		protected set

	/** 저장(persist)한 뒤에만 쓴다 */
	fun requireId(): UUID = checkNotNull(id) { "id is not assigned yet" }
}
