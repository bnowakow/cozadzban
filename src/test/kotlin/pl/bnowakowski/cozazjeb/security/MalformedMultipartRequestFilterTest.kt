// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.security

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.apache.tomcat.util.http.InvalidParameterException
import org.apache.tomcat.util.http.fileupload.impl.IOFileUploadException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.IOException

class MalformedMultipartRequestFilterTest {

    private val filter = MalformedMultipartRequestFilter()

    @Test
    fun `returns 400 for malformed multipart parameter parsing failures`() {
        val request = MockHttpServletRequest("POST", "/")
        request.contentType = "${MediaType.MULTIPART_FORM_DATA_VALUE}; boundary=abc"
        val response = MockHttpServletResponse()
        val chain = FilterChain { _: ServletRequest, _: ServletResponse ->
            throw malformedMultipartException()
        }

        filter.doFilter(request, response, chain)

        assertEquals(400, response.status)
        assertEquals("Malformed multipart request", response.contentAsString)
    }

    @Test
    fun `does not swallow non-multipart invalid parameter failures`() {
        val request = MockHttpServletRequest("POST", "/")
        request.contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE
        val response = MockHttpServletResponse()
        val exception = malformedMultipartException()
        val chain = FilterChain { _: ServletRequest, _: ServletResponse ->
            throw exception
        }

        val thrown = assertThrows(InvalidParameterException::class.java) {
            filter.doFilter(request, response, chain)
        }

        assertEquals(exception, thrown)
    }

    @Test
    fun `passes valid requests through`() {
        val request = MockHttpServletRequest("GET", "/")
        val response = MockHttpServletResponse()
        var invoked = false
        val chain = FilterChain { _: ServletRequest, _: ServletResponse ->
            invoked = true
        }

        filter.doFilter(request, response, chain)

        assertEquals(true, invoked)
        assertEquals(200, response.status)
    }

    private fun malformedMultipartException(): InvalidParameterException =
        InvalidParameterException(
            IOFileUploadException(
                "Processing of multipart/form-data request failed. Stream ended unexpectedly",
                IOException("Stream ended unexpectedly"),
            ),
        )
}
