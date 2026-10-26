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
}
