package neuromate

import (
	"context"
	"testing"
)

func reply(structured map[string]any, text string) map[string]any {
	return map[string]any{"content": []any{map[string]any{"type": "text", "text": text}}, "structuredContent": structured}
}

func TestUserLocaleFields(t *testing.T) {
	g := clone(result(t, "get_game_start"))
	game := g["structuredContent"].(map[string]any)["game"].(map[string]any)
	game["user_locale"], game["opponent_locale"] = "fa", "de"
	pos, err := newFake(t, map[string][]any{"get_game": {g}}).client().GetGame(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	if pos.UserLocale != "fa" || pos.OpponentLocale != "de" {
		t.Fatalf("locales: %q %q", pos.UserLocale, pos.OpponentLocale)
	}
	plain, _ := newFake(t, map[string][]any{"get_game": {result(t, "get_game_start")}}).client().GetGame(context.Background(), "")
	if plain.UserLocale != "" || plain.OpponentLocale != "" {
		t.Fatalf("unset locales must be empty: %q %q", plain.UserLocale, plain.OpponentLocale)
	}
	lobby, err := newFake(t, map[string][]any{"list_challenges": {reply(map[string]any{"user_locale": "ja", "incoming_challenges": []any{
		map[string]any{"id": "ch_1", "from": map[string]any{"name": "Hanna", "kind": "human"}, "from_locale": "de"}}}, "L")}}).client().ListChallenges(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if lobby.UserLocale != "ja" || lobby.IncomingChallenges[0].FromLocale != "de" {
		t.Fatalf("lobby: %+v", lobby)
	}
}

func TestCoachMode(t *testing.T) {
	ctx := context.Background()
	f := newFake(t, map[string][]any{
		"coach_sessions": {reply(map[string]any{"sessions": []any{map[string]any{"id": "cs_1", "kind": "guided", "status": "active", "hint_mode": "ask", "locale": "fa", "game_id": "g1"}}}, "S")},
		"coach_wait": {
			reply(map[string]any{"user_locale": "fa", "next": "n", "events": []any{map[string]any{"type": "student_message", "session_id": "cs_1", "text": "help", "untrusted_text": true}}}, "W"),
			reply(map[string]any{"timeout": true, "events": []any{}}, "T"),
		},
		"coach_get_context":   {reply(map[string]any{"session": map[string]any{"id": "cs_1"}, "user_locale": "fa", "policy": "ASK mode"}, "C")},
		"coach_say":           {reply(map[string]any{}, "SENT")},
		"coach_mark_progress": {reply(map[string]any{}, "RECORDED")},
	})
	c := f.client()
	ss, err := c.CoachSessions(ctx)
	if err != nil || len(ss) != 1 || ss[0].Locale != "fa" || ss[0].HintMode != "ask" || ss[0].GameID != "g1" {
		t.Fatalf("sessions: %+v %v", ss, err)
	}
	w, err := c.CoachWait(ctx, "", 5)
	if err != nil || w.TimedOut || len(w.Events) != 1 || w.Events[0].Type != "student_message" || w.Events[0].SessionID != "cs_1" || w.Events[0].Data["text"] != "help" || w.Events[0].Data["untrusted_text"] != true {
		t.Fatalf("wait: %+v %v", w, err)
	}
	if w, _ = c.CoachWait(ctx, "", 0); !w.TimedOut {
		t.Fatal("expected a timeout")
	}
	cx, err := c.CoachGetContext(ctx, "cs_1")
	if err != nil || cx.SessionID != "cs_1" || cx.UserLocale != "fa" || cx.Policy != "ASK mode" {
		t.Fatalf("context: %+v %v", cx, err)
	}
	if _, err := c.CoachSay(ctx, CoachMessage{SessionID: "cs_1", Text: "نگاه کن", Kind: "suggestion", Arrows: []CoachArrow{{From: "e2", To: "e4", Color: "primary"}}, Circles: []CoachCircle{{Square: "f7"}}}); err != nil {
		t.Fatal(err)
	}
	if _, err := c.CoachMarkProgress(ctx, "", "fork", 50, "ok"); err != nil {
		t.Fatal(err)
	}
	say := f.callsOf("coach_say")[0].Args
	if say["text"] != "نگاه کن" || say["kind"] != "suggestion" || say["session_id"] != "cs_1" || say["arrows"] == nil {
		t.Fatalf("say args: %v", say)
	}
	if a := f.callsOf("coach_wait")[0].Args; a["timeout_seconds"] != float64(5) {
		t.Fatalf("wait args: %v", a)
	}
}
