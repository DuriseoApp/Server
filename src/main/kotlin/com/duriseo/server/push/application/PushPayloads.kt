package com.duriseo.server.push.application

import java.util.UUID

/** 동기화 신호의 `resource` (API.md 6장) */
enum class SyncResource { EVENT, ANNIVERSARY, MEMO, SPACE, NOTIFICATION }

/** 동기화 신호의 `action` (API.md 6장). `UNLINKED`·`LINKED`는 `SPACE`만 쓴다 */
enum class SyncAction { CREATED, UPDATED, DELETED, UNLINKED, LINKED }

/**
 * FCM data 메시지의 내용 (API.md 6장, 7장 "푸시 전송 형식", D-136, D-142). FCM data 값은 문자열뿐이라 값이 없는 키는 넣지 않는다.
 */
object PushPayloads {

	/** `{"type": "SYNC", "resource": …, "id": …, "action": …}`. [id]가 null이면 `id`를 넣지 않는다 (`SPACE`/`LINKED`) */
	fun sync(resource: SyncResource, id: UUID?, action: SyncAction): Map<String, String> = buildMap {
		put("type", "SYNC")
		put("resource", resource.name)
		if (id != null) put("id", id.toString())
		put("action", action.name)
	}

	/** 보이는 푸시: `type`(`PUSH`), `notificationId`, `kind`(`notifications.type`), `title`, `body`, `targetType`, `targetId` */
	fun push(n: NotificationSnapshot): Map<String, String> = buildMap {
		put("type", "PUSH")
		put("notificationId", n.id.toString())
		put("kind", n.type.name)
		put("title", n.title)
		n.body?.let { put("body", it) }
		n.targetType?.let { put("targetType", it.name) }
		n.targetId?.let { put("targetId", it.toString()) }
	}
}
