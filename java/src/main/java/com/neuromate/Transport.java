package com.neuromate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/** One HTTP POST. Replace it (Client.Builder#transport) to test without a network or to add a proxy. */
public interface Transport {
    final class Response {
        public final int status;
        public final Map<String, String> headers; // lower-case names
        public final String body;

        public Response(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    Response post(String url, Map<String, String> headers, String body, long timeoutMs) throws IOException;

    /** The default transport, java.net.http. */
    static Transport standard() {
        HttpClient http = HttpClient.newHttpClient();
        return (url, headers, body, timeoutMs) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMs))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(b::header);
            try {
                HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                Map<String, String> h = new HashMap<>();
                r.headers().map().forEach((k, v) -> h.put(k.toLowerCase(), v.get(0)));
                return new Response(r.statusCode(), h, r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }
}
