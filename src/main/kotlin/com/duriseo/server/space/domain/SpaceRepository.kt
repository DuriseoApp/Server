package com.duriseo.server.space.domain

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SpaceRepository : JpaRepository<Space, UUID>
