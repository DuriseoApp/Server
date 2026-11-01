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
	 * [ids]의 사용자 행을 **ID 오름차순**으로 `FOR KEY SHARE`로 잠근다. 행을 `FOR UPDATE`로 잡은 탈퇴·연결 해제가 끝날 때까지 기다리고,
	 * 서로는 막지 않는다. 일정을 쓰기 전에 부른다: 외래 키 검사가 공간 행에 먼저 `FOR KEY SHARE`를 잡은 채 사용자 행을 기다리면
	 * 사용자 행 → 공간 행 순서로 잠그는 탈퇴·해제와 교착하기 때문이다 (ARCHITECTURE 4장). 읽은 ID를 돌려준다.
	 */
	@Query(value = "select u.id from users u where u.id in (:ids) order by u.id for key share", nativeQuery = true)
	fun lockKeyShare(@Param("ids") ids: Collection<UUID>): List<UUID>
}
