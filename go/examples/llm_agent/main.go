// Skeleton for an LLM-driven agent. Provider-agnostic: replace askLLM with any model call.
//
//	NEUROMATE_API_KEY=nm_live_... LLM_BASE_URL=https://your-provider/v1 LLM_API_KEY=... LLM_MODEL=... go run ./examples/llm_agent
//
// askLLM speaks the widely supported "chat completions" HTTP shape with net/http and environment
// variables only; nothing is shipped or hard-coded.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"regexp"
	"strings"
	"time"

	neuromate "github.com/soh1eil/neuromate-sdk/go"
)

const system = "You are a chess grandmaster playing a game through a tool. You will receive the board, the legal moves " +
	"and the clocks. Reply with exactly one move taken from the legal moves, then a short reason on the next line."

func buildPrompt(pos *neuromate.Position) string {
	var b strings.Builder
	fmt.Fprintf(&b, "You are %s. Opponent: %s (%s).\nFEN: %s\n%s\n", pos.You.Color, pos.Opponent.Name, pos.Opponent.ModelLabel, pos.FEN, pos.BoardASCII)
	fmt.Fprintf(&b, "Moves so far: %s\n", strings.Join(pos.MovesSAN, " "))
	fmt.Fprintf(&b, "Your clock: %ds, opponent's: %ds\nLegal moves (UCI=SAN):", pos.Clock.YouMS/1000, pos.Clock.OpponentMS/1000)
	for _, m := range pos.LegalMoves {
		fmt.Fprintf(&b, "  %s=%s", m.UCI, m.SAN)
	}
	if pos.InCheck {
		b.WriteString("\nYou are in check.")
	}
	for _, a := range pos.IllegalAttempts { // the SDK feeds rejected moves back so the model can correct itself
		fmt.Fprintf(&b, "\nYour earlier answer %q was rejected: %s", a.Move, a.Error)
	}
	b.WriteString("\nAnswer with one legal move (UCI or SAN) on the first line.")
	return b.String()
}

// askLLM is the one function to replace with your model. This default calls an OpenAI-compatible endpoint.
func askLLM(ctx context.Context, prompt string) (string, error) {
	body, _ := json.Marshal(map[string]any{
		"model":    os.Getenv("LLM_MODEL"),
		"messages": []map[string]string{{"role": "system", "content": system}, {"role": "user", "content": prompt}},
	})
	req, _ := http.NewRequestWithContext(ctx, "POST", strings.TrimRight(os.Getenv("LLM_BASE_URL"), "/")+"/chat/completions", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+os.Getenv("LLM_API_KEY"))
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var out struct {
		Choices []struct {
			Message struct{ Content string } `json:"message"`
		} `json:"choices"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil || len(out.Choices) == 0 {
		return "", fmt.Errorf("llm: status %d, no answer", resp.StatusCode)
	}
	return out.Choices[0].Message.Content, nil
}

var token = regexp.MustCompile(`[A-Za-z0-9=+#@-]+`)

func strategy(ctx context.Context, pos *neuromate.Position) (neuromate.Move, error) {
	answer, err := askLLM(ctx, buildPrompt(pos)) // ctx expires with your clock, so a slow model cannot flag you
	if err != nil {
		return neuromate.Move{}, err
	}
	legal := map[string]bool{}
	for _, m := range pos.LegalMoves {
		legal[m.UCI], legal[m.SAN] = true, true
	}
	lines := strings.Split(strings.TrimSpace(answer), "\n")
	note := lines[len(lines)-1]
	if len(note) > 300 {
		note = note[:300]
	}
	for _, tk := range token.FindAllString(answer, -1) { // first token that is a legal move
		if legal[tk] || legal[strings.TrimRight(tk, "+#")] {
			return neuromate.Move{Move: tk, Note: note}, nil
		}
	}
	return neuromate.UCI(strings.Fields(answer)[0]), nil // let the server's teaching error and the retry loop handle it
}

func main() {
	client, err := neuromate.NewClient("")
	if err != nil {
		log.Fatal(err)
	}
	// Leave 3 s for network and model latency.
	final, err := client.Play(context.Background(), strategy, neuromate.WithTimeControl("10+5"), neuromate.WithSafetyMargin(3*time.Second))
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(final.Result)
}
