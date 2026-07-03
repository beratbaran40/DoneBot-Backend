package com.todoapp.backend.integration

import com.todoapp.backend.auth.AuthService
import com.todoapp.backend.auth.JwtService
import com.todoapp.backend.auth.AuthResponseData
import com.todoapp.backend.auth.RegisterRequest
import com.todoapp.backend.group.GroupService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Base for full-stack integration tests: real Spring context + MockMvc + in-memory H2 (test profile).
 *
 * Auth uses REAL JWTs minted by the actual [JwtService], so requests exercise the whole security
 * filter chain ([com.todoapp.backend.auth.JwtAuthFilter] → principal = userId:Long → CurrentUser.id()).
 * `@Transactional` rolls each test back; unique per-user emails are a second guard against bleed-over.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
abstract class AbstractIntegrationTest {
    @Autowired protected lateinit var mockMvc: MockMvc

    @Autowired protected lateinit var authService: AuthService

    @Autowired protected lateinit var groupService: GroupService

    @Autowired protected lateinit var jwtService: JwtService

    /** Seeds a fresh user with a unique email and returns its issued token pair + profile. */
    protected fun registerUser(displayName: String = "Test User"): AuthResponseData =
        authService.register(
            RegisterRequest(
                email = "u-${UUID.randomUUID()}@test.com",
                password = "password123",
                displayName = displayName,
            ),
        )

    /** Builds an `Authorization: Bearer <token>` header value for the given user id. */
    protected fun bearer(userId: Long): String = "Bearer " + jwtService.issueAccessToken(userId)
}
