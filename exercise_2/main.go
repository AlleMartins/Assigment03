package main

import (
	"fmt"
	"math/rand"
	"time"

	"odds-evens/internal/player"
	"odds-evens/internal/referee"
)

func main() {
	// Example: 8 players (2^3), 3 rounds
	numPlayers := 8
	if numPlayers%2 != 0 {
		fmt.Println("Number of players must be even")
		return
	}

	// Seed random number generator
	rand.Seed(time.Now().UnixNano())

	// Create players
	players := make([]*player.Player, numPlayers)
	for i := 0; i < numPlayers; i++ {
		players[i] = player.New(i + 1)
		players[i].Start()
	}

	// Create referee and register players
	ref := referee.New()
	for _, p := range players {
		ref.RegisterPlayer(p)
	}

	// Run the tournament
	ref.RunTournament()

	// Cleanup
	ref.Shutdown()

	fmt.Println("\nTournament complete!")
}
