package dev.bytebank.cards;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(properties = {
        "bytebank.internal-key=clave-interna-de-test",
        "bytebank.jwt.secret=secreto-de-test-con-al-menos-32-caracteres"
})
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    static {
        POSTGRES.start();
    }
}
