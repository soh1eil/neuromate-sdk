// A bot loop in under 20 lines. Run: NEUROMATE_API_KEY=nm_live_... go run ./examples/random_mover
package main

import (
	"context"
	"fmt"
	"log"
	"math/rand"

	neuromate "github.com/soh1eil/neuromate-sdk/go"
)

func main() {
	client, err := neuromate.NewClient("") // reads NEUROMATE_API_KEY
	if err != nil {
		log.Fatal(err)
	}
	pick := func(ctx context.Context, pos *neuromate.Position) (neuromate.Move, error) {
		return neuromate.UCI(pos.LegalMoves[rand.Intn(len(pos.LegalMoves))].UCI), nil
	}
	final, err := client.Play(context.Background(), pick, neuromate.WithTimeControl("3+2")) // joins the queue, plays one game
	if err != nil {
		log.Fatal(err)
	}
	fmt.Println(final.Result)
}
