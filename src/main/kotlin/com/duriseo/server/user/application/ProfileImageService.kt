package com.duriseo.server.user.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.time.nowInSeconds
import com.duriseo.server.space.application.SpaceService
import com.duriseo.server.space.application.SpaceUpdated
import com.duriseo.server.storage.ProfileImageKeys
import com.duriseo.server.storage.ProfileImageProcessor
import com.duriseo.server.storage.ProfileImageStorage
import com.duriseo.server.user.domain.User
import com.duriseo.server.user.domain.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.InputStream
import java.time.Clock
import java.util.UUID

/**
 * 프로필 사진 올리기·지우기·읽기 (FR-AUTH-07 AC3·AC5, NFR-FILE-01, API.md 3.1·8장).
 *
 * - 올리기: 처리(디코드·인코딩)와 저장은 **트랜잭션 밖**에서 하고, 사용자 행을 잠근 짧은 트랜잭션에서 키만 바꾼다.
 *   키 갱신이 실패하면 방금 올린 파일을 지우려 시도한다. 이전 파일은 커밋 뒤 [ProfileImageFileListener]가 지운다.
 *   같은 사용자의 업로드가 겹치면 행 잠금이 한 줄로 세워 마지막에 커밋한 쪽이 이기고, 진 쪽의 파일은 이긴 쪽이 이전 파일로 지운다
 *   (지우기가 실패한 파일은 정리 작업이 회수한다).
 * - 지우기: 사진이 없어도 성공(멱등)이고 그때는 바뀐 것이 없어 신호도 없다.
 * - 올리기·지우기가 성공하고 커플 공간이면 상대에게 SYNC `SPACE`/`UPDATED`를 보낸다 (`SpaceUpdated`, API.md 6장).
 * - 읽기: R-11(`SpaceAccessPolicy`)을 요청마다 확인한다. 파일은 트랜잭션 밖에서 스트림으로 연다.
 */
@Service
class ProfileImageService(
	private val users: UserRepository,
	private val policy: SpaceAccessPolicy,
	private val spaceService: SpaceService,
	private val processor: ProfileImageProcessor,
	private val storage: ProfileImageStorage,
	private val events: ApplicationEventPublisher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
) {

	private val log = LoggerFactory.getLogger(javaClass)
	private val tx = TransactionTemplate(transactionManager)
	private val readTx = TransactionTemplate(transactionManager).apply { isReadOnly = true }

	/**
	 * `PUT /me/profile-image`. [size]는 원본 바이트 수(10MB를 넘으면 내용을 읽기 전에 413)이고 [open]은 원본을 읽는 새 입력 스트림이다.
	 * 원본은 처리 자리를 얻은 뒤에 스트림에서 바로 읽는다(힙에 통째로 올리지 않는다).
	 * 처리 결과 JPEG를 새 uuid의 객체 키로 저장한 뒤 `profile_image_key`를 그 uuid로 바꾼다.
	 */
	fun upload(userId: UUID, size: Long, open: () -> InputStream) {
		processor.requireWithinSizeLimit(size)
		val jpeg = processor.process(size, open)
		val version = UUID.randomUUID().toString()
		val objectKey = ProfileImageKeys.objectKey(userId, version)
		storage.put(objectKey, jpeg)
		try {
			tx.executeWithoutResult { replaceVersion(userId, version) }
		} catch (e: Exception) {
			removeUploadedFile(userId, version, objectKey)
			throw e
		}
	}

	/** `DELETE /me/profile-image`: 사진이 있으면 키를 지우고 커밋 뒤 파일을 지운다. 없으면 아무것도 하지 않는다 (멱등) */
	fun delete(userId: UUID) {
		tx.executeWithoutResult {
			val user = lockActive(userId)
			val previous = user.profileImageKey ?: return@executeWithoutResult
			user.profileImageKey = null
			user.updatedAt = clock.nowInSeconds()
			publishChange(userId, previous)
		}
	}

	/**
	 * `GET /users/{id}/profile-image`: R-11을 확인하고 사진 파일을 연다. 부른 쪽이 닫는다.
	 * 권한이 없거나, 사진이 없거나, DB에는 키가 있는데 파일이 없으면 모두 404 `NOT_FOUND`다 (마지막은 경고 로그를 남긴다).
	 */
	fun open(requesterId: UUID, targetUserId: UUID): InputStream {
		val version = readTx.execute {
			policy.requireProfileImageAccess(requesterId, targetUserId)
			users.findByIdOrNull(targetUserId)?.profileImageKey
		} ?: throw notFound()
		return storage.get(ProfileImageKeys.objectKey(targetUserId, version)) ?: run {
			log.warn("profile image file is missing for user {} (version {})", targetUserId, version)
			throw notFound()
		}
	}

	private fun replaceVersion(userId: UUID, version: String) {
		val user = lockActive(userId)
		val previous = user.profileImageKey
		user.profileImageKey = version
		user.updatedAt = clock.nowInSeconds()
		publishChange(userId, previous)
	}

	/** 사용자 행을 `FOR UPDATE`로 잠그고 읽는다 (같은 사용자의 업로드·삭제·`PATCH /me`를 한 줄로 세운다). 탈퇴했으면 세션이 무효라 401 */
	private fun lockActive(userId: UUID): User =
		users.findByIdForUpdate(userId)?.takeIf { it.deletedAt == null }
			?: throw ApiException(ErrorCode.SESSION_INVALID, "user not found")

	/** 이전 파일 삭제([ProfileImageFileObsolete])와, 커플 공간이면 상대 신호([SpaceUpdated])를 발행한다. 커밋 뒤 리스너가 받는다 */
	private fun publishChange(userId: UUID, previousVersion: String?) {
		if (previousVersion != null) events.publishEvent(ProfileImageFileObsolete(userId, previousVersion))
		if (spaceService.isInCoupleSpace(userId)) {
			policy.activeSpaceIdOf(userId)?.let { events.publishEvent(SpaceUpdated(it, userId)) }
		}
	}

	/**
	 * 키 갱신이 실패했을 때 방금 올린 파일을 지운다. 커밋은 됐는데 그 뒤 예외가 난 경우(DB가 이 uuid를 가리킴)에는 지우지 않는다.
	 * 확인하지 못하면 지우지 않는다: 남는 파일은 정리 작업이 회수하지만, 쓰이는 파일을 지우면 복구할 수 없다.
	 */
	private fun removeUploadedFile(userId: UUID, version: String, objectKey: String) {
		try {
			val referenced = readTx.execute { users.findByIdOrNull(userId)?.profileImageKey == version } ?: true
			if (!referenced) storage.delete(objectKey)
		} catch (e: Exception) {
			log.warn("uploaded profile image of user {} was not removed after a failed update ({}); the cleanup job will collect it", userId, e.javaClass.simpleName)
		}
	}

	private fun notFound() = ApiException(ErrorCode.NOT_FOUND, "not found")
}
