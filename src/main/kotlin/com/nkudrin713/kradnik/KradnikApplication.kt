package com.nkudrin713.kradnik

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KradnikApplication

fun main(args: Array<String>) {
	runApplication<KradnikApplication>(*args)
}
