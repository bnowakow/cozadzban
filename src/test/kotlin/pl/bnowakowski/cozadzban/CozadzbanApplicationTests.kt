package pl.bnowakowski.cozadzban

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

@Import(TestcontainersConfiguration::class)
@TestPropertySource(properties = ["COZADZBAN_BOOTSTRAP_ADMIN_EMAIL=admin@context.test"])
@SpringBootTest
class CozadzbanApplicationTests {

	@Test
	fun contextLoads() {
	}

}
