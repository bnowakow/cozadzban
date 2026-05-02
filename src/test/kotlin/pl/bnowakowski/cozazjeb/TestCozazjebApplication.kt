package pl.bnowakowski.cozazjeb

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<CozazjebApplication>().with(TestcontainersConfiguration::class).run(*args)
}
