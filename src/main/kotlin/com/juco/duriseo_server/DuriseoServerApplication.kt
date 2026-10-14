package com.juco.duriseo_server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DuriseoServerApplication

fun main(args: Array<String>) {
	runApplication<DuriseoServerApplication>(*args)
}
