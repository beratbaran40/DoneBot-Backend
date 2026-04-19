package com.todoapp.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class TodoBackendApplication

fun main(args: Array<String>) {
	runApplication<TodoBackendApplication>(*args)
}
