package com.duriseo.server.storage

import java.io.InputStream
import java.time.Instant
import java.util.UUID

/** 저장소에 있는 객체 하나. [lastModified]는 마지막 수정 시각이다 (정리 작업의 24시간 기준, ERD 4.4) */
data class StoredObject(val key: String, val lastModified: Instant)

/**
 * 프로필 사진 저장소 (API.md 8장). 개발 환경은 로컬 디스크, 운영 환경은 Amazon S3다.
 * 키는 [ProfileImageKeys]가 만든다. 구현은 트랜잭션과 무관하다: 부르는 쪽이 DB 커밋 뒤에 지운다 (ARCHITECTURE 4장).
 */
interface ProfileImageStorage {

	/** [key]에 [bytes](JPEG)를 저장한다. 같은 키가 있으면 덮어쓴다 */
	fun put(key: String, bytes: ByteArray)

	/** [key]의 내용을 스트림으로 연다. 없으면 null. 부른 쪽이 닫는다 */
	fun get(key: String): InputStream?

	/** [key]를 지운다. 없어도 오류가 아니다 (멱등) */
	fun delete(key: String)

	/** [prefix]로 시작하는 객체를 하나씩 [action]에 넘긴다 (S3는 페이지를 이어서 읽는다). 전부 메모리에 올리지 않는다 */
	fun list(prefix: String, action: (StoredObject) -> Unit)
}

/** 객체 키 규칙: `profile/{userId}/{uuid}.jpg`. `users.profile_image_key` 컬럼에는 이 uuid만 들어 있다 (진행 기록 단위 7 확인 필요 1) */
object ProfileImageKeys {
	const val PREFIX = "profile/"

	fun objectKey(userId: UUID, version: String): String = "$PREFIX$userId/$version.jpg"
}
