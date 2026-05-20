// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.bnowakowski.cozadzban.article.ArticleUrlConflictException
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportAlreadyRunningException
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportNotRunningException
import pl.bnowakowski.cozadzban.enrichment.EnrichmentException
import pl.bnowakowski.cozadzban.user.AllowlistEmailConflictException
import pl.bnowakowski.cozadzban.user.LastAdminRequiredException
import java.net.URI

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException, request: HttpServletRequest): ProblemDetail {
        val detail = ex.bindingResult.fieldErrors
            .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        logArticleWriteException(request, HttpStatus.BAD_REQUEST, "validation", detail)
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException, request: HttpServletRequest): ProblemDetail {
        val detail = "Malformed request body: ${ex.mostSpecificCause.message}"
        logArticleWriteException(request, HttpStatus.BAD_REQUEST, "unreadable-body", detail)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            detail,
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException, request: HttpServletRequest): ProblemDetail {
        val detail = ex.message ?: "Bad request"
        logArticleWriteException(request, HttpStatus.BAD_REQUEST, "bad-request", detail)
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail)
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException, request: HttpServletRequest): ProblemDetail {
        val detail = ex.message ?: "Authentication is required"
        logArticleWriteException(request, HttpStatus.UNAUTHORIZED, "authentication", detail)
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            detail,
        )
        pd.title = "Unauthorized"
        return pd
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException, request: HttpServletRequest): ProblemDetail {
        val detail = ex.message ?: "Access is denied"
        logArticleWriteException(request, HttpStatus.FORBIDDEN, "access-denied", detail)
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN,
            detail,
        )
        pd.title = "Forbidden"
        return pd
    }

    @ExceptionHandler(EnrichmentException::class)
    fun handleEnrichment(ex: EnrichmentException, request: HttpServletRequest): ProblemDetail {
        val detail = ex.message ?: "URL enrichment failed"
        logArticleWriteException(request, HttpStatus.UNPROCESSABLE_ENTITY, "enrichment", detail)
        return ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            detail,
        )
    }

    @ExceptionHandler(ArticleUrlConflictException::class)
    fun handleArticleConflict(ex: ArticleUrlConflictException, request: HttpServletRequest): ProblemDetail {
        logArticleWriteException(request, HttpStatus.CONFLICT, "article-url-conflict", ex.url)
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "An article with URL '${ex.url}' already exists",
        )
        pd.type = URI.create("https://cozadzban.pl/problems/article-url-conflict")
        pd.title = "Article URL Already Exists"
        return pd
    }

    @ExceptionHandler(FacebookImportAlreadyRunningException::class)
    fun handleFacebookImportBusy(ex: FacebookImportAlreadyRunningException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.message ?: "Facebook import is already running",
        )
        pd.type = URI.create("https://cozadzban.pl/problems/facebook-import-busy")
        pd.title = "Facebook Import Already Running"
        return pd
    }

    @ExceptionHandler(FacebookImportNotRunningException::class)
    fun handleFacebookImportNotRunning(ex: FacebookImportNotRunningException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.message ?: "No Facebook import job is currently running",
        )
        pd.type = URI.create("https://cozadzban.pl/problems/facebook-import-not-running")
        pd.title = "No Active Facebook Import"
        return pd
    }

    @ExceptionHandler(AllowlistEmailConflictException::class)
    fun handleAllowlistEmailConflict(ex: AllowlistEmailConflictException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "User with email '${ex.email}' is already allowlisted",
        )
        pd.type = URI.create("https://cozadzban.pl/problems/allowlist-email-conflict")
        pd.title = "Allowlisted Email Already Exists"
        return pd
    }

    @ExceptionHandler(LastAdminRequiredException::class)
    fun handleLastAdminRequired(ex: LastAdminRequiredException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.message ?: "Operation would leave the system without an ADMIN user",
        )
        pd.type = URI.create("https://cozadzban.pl/problems/last-admin-required")
        pd.title = "Last Admin Required"
        return pd
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException, request: HttpServletRequest): ProblemDetail {
        val detail = ex.message ?: "Resource not found"
        logArticleWriteException(request, HttpStatus.NOT_FOUND, "not-found", detail)
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail)
    }

    private fun logArticleWriteException(
        request: HttpServletRequest,
        status: HttpStatus,
        reason: String,
        detail: String,
    ) {
        if (!isArticleWrite(request)) return

        LOG.warn(
            "Article write exception response; method={}; uri={}; status={}; reason={}; detail='{}'",
            request.method,
            request.requestURI,
            status.value(),
            reason,
            detail,
        )
    }

    private fun isArticleWrite(request: HttpServletRequest): Boolean =
        (request.method == "POST" || request.method == "PATCH") &&
            (request.requestURI == "/api/articles" || request.requestURI.startsWith("/api/articles/"))

    companion object {
        private val LOG = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
