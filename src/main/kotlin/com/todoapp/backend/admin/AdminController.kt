package com.todoapp.backend.admin

import com.todoapp.backend.common.BaseResponse
import com.todoapp.backend.user.UserRole
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/admin")
class AdminController {

    /**
     * Cheapest possible authorised endpoint — no database access beyond the identity the admin filter
     * already loaded. The panel calls it right after login and treats 403 as terminal.
     */
    @GetMapping("/me")
    fun me(): BaseResponse<AdminMeData> {
        val admin = CurrentAdmin.get()
        return BaseResponse.ok(
            AdminMeData(
                id = admin.id,
                email = admin.email,
                displayName = admin.displayName,
                // Reaching this method at all means the three gates passed, so the role is not in doubt.
                role = UserRole.ADMIN.name,
                serverTime = Instant.now().toString(),
            ),
        )
    }
}
