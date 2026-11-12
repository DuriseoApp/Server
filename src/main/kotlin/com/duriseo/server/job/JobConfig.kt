package com.duriseo.server.job

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 스케줄러를 켠다 (일정 알림, 기념일 알림, 정리 작업). 서버 1대를 가정하므로 분산 잠금(ShedLock)은 없다 (ERD DB-03).
 * `duriseo.jobs.enabled=false`면 켜지 않는다: 테스트가 시계를 옮기며 작업 메서드를 직접 부르므로 자동 실행이 끼어들지 않게 한다.
 * 같은 작업은 `fixedDelay`·`cron`이라 이전 실행이 끝나야 다음이 시작한다(같은 인스턴스 안에서 겹치지 않는다).
 * 작업끼리 서로 기다리지 않게 스케줄러 스레드 수를 `spring.task.scheduling.pool.size`로 늘렸다(application.yml).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["duriseo.jobs.enabled"], havingValue = "true", matchIfMissing = true)
class JobConfig
