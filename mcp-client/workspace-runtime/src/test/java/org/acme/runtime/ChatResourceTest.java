package org.acme.runtime;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class ChatResourceTest {

    @Test
    void rejectsEmptyMessage() {
        given()
                .contentType("application/json")
                .body("{}")
                .when()
                .post("/api/chat")
                .then()
                .statusCode(200)
                .body("error", is("El mensaje es obligatorio."));
    }
}
