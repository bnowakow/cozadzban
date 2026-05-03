// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.bnowakowski.cozazjeb.article.ArticleUrlConflictException
import pl.bnowakowski.cozazjeb.enrichment.EnrichmentException
import pl.bnowakowski.cozazjeb.user.AllowlistEmailConflictException
import pl.bnowakowski.cozazjeb.user.LastAdminRequiredException
import java.net.URI

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(ex: MethodArgumentNotValidException): ProblemDetail {
        val detail = ex.bindingResult.fieldErrors
            .joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(ex: HttpMessageNotReadableException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            "Malformed request body: ${ex.mostSpecificCause.message}",
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.message ?: "Bad request")

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthentication(ex: AuthenticationException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.UNAUTHORIZED,
            ex.message ?: "Authentication is required",
        )
        pd.title = "Unauthorized"
        return pd
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(ex: AccessDeniedException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.FORBIDDEN,
            ex.message ?: "Access is denied",
        )
        pd.title = "Forbidden"
        return pd
    }

    @ExceptionHandler(EnrichmentException::class)
    fun handleEnrichment(ex: EnrichmentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ex.message ?: "URL enrichment failed",
        )

    @ExceptionHandler(ArticleUrlConflictException::class)
    fun handleArticleConflict(ex: ArticleUrlConflictException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "An article with URL '${ex.url}' already exists",
        )
        pd.type = URI.create("https://cozazjeb.pl/problems/article-url-conflict")
        pd.title = "Article URL Already Exists"
        return pd
    }

    @ExceptionHandler(AllowlistEmailConflictException::class)
    fun handleAllowlistEmailConflict(ex: AllowlistEmailConflictException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            "User with email '${ex.email}' is already allowlisted",
        )
        pd.type = URI.create("https://cozazjeb.pl/problems/allowlist-email-conflict")
        pd.title = "Allowlisted Email Already Exists"
        return pd
    }

    @ExceptionHandler(LastAdminRequiredException::class)
    fun handleLastAdminRequired(ex: LastAdminRequiredException): ProblemDetail {
        val pd = ProblemDetail.forStatusAndDetail(
            HttpStatus.CONFLICT,
            ex.message ?: "Operation would leave the system without an ADMIN user",
        )
        pd.type = URI.create("https://cozazjeb.pl/problems/last-admin-required")
        pd.title = "Last Admin Required"
        return pd
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.message ?: "Resource not found")
}
