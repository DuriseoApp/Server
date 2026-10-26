package com.duriseo.server.user.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationSettingsRepository : JpaRepository<NotificationSettings, UUID>
