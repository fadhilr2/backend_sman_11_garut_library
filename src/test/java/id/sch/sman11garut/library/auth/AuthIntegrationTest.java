package id.sch.sman11garut.library.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import id.sch.sman11garut.library.support.IntegrationTestBase;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void seededLibrarianLogsInAndSeesTheirProfile() throws Exception {
        mvc.perform(as(get("/api/auth/me"), adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN))
                .andExpect(jsonPath("$.role").value("LIBRARIAN"));
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameAnswer() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", ADMIN, "password", "wrong-password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", "nobody_" + unique(), "password", "wrong-password")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        // The admin account is not locked by one failure.
        adminToken();
    }

    @Test
    void fiveFailuresLockTheUsernameEvenForTheRightPassword() throws Exception {
        String username = newTeacher()[0];
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json("username", username, "password", "wrong-password")))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("username", username, "password", "rahasia123")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ACCOUNT_TEMPORARILY_LOCKED"));
    }

    @Test
    void aDeactivatedTeachersTokenStopsWorkingImmediately() throws Exception {
        String[] teacher = newTeacher();
        mvc.perform(as(get("/api/auth/me"), teacher[1])).andExpect(status().isOk());

        String users = getBody("/api/admin/users", adminToken());
        Number id = JsonPath.<java.util.List<Number>>read(users, "$[?(@.username == '" + teacher[0] + "')].id").get(0);
        mvc.perform(as(put("/api/admin/users/" + id), adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("active", "false")))
                .andExpect(status().isOk());

        mvc.perform(as(get("/api/auth/me"), teacher[1]))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void anonymousGets401AndTeacherGets403OnAdminEndpoints() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/admin/users"), newTeacher()[1]))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void framesAreAllowedFromTheSameOriginOnly() throws Exception {
        mvc.perform(get("/api/meta/filters"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void requestErrorsExplainWhatIsWrong() throws Exception {
        // An upload without the "file" part used to answer only "Request failed".
        mvc.perform(as(multipart("/api/books")
                                .file(new MockMultipartFile("metadata", "", MediaType.APPLICATION_JSON_VALUE,
                                        json("title", "No file").getBytes())), adminToken())
                        .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("file")));
    }
}
