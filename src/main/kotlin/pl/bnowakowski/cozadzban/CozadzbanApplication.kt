package pl.bnowakowski.cozadzban

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class CozadzbanApplication

fun main(args: Array<String>) {
	runApplication<CozadzbanApplication>(*args)
}
