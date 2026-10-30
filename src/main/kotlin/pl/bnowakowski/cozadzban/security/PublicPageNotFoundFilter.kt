// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.security

import com.vaadin.flow.server.VaadinServletContext
import com.vaadin.flow.server.startup.ApplicationRouteRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.ApplicationContext
import org.springframework.http.server.PathContainer
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.pattern.PathPatternParser
import pl.bnowakowski.cozadzban.ui.PublicNotFoundPage

/** Unknown public page URLs should be 404s, rather than security's default empty 401. */
class PublicPageNotFoundFilter(private val context: ApplicationContext) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI.removePrefix(request.contextPath)
        if (request.method !in setOf("GET", "HEAD") || path == "/" ||
            RESERVED.any { path == it || path.startsWith("$it/") } ||
            !request.getHeader("Accept").orEmpty().let { it.isEmpty() || it.contains("text/html") || it.contains("*/*") } ||
            exists(request, path)
        ) {
            chain.doFilter(request, response)
            return
        }
        response.status = 404
        response.contentType = "text/html"
        response.characterEncoding = "UTF-8"
        response.setHeader("Cache-Control", "no-store")
        if (request.method != "HEAD") response.writer.write(PublicNotFoundPage.html())
    }

    private fun exists(request: HttpServletRequest, path: String): Boolean {
        val registry = ApplicationRouteRegistry.getInstance(VaadinServletContext(request.servletContext))
        if (registry.getNavigationTarget(path.trim('/')).isPresent) return true
        val mappings = context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping::class.java)
        val parsed = PathContainer.parsePath(path)
        if (mappings.handlerMethods.keys.any { mapping ->
                mapping.patternValues.any { PathPatternParser.defaultInstance.parse(it).matches(parsed) }
            }) return true
        // Do not let path traversal turn a resource-existence check into a page match.
        if (path.contains("..") || path.contains('%')) return false
        return context.getResource("classpath:/static$path").exists() ||
            context.getResource("classpath:/META-INF/resources$path").exists()
    }

    private companion object {
        // Authentication, APIs and framework traffic retain their existing security/error semantics.
        val RESERVED = listOf("/api", "/admin", "/auth", "/oauth2", "/login", "/logout", "/error",
            "/actuator", "/VAADIN", "/vaadin", "/aura", "/lumo", "/themes", "/frontend", "/webjars",
            "/v3", "/swagger-ui", "/rss", "/stories",
            "/generated-images", "/favicons", "/flags", "/.well-known")
    }
}
