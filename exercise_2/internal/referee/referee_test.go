package referee

import (
	"odds-evens/internal/player"
	"testing"
)

func TestCalculateRounds(t *testing.T) {
	tests := []struct {
		numPlayers int
		expected   int
	}{
		{2, 1},
		{4, 2},
		{8, 3},
		{16, 4},
		{32, 5},
	}

	for _, tt := range tests {
		result := calculateRounds(tt.numPlayers)
		if result != tt.expected {
			t.Errorf("calculateRounds(%d) = %d, want %d", tt.numPlayers, result, tt.expected)
		}
	}
}

func TestRefereeRunTournament(t *testing.T) {
	r := New()

	// Create 8 players
	for i := 1; i <= 8; i++ {
		p := player.New(i)
		p.Start()
		r.RegisterPlayer(p)
	}

	champion := r.RunTournament()
	r.Shutdown()

	if champion == nil {
		t.Fatal("No champion declared")
	}

	if champion.GetID() < 1 || champion.GetID() > 8 {
		t.Errorf("Invalid champion ID: %d", champion.GetID())
	}
}
