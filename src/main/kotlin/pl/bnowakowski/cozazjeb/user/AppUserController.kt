// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.user

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

@RestController
@RequestMapping("/api/users")
class AppUserController(
    private val appUserService: AppUserService,
) {

    @GetMapping
    fun listUsers(): List<AppUser> = appUserService.list()

    @PostMapping
    fun addUser(@Valid @RequestBody input: AppUserInput): ResponseEntity<AppUser> {
        val user = appUserService.create(input)
        val location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}").buildAndExpand(user.id).toUri()
        return ResponseEntity.created(location).body(user)
    }

    @DeleteMapping("/{id}")
    fun removeUser(@PathVariable id: Long): ResponseEntity<Void> {
        appUserService.delete(id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/{id}")
    fun updateUserRole(@PathVariable id: Long, @Valid @RequestBody patch: AppUserRolePatch): AppUser =
        appUserService.updateRole(id, patch)

    @PatchMapping("/{id}/status")
    fun updateUserStatus(@PathVariable id: Long, @RequestBody patch: AppUserStatusPatch): ResponseEntity<AppUser> =
        when (patch.status) {
            AppUserStatus.ACTIVE -> ResponseEntity.ok(appUserService.restore(id))
            AppUserStatus.DELETED -> {
                appUserService.delete(id)
                ResponseEntity.noContent().build()
            }
        }
}
