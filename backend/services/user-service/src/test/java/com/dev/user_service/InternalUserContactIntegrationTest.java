package com.dev.user_service;

import com.dev.user_service.entities.User;
import com.dev.user_service.repository.UserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** API nội bộ lấy email cho notification-service (D34): chỉ role INTERNAL gọi được. */
class InternalUserContactIntegrationTest extends AbstractUserServiceIT {

    private static final String SERVICE_ID = "00000000-0000-0000-0000-000000000000";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    @Autowired
    private UserRepository userRepository;

    private MockMvc mvc;
    private User user;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(springSecurityFilterChain).build();
        // Tài khoản seed (ApplicationRunConfig) luôn có trong DB test
        user = userRepository.findAll().stream().filter(u -> u.getDeletedAt() == null).findFirst().orElseThrow();
    }

    @Test
    void internalCallerGetsEmailAndName() throws Exception {
        mvc.perform(get("/internal/users/" + user.getId() + "/contact")
                        .header("X-User-Id", SERVICE_ID).header("X-User-Role", "INTERNAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.result.email").value(user.getEmail()))
                .andExpect(jsonPath("$.result.fullName").value(user.getFullName()));
    }

    @Test
    void normalRolesAndAnonymousCannotReadContacts() throws Exception {
        for (String role : new String[]{"BUYER", "STAFF", "ADMIN"}) {
            mvc.perform(get("/internal/users/" + user.getId() + "/contact")
                            .header("X-User-Id", user.getId().toString()).header("X-User-Role", role))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(1004));
        }
        // Không có danh tính: user-service không có entry point JSON riêng (oauth2Login có thể redirect),
        // điều cần chắc chắn là không lộ email
        mvc.perform(get("/internal/users/" + user.getId() + "/contact"))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus())
                        .isNotEqualTo(200))
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString()).doesNotContain(user.getEmail()));
    }

    @Test
    void unknownUserIsNotFound() throws Exception {
        mvc.perform(get("/internal/users/" + UUID.randomUUID() + "/contact")
                        .header("X-User-Id", SERVICE_ID).header("X-User-Role", "INTERNAL"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(2601));
    }
}
