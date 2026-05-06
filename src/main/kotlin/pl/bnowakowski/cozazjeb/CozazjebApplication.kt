package pl.bnowakowski.cozazjeb

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class CozazjebApplication

fun main(args: Array<String>) {
	runApplication<CozazjebApplication>(*args)
}
