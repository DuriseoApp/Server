package com.duriseo.server.push.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * 커밋이 끝난 뒤 푸시와 동기화 신호(SYNC)를 보낸다 (API.md 6장, 7장). **트랜잭션 안에서 부르지 않는다.**
 * 어떤 오류도 밖으로 던지지 않고 로그만 남긴다(이미 커밋된 요청의 응답에 영향이 없다). 재시도하지 않는다.
 * 무효 토큰 응답이면 그 기기 행을 지운다. 토큰·키는 로그에 쓰지 않는다.
 *
 * 스케줄러(묶음 C)는 알림 행을 만들어 커밋한 뒤 [dispatch]를 부른다.
 */
@Component
class NotificationDispatcher(
	private val store: PushStore,
	private val sender: PushSender,
	private val clock: Clock,
) {

	private val log = LoggerFactory.getLogger(javaClass)

	/**
	 * 알림 행 하나를 받는 사람 본인 기기에 알린다: 동기화 신호 `NOTIFICATION`/`CREATED`(푸시를 끈 종류·세션이 없는 사람도, 7장)와,
	 * [withPush]이고 푸시 조건(유효 세션 R-16, 그 종류의 설정 — 연결·해제는 설정을 안 본다)을 채울 때만 보이는 푸시(high).
	 * 일정 알림이 10분 넘게 늦었을 때(D-137)는 [withPush] = false로 부른다.
	 */
	fun dispatch(notificationId: UUID, withPush: Boolean = true) = safely("notification $notificationId") {
		val plan = store.planFor(notificationId, clock.instant()) ?: return@safely
		if (withPush && plan.pushAllowed) {
			sendAll(plan.tokens, PushPayloads.push(plan.notification), PushPriority.HIGH)
		}
		sendAll(plan.tokens, PushPayloads.sync(SyncResource.NOTIFICATION, plan.notification.id, SyncAction.CREATED), PushPriority.NORMAL)
	}

	/** 공간의 **다른** 활성 구성원 기기에 동기화 신호를 보낸다 (normal, 세션을 보지 않는다) */
	fun syncToOthers(spaceId: UUID, changedByUserId: UUID, resource: SyncResource, id: UUID?, action: SyncAction) =
		safely("sync $resource/$action") {
			syncTo(store.otherActiveMembers(spaceId, changedByUserId), resource, id, action)
		}

	/** [userIds]의 기기에 동기화 신호를 보낸다 (`LINKED`처럼 공간의 다른 구성원이 아닌 사람에게 보낼 때) */
	fun syncToUsers(userIds: Collection<UUID>, resource: SyncResource, id: UUID?, action: SyncAction) =
		safely("sync $resource/$action") { syncTo(userIds, resource, id, action) }

	private fun syncTo(userIds: Collection<UUID>, resource: SyncResource, id: UUID?, action: SyncAction) {
		sendAll(store.tokensOfUsers(userIds), PushPayloads.sync(resource, id, action), PushPriority.NORMAL)
	}

	private fun sendAll(tokens: List<String>, data: Map<String, String>, priority: PushPriority) {
		tokens.forEach { token ->
			val result = try {
				sender.send(PushMessage(token, data, priority))
			} catch (e: Exception) {
				log.warn("push send threw (type={}, error={})", data["type"], e.javaClass.simpleName)
				PushResult.FAILED
			}
			if (result == PushResult.INVALID_TOKEN) {
				try {
					store.deleteDeviceByToken(token)
				} catch (e: Exception) {
					log.warn("deleting an invalid device failed ({})", e.javaClass.simpleName)
				}
			}
		}
	}

	private fun safely(what: String, block: () -> Unit) {
		try {
			block()
		} catch (e: Exception) {
			log.warn("push dispatch failed for {} ({})", what, e.javaClass.simpleName)
		}
	}
}
