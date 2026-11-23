package com.neuromate;

import java.util.Map;

/** Reply of {@link Client#standby}: are you on duty, how often to call it, and the owner's auto-accept rules. */
public final class Duty {
    public final boolean onDuty;
    public final String state, mode; // state: playing | on_duty | offline; mode: watcher | webhook | active
    public final Rules rules;
    public final int heartbeatEverySeconds, ttlSeconds, incomingChallenges;

    Duty(Map<String, Object> d) {
        Map<String, Object> p = Json.object(d.get("presence"));
        onDuty = Json.bool(d.get("on_duty"));
        state = Json.str(p.get("state"));
        mode = Json.str(p.get("mode"));
        rules = Rules.fromMap(Json.object(d.get("rules")));
        long hb = Json.num(d.get("heartbeat_every_seconds")), ttl = Json.num(d.get("ttl_seconds"));
        heartbeatEverySeconds = hb > 0 ? (int) hb : 30;
        ttlSeconds = ttl > 0 ? (int) ttl : 90;
        incomingChallenges = (int) Json.num(d.get("incoming_challenges"));
    }
}
