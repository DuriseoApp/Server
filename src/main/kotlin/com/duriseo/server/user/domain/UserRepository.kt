package com.duriseo.server.user.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {

	/** 로그인 3종의 "이전 세션·기기 정리"와 switch가 사용자 행을 잠글 때 쓴다 (SELECT … FOR UPDATE) */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	fun findByIdForUpdate(@Param("id") id: UUID): User?

	/**
	 * [ids]의 사용자 행을 **ID 오름차순**으로 `FOR SHARE`로 잠근다. 일정 쓰기·일정 알림 설정이 행을 넣기 전에 부른다.
	 * 탈퇴·연결 해제는 사용자 행을 `PESSIMISTIC_WRITE`로 잡는데, Hibernate는 PostgreSQL에서 이를 `FOR NO KEY UPDATE`로 보낸다.
	 * `FOR KEY SHARE`는 이와 충돌하지 않아 해제 도중에 R-01을 통과하고 해제 정리(D-45) 뒤에 행을 남겼다. `FOR SHARE`는 충돌하므로
	 * 해제가 끝날 때까지 기다리고, 서로(`FOR SHARE`끼리)는 막지 않는다. 사용자 행을 먼저 잡는 순서(users → … → spaces)도 지킨다
	 * (ARCHITECTURE 4장). 읽은 ID를 돌려준다.
	 */
	@Query(value = "select u.id from users u where u.id in (:ids) order by u.id for share", nativeQuery = true)
	fun lockShare(@Param("ids") ids: Collection<UUID>): List<UUID>
}
