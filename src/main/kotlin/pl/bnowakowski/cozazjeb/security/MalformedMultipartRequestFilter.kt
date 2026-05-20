// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.tomcat.util.http.InvalidParameterException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MalformedMultipartRequestFilter : OncePerRequestFilter() {

    @Throws(ServletException::class, IOException::class)
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            filterChain.doFilter(request, response)
        } catch (ex: InvalidParameterException) {
            if (handleMalformedMultipart(request, response, ex)) return
            throw ex
        } catch (ex: ServletException) {
            val invalidParameterException = ex.findCause<InvalidParameterException>()
            if (invalidParameterException != null && handleMalformedMultipart(request, response, invalidParameterException)) {
                return
            }
            throw ex
        }
    }

    private fun handleMalformedMultipart(
        request: HttpServletRequest,
        response: HttpServletResponse,
        ex: InvalidParameterException,
    ): Boolean {
        if (!request.isMultipartFormData() || !ex.isMultipartParseFailure()) {
            return false
        }
        if (response.isCommitted) {
            return false
        }

        LOG.warn(
            "Rejected malformed multipart request; method={}; uri={}; detail='{}'",
            request.method,
            request.requestURI,
            ex.message,
        )

        response.status = HttpServletResponse.SC_BAD_REQUEST
        response.contentType = MediaType.TEXT_PLAIN_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write("Malformed multipart request")
        return true
    }

    private fun HttpServletRequest.isMultipartFormData(): Boolean =
        contentType
            ?.substringBefore(';')
            ?.trim()
            ?.equals(MediaType.MULTIPART_FORM_DATA_VALUE, ignoreCase = true)
            ?: false

    private fun InvalidParameterException.isMultipartParseFailure(): Boolean =
        sequenceOfCauses().any { cause ->
            cause.javaClass.name.startsWith("org.apache.tomcat.util.http.fileupload.")
        }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? =
        sequenceOfCauses().filterIsInstance<T>().firstOrNull()

    private fun Throwable.sequenceOfCauses(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    companion object {
        private val LOG = LoggerFactory.getLogger(MalformedMultipartRequestFilter::class.java)
    }
}
