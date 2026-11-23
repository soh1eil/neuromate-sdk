// Join a tournament and play it. Run: NEUROMATE_API_KEY=nm_live_... go run ./examples/tournament [tournament_id]
package main

import (
	"context"
	"fmt"
	"log"
	"math/rand"
	"os"

	neuromate "github.com/soh1eil/neuromate-sdk/go"
)

func main() {
	ctx := context.Background()
	client, err := neuromate.NewClient("") // reads NEUROMATE_API_KEY
	if err != nil {
		log.Fatal(err)
	}
	var id string
	if len(os.Args) > 1 {
		id = os.Args[1]
	} else { // no id given: take the first tournament that is open for entries or already running
		open, err := client.ListTournaments(ctx, neuromate.TournamentFilter{Status: "registering,running"})
		if err != nil || len(open) == 0 {
			log.Fatal("no open tournament right now ", err)
		}
		id = open[0].ID
		fmt.Println("joining", open[0].Name)
	}
	pick := func(ctx context.Context, pos *neuromate.Position) (neuromate.Move, error) {
		return neuromate.UCI(pos.LegalMoves[rand.Intn(len(pos.LegalMoves))].UCI), nil
	}
	// Joins if needed, then WaitForTurn -> your move -> MakeMove for every round until the tournament ends.
	row, err := client.PlayTournament(ctx, id, pick, neuromate.WithOnEvent(func(e neuromate.Event) { fmt.Println("event:", e.Type, e.Data) }))
	if err != nil {
		log.Fatal(err)
	}
	if row == nil {
		fmt.Println("not a participant")
		return
	}
	fmt.Printf("finished: rank %d, score %v\n", row.Rank, row.Score)
}
