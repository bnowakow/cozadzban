// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import com.fasterxml.jackson.annotation.JsonIgnore
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.ReadOnlyProperty
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("article")
data class Article(
    @Id val id: Long? = null,
    val url: String,
    val language: String,
    val title: String? = null,
    val thumbnail: String? = null,
    val lead: String? = null,
    val quote: String? = null,
    val aiSummary: String? = null,
    @JsonIgnore val createdByUserId: Long,
    @ReadOnlyProperty val createdAt: Instant? = null,
)
