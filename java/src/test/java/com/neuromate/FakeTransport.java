package com.neuromate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Replays recorded tool results (sdk/testdata) as a scripted MCP server. */
final class FakeTransport implements Transport {
    final Map<String, List<Object>> script = new LinkedHashMap<>();
    final List<String> names = new ArrayList<>();
    final List<Map<String, Object>> args = new ArrayList<>();
    int forcedStatus = 0;
    String forcedBody = "";
    String contentType = "application/json";

    static Map<String, Object> fixture(String name) {
        try {
            return Json.object(Json.parse(Files.readString(Path.of("..", "testdata", name + ".json"))));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    static Map<String, Object> result(String name) {
        return Json.object(Json.object(fixture(name).get("response")).get("result"));
    }

    /** A wait_for_turn result around the game of a recorded fixture. */
    static Map<String, Object> waitResult(String reason, String gameFrom) {
        Map<String, Object> g = Json.object(Json.object(result(gameFrom).get("structuredContent")).get("game"));
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("reason", reason);
        sc.put("events", new ArrayList<>());
        sc.put("game", g);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("content", List.of(Map.of("type", "text", "text", "WAIT")));
        r.put("structuredContent", sc);
        return r;
    }

    FakeTransport on(String tool, Object... results) {
        script.put(tool, new ArrayList<>(List.of(results)));
        return this;
    }

    @Override
    public synchronized Response post(String url, Map<String, String> headers, String body, long timeoutMs) throws IOException {
        if (forcedStatus != 0) return new Response(forcedStatus, Map.of("content-type", "application/json"), forcedBody);
        Map<String, Object> req = Json.object(Json.parse(body));
        String method = Json.str(req.get("method"));
        Object id = req.get("id");
        Object result;
        if (method.equals("notifications/initialized")) return new Response(202, Map.of(), "");
        if (method.equals("initialize")) {
            result = Map.of("serverInfo", Map.of("name", "fake", "version", "1"));
        } else {
            Map<String, Object> p = Json.object(req.get("params"));
            String name = Json.str(p.get("name"));
            names.add(name);
            args.add(Json.object(p.get("arguments")));
            List<Object> q = script.get(name);
            if (q == null) throw new IOException("no scripted reply for " + name);
            result = q.size() > 1 ? q.remove(0) : q.get(0);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("jsonrpc", "2.0");
        resp.put("id", id);
        resp.put("result", result);
        String json = Json.stringify(resp);
        if (contentType.contains("event-stream")) json = "event: message\ndata: " + json + "\n\n";
        return new Response(200, Map.of("content-type", contentType), json);
    }
}
