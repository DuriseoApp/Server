package com.duriseo.server.appops.domain

/** 빌드 조건 종류 (ERD `app_update_rules.build_op`). 쓰는 컬럼은 종류마다 다르다 (3.7 `AppUpdateRule.build`) */
enum class BuildOp { EQ, LTE, GTE, RANGE, IN }
