package com.duriseo.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.util.TimeZone

@SpringBootApplication
@ConfigurationPropertiesScan
class DuriseoServerApplication

fun main(args: Array<String>) {
	// 일시는 UTC로 다룬다 (PRD NFR-TIME-01, ERD 1장)
	TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
	runApplication<DuriseoServerApplication>(*args)
}
