package com.neuromate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The owner's auto-accept rules (mirrors server/internal/duty/rules.go; both are tested against
 * sdk/testdata/auto_accept_cases.json). Empty lists mean "any".
 */
public final class Rules {
    /** What to do with a challenge: accept, decline or ignore (leave it for the owner). */
    public static final class Decision {
        public final String action; // accept | decline | ignore
        public final String reason;

        public Decision(String action, String reason) {
            this.action = action;
            this.reason = reason;
        }
    }

    public boolean enabled;
    public List<String> speeds = new ArrayList<>(), variants = new ArrayList<>(), opponentKinds = new ArrayList<>();
    public String rated = "any", ruleset = "any"; // rated: any|casual|rated, ruleset: any|casual|strict
    public int maxConcurrent = 1;
    public boolean declineOthers;

    public Rules() {}

    public static Rules enabled() {
        Rules r = new Rules();
        r.enabled = true;
        return r;
    }

    public static Rules fromMap(Map<String, Object> d) {
        Rules r = new Rules();
        if (d == null) return r;
        r.enabled = Json.bool(d.get("enabled"));
        r.speeds = strings(d.get("speeds"));
        r.variants = strings(d.get("variants"));
        r.opponentKinds = strings(d.get("opponent_kinds"));
        String rated = Json.str(d.get("rated")), ruleset = Json.str(d.get("ruleset"));
        r.rated = rated.isEmpty() ? "any" : rated;
        r.ruleset = ruleset.isEmpty() ? "any" : ruleset;
        r.maxConcurrent = (int) Math.max(1, Json.num(d.get("max_concurrent")));
        r.declineOthers = Json.bool(d.get("decline_others"));
        return r;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?>) for (Object x : (List<?>) o) out.add(String.valueOf(x));
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Rules)) return false;
        Rules r = (Rules) o;
        return enabled == r.enabled && speeds.equals(r.speeds) && variants.equals(r.variants) && opponentKinds.equals(r.opponentKinds)
                && rated.equals(r.rated) && ruleset.equals(r.ruleset) && maxConcurrent == r.maxConcurrent && declineOthers == r.declineOthers;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(enabled, speeds, variants, opponentKinds, rated, ruleset, maxConcurrent, declineOthers);
    }

    /**
     * @param c speed, variant, rated (Boolean), ruleset (casual | fide_strict), from_kind (human | agent | bot)
     * @param playing games running now
     */
    public Decision evaluate(Map<String, Object> c, int playing) {
        if (!enabled) return new Decision("ignore", "auto-accept is off");
        String speed = Json.str(c.get("speed")), variant = Json.str(c.get("variant")), kind = Json.str(c.get("from_kind"));
        if (!speeds.isEmpty() && !speeds.contains(speed)) return refuse("time control " + speed + " is not accepted");
        if (!variants.isEmpty() && !variants.contains(variant)) return refuse("variant " + variant + " is not accepted");
        boolean isRated = Json.bool(c.get("rated"));
        if (rated.equals("casual") && isRated) return refuse("only casual games are accepted");
        if (rated.equals("rated") && !isRated) return refuse("only rated games are accepted");
        String rs = Json.str(c.get("ruleset"));
        boolean strict = rs.equals("fide_strict") || rs.equals("strict");
        if (ruleset.equals("casual") && strict) return refuse("strict (FIDE) games are not accepted");
        if (ruleset.equals("strict") && !strict) return refuse("only strict (FIDE) games are accepted");
        if (!opponentKinds.isEmpty() && !opponentKinds.contains(kind)) return refuse("opponents of kind " + kind + " are not accepted");
        if (playing >= Math.max(1, maxConcurrent)) return new Decision("ignore", "already playing " + playing + " game(s)");
        return new Decision("accept", "");
    }

    private Decision refuse(String why) {
        return new Decision(declineOthers ? "decline" : "ignore", why);
    }

    private static final Pattern LABEL = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(?:[+|]\\s*(\\d+))?");
    private static final Pattern DAYS = Pattern.compile("\\d\\s*d");

    /** bullet / blitz / rapid / classical / correspondence / untimed, from a control object (may be null) or a label like "3+2". */
    public static String speedOf(Map<String, Object> control, String label) {
        label = label == null ? "" : label;
        String kind = control == null ? "" : Json.str(control.get("kind"));
        if (kind.equals("correspondence") || DAYS.matcher(label).find() || label.contains("day")) return "correspondence";
        if (kind.equals("untimed") || label.trim().equalsIgnoreCase("untimed")) return "untimed";
        long est;
        if (control != null && !control.isEmpty()) {
            long inc = Math.max(Json.num(control.get("increment_ms")), Math.max(Json.num(control.get("delay_ms")), Json.num(control.get("bronstein_ms"))));
            est = (Json.num(control.get("initial_ms")) + 40 * inc) / 1000;
        } else {
            Matcher m = LABEL.matcher(label);
            if (!m.find()) return "classical";
            est = (long) (Double.parseDouble(m.group(1)) * 60) + 40 * (m.group(2) == null ? 0 : Long.parseLong(m.group(2)));
        }
        return est < 180 ? "bullet" : est < 480 ? "blitz" : est < 1500 ? "rapid" : "classical";
    }

    private static Map<String, Object> info(String speed, String variant, boolean rated, String ruleset, String kind) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("speed", speed);
        m.put("variant", variant.isEmpty() ? "standard" : variant);
        m.put("rated", rated);
        m.put("ruleset", ruleset.isEmpty() ? "casual" : ruleset);
        m.put("from_kind", kind.isEmpty() ? "agent" : kind);
        return m;
    }

    /** The fields {@link #evaluate} reads, from a challenge_received event. */
    public static Map<String, Object> infoFromEvent(Map<String, Object> ev) {
        Map<String, Object> d = Json.object(ev.get("data"));
        String speed = Json.str(d.get("speed"));
        return info(speed.isEmpty() ? speedOf(null, Json.str(d.get("time_control"))) : speed, Json.str(d.get("variant")), Json.bool(d.get("rated")),
                Json.str(d.get("ruleset")), Json.str(d.get("from_kind")));
    }

    /** The same, from an entry of whoami's incoming_challenges. */
    public static Map<String, Object> infoFromChallenge(Map<String, Object> c) {
        Map<String, Object> p = Json.object(c.get("params"));
        Object ctl = p.get("control");
        return info(speedOf(ctl instanceof Map ? Json.object(ctl) : null, ""), Json.str(p.get("variant")), Json.bool(p.get("rated")), Json.str(p.get("ruleset")),
                Json.str(Json.object(c.get("from")).get("kind")));
    }
}
