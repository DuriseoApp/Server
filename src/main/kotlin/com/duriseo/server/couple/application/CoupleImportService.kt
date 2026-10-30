package com.duriseo.server.couple.application

import com.duriseo.server.common.access.SpaceAccessPolicy
import com.duriseo.server.common.api.ApiException
import com.duriseo.server.common.api.ErrorCode
import com.duriseo.server.common.api.FieldError
import com.duriseo.server.common.api.FieldErrorDetails
import com.duriseo.server.common.api.FieldErrorReason
import com.duriseo.server.space.application.SpaceService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 기록 가져오기 결정 (`POST /couple/import`, FR-COUPLE-03, API.md 4.4 9~10단계). 권한은 R-18(가져오기 대기 중인 개인 공간이 있을 것)이며
 * `SpaceAccessPolicy`가 검사한다.
 *
 * `import: true`면 대기 중인 개인 공간의 `events`, `anniversaries`, `memos`를 커플 공간으로 옮긴 뒤 개인 공간을 지우고,
 * `false`면 개인 공간을 지운다. 어느 쪽이든 대기 공간이 사라져 `importPending`이 false가 된다. 각자 자기 대기 공간만 다룬다.
 *
 * 해제(`DELETE /couple`)와 겹치면 커플 공간 행의 잠금으로 한 줄로 세운다: 가져오기가 먼저면 기록은 커플 공간에 남고,
 * 해제가 먼저면 대기 공간이 되살아나(활성 개인 공간) 잠금 뒤 다시 확인에서 409 `NO_IMPORT_PENDING`이다.
 */
@Service
class CoupleImportService(
	private val policy: SpaceAccessPolicy,
	private val spaceService: SpaceService,
	private val content: CoupleContentGateway,
) {

	/** [rawImport]는 JSON 값 그대로다. R-18이 `import` 검사(400)보다 먼저다 */
	@Transactional
	fun decide(requesterId: UUID, rawImport: Any?) {
		val coupleSpaceId = policy.requireImportPending(requesterId).coupleSpaceId
		val shouldImport = parsedImport(rawImport)

		checkNotNull(spaceService.lockSpace(coupleSpaceId)) { "couple space to import into is missing" }
		// 잠근 뒤 다시 확인한다: 그 사이 해제됐거나 같은 사람의 다른 요청이 먼저 정했으면 409 NO_IMPORT_PENDING
		val pending = policy.requireImportPending(requesterId)
		pending.personalSpaceIds.forEach { personalSpaceId ->
			if (shouldImport) content.moveAll(personalSpaceId, pending.coupleSpaceId)
			spaceService.deleteSpace(personalSpaceId)
		}
	}

	/** `import`가 없거나 null이면 400 `VALIDATION_FAILED`(`import`, `REQUIRED`), 불리언이 아니면 `INVALID` */
	private fun parsedImport(raw: Any?): Boolean {
		if (raw is Boolean) return raw
		val reason = if (raw == null) FieldErrorReason.REQUIRED else FieldErrorReason.INVALID
		throw ApiException(ErrorCode.VALIDATION_FAILED, "import: $reason", FieldErrorDetails(listOf(FieldError("import", reason))))
	}
}
