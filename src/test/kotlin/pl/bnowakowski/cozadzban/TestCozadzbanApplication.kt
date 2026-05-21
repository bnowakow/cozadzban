package pl.bnowakowski.cozadzban

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<CozadzbanApplication>().with(TestcontainersConfiguration::class).run(*args)
}
