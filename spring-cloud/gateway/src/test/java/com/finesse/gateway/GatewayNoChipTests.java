package com.finesse.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 칩이 하나도 꽂혀 있지 않아도 gateway는 뜬다. 칩의 경로(/api/**)는 없고(404), 본체 경로 규칙만 남는다.
 * 칩 폴더는 비어 있는 src/test/chips-empty 를 쓴다.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT,
        properties = {"CHIPS_DIR=src/test/chips-empty", "management.server.port=0"})
class GatewayNoChipTests {

    @Value("${local.server.port}")
    private int port;

    @Test
    void 칩이_없으면_칩_경로도_없다() throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/stats/someone")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(404);
    }
}
