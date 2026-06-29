package pl.bnowakowski.cozadzban

import com.vaadin.flow.component.dependency.StyleSheet
import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.theme.aura.Aura
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
@StyleSheet(Aura.STYLESHEET)
class CozadzbanApplication : AppShellConfigurator

fun main(args: Array<String>) {
	runApplication<CozadzbanApplication>(*args)
}
