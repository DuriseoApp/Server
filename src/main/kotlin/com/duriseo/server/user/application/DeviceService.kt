package com.duriseo.server.user.application

import com.duriseo.server.common.persistence.UuidV7
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.user.domain.DevicePlatform
import com.duriseo.server.user.domain.DeviceRepository
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class DeviceService(
	private val devices: DeviceRepository,
	private val em: EntityManager,
	private val clock: Clock,
) {

	/** 그 사용자의 기기를 모두 지운다. 로그인 3종·로그아웃·탈퇴가 쓴다 (DB-09). 부른 쪽 트랜잭션 안에서 처리한다 */
	@Transactional(propagation = Propagation.MANDATORY)
	fun deleteAllOf(userId: UUID) {
		devices.deleteAllOfUser(userId)
	}

	/**
	 * `PUT /me/devices`: FCM 토큰을 등록·갱신한다. 같은 토큰이 이미 있으면 (다른 사용자의 것이어도) 이 사용자로 옮긴다 (ERD devices).
	 * 같은 토큰으로 요청이 겹쳐도 유일 제약(`uq_devices_fcm_token`)에 걸리지 않도록 INSERT … ON CONFLICT 한 문장으로 처리한다.
	 */
	@Transactional
	fun register(userId: UUID, fcmToken: String, platform: DevicePlatform) {
		val now = clock.nowInSeconds()
		em.createNativeQuery(
			"insert into devices (id, user_id, fcm_token, platform, updated_at) " +
				"values (:id, :userId, :fcmToken, :platform, :now) " +
				"on conflict (fcm_token) do update " +
				"set user_id = excluded.user_id, platform = excluded.platform, updated_at = excluded.updated_at",
		)
			.setParameter("id", UuidV7.generate(now.toEpochMilli()))
			.setParameter("userId", userId)
			.setParameter("fcmToken", fcmToken)
			.setParameter("platform", platform.name)
			.setParameter("now", now)
			.executeUpdate()
	}
}
