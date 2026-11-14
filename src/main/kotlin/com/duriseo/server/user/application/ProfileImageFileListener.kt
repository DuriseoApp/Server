package com.duriseo.server.user.application

import com.duriseo.server.storage.ProfileImageKeys
import com.duriseo.server.storage.ProfileImageStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/**
 * 프로필 사진 파일을 DB 커밋 뒤에 지운다 (API.md 8장, ERD 4.3·4.4, NFR-FILE-01). 트랜잭션 안에서 외부 호출을 하지 않는다 (ARCHITECTURE 4장).
 * 실패해도 이미 끝난 요청·탈퇴를 되돌리지 않고 경고만 남긴다: 남은 파일은 정리 작업 8단계가 회수한다. 롤백되면 불리지 않는다.
 * 탈퇴는 게스트 정리·`POST /auth/switch`·Kakao 웹훅의 탈퇴도 모두 `UserWithdrawn`을 발행하므로 같은 곳에서 지운다.
 */
@Component
class ProfileImageFileListener(private val storage: ProfileImageStorage) {

	private val log = LoggerFactory.getLogger(javaClass)

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onObsolete(event: ProfileImageFileObsolete) = deleteQuietly(event.userId, event.previousVersion, "replaced or deleted")

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onWithdrawn(event: UserWithdrawn) {
		val version = event.profileImageKey ?: return
		deleteQuietly(event.userId, version, "withdrawal")
	}

	private fun deleteQuietly(userId: UUID, version: String, reason: String) {
		try {
			storage.delete(ProfileImageKeys.objectKey(userId, version))
		} catch (e: Exception) {
			log.warn("profile image file of user {} was not deleted after {} ({}); the cleanup job will collect it", userId, reason, e.javaClass.simpleName)
		}
	}
}
