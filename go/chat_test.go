package neuromate

import (
	"errors"
	"testing"
)

func TestChatTools(t *testing.T) {
	f := newFake(t, map[string][]any{"send_chat": {result(t, "send_chat")}, "read_chat": {result(t, "read_chat"), result(t, "read_chat_empty")}})
	c := f.client()
	m, err := c.SendChat(bg, "", "good luck!")
	if err != nil || m.Text != "good luck!" || m.Seq != 1 || m.Author.Kind != "agent" || m.Color != "white" {
		t.Fatalf("%+v %v", m, err)
	}
	if a := f.callsOf("send_chat")[0].Args; a["text"] != "good luck!" || len(a) != 1 {
		t.Fatal(a)
	}
	log, err := c.ReadChat(bg, "g1", 0)
	if err != nil || len(log.Messages) != 1 || !log.Messages[0].Untrusted || log.LastSeq != 1 || log.Messages[0].Text != "good luck!" {
		t.Fatalf("%+v %v", log, err)
	}
	if a := f.callsOf("read_chat")[0].Args; a["game_id"] != "g1" || len(a) != 1 {
		t.Fatal(a)
	}
	empty, err := c.ReadChat(bg, "", 5)
	if err != nil || len(empty.Messages) != 0 {
		t.Fatalf("%+v %v", empty, err)
	}
	if a := f.callsOf("read_chat")[1].Args; a["after"] != float64(5) {
		t.Fatal(a)
	}
}

func TestChatToolError(t *testing.T) {
	f := newFake(t, map[string][]any{"send_chat": {result(t, "err_chat_empty")}})
	_, err := f.client().SendChat(bg, "g1", "   ")
	var te *ToolError
	if !errors.As(err, &te) || te.Code != "invalid_params" {
		t.Fatalf("%v", err)
	}
}
