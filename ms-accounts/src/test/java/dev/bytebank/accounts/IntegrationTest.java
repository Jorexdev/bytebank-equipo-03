package dev.bytebank.accounts;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "bytebank.internal-key=clave-interna-de-test",
        "bytebank.jwt.secret=secreto-de-test-con-al-menos-32-caracteres"
})
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    // Contenedores singleton por JVM para no invalidar el pool del contexto cacheado de Spring entre clases de test.
    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
    }
}
