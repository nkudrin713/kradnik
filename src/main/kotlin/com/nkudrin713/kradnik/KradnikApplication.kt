package com.nkudrin713.kradnik

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class KradnikApplication

fun main(args: Array<String>) {
	runApplication<KradnikApplication>(*args)
}
