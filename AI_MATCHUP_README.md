# AI Matchup Runner

Run automated AI vs AI games between defined decks with comprehensive statistics and replay saving.

## Features

- **100+ games**: Run any number of games between two defined decks
- **Fair matchups**: Automatically swaps starting player every other game
- **Statistics tracking**: Win rates, average turns, game duration, completion rates
- **Replay saving**: Optional detailed game logs for every game
- **CSV output**: Machine-readable results for further analysis
- **Card importance ranking**: Sort each deck by cards most correlated with wins
- **Progress tracking**: Real-time progress updates during long runs

## Quick Start

```bash
# Build the project first
./gradlew :game-server:build

# Run 100 games between predefined decks with replays
./gradlew :game-server:runAiMatchup -Pargs="--deck1=red_creatures --deck2=white_creatures --games=100 --save-replays"

# Run games using text decklist files (like wauras.txt)
./gradlew :game-server:runAiMatchup -Pargs="--deck1-file=wauras.txt --deck2=red_creatures --games=50"

# Mix text file and predefined deck
./gradlew :game-server:runAiMatchup -Pargs="--deck1-file=my-deck.txt --deck2=white_creatures --games=100 --max-turns=75 --save-replays"
```

## Deck Options

### Predefined Decks (from test-decks.json)
- **red_creatures**: Emberheart Challenger, Flamecache Gecko - aggressive red creatures
- **white_creatures**: Brightblade Stoat, Brambleguard Captain - white creature swarm  
- **blue_creatures**: Daring Waverider, Finneas, Ace Archer - blue flying/control

### Text Decklist Files
Use simple text format like `wauras.txt`:
```
# Comments start with #
20 Plains
4 Ethereal Armor
4 Feather of Flight
4 Shardmage's Rescue
```

**Format**: Each line contains `count card_name`. Blank lines and comments are ignored.

## Command Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `--deck1=<name>` | First deck name (from test-decks.json) | Required* |
| `--deck2=<name>` | Second deck name (from test-decks.json) | Required* |
| `--deck1-file=<path>` | First deck from text file | Required* |
| `--deck2-file=<path>` | Second deck from text file | Required* |
| `--games=<number>` | Number of games to run | `100` |
| `--max-turns=<number>` | Maximum turns before draw | `50` |
| `--save-replays` | Save detailed game logs | `false` |

*Must specify either `--deck1`/`--deck2` OR `--deck1-file`/`--deck2-file` for each player

## Output

The script creates a timestamped directory with:

```
ai-matchup-red_creatures-vs-white_creatures-20240506_123456/
├── results.csv              # Game-by-game results
├── card-importance.csv      # Per-card win-rate lift ranking
├── matchup-summary.txt      # Human-readable summary with card importance
├── game-1.log               # First game (if --save-replays)
├── game-2.log               # Second game (if --save-replays)
├── game-3.log               # Third game (if --save-replays)
└── ...
```

### Card Importance

After a single matchup, the runner prints and saves a card ranking for both decks.
Cards at the top had the highest positive score; cards at the bottom had the lowest score.

The score is the deck's win-rate lift in games where the card was seen, weighted by how often it was seen:

```
score = (win rate when seen - deck baseline win rate) * seen games / total games
```

"Seen" includes opening hand, drawn cards, cast spells, played lands, discarded cards, and cards that moved to public zones. This is correlation, not proof that the card caused the wins, so use larger game counts for less noisy rankings.

### CSV Format

```csv
game,first_player,turns,actions,duration_ms,winner,p1_life,p2_life,completed,draw_reason
1,P1,15,42,1250,P1,20,0,true,
2,P2,18,51,1450,P2,0,20,true,
3,P1,50,134,3200,draw,5,5,false,maxTurns(50)
```

### Card Importance CSV Format

```csv
deck,rank,card,copies,score,seen_games,wins_when_seen,losses_when_seen,draws_when_seen,seen_win_rate,baseline_win_rate
"red_aggro",1,"Emberheart Challenger",4,8.2500,42,28,14,0,66.67,58.00
```

### Console Output

```
=== AI MATCHUP RUNNER ===
Deck 1: red_aggro
Deck 2: white_weenie
Games: 100
Save replays: true
Max turns: 50

Output directory: /path/to/ai-matchup-red_aggro-vs-white_weenie-20240506_123456

[10/100] P1: 6 | P2: 4 | Draws: 0
[20/100] P1: 13 | P2: 7 | Draws: 0
[30/100] P1: 18 | P2: 11 | Draws: 1
...
[100/100] P1: 58 | P2: 39 | Draws: 3

=== FINAL RESULTS ===
Matchup: red_aggro vs white_weenie
Total games: 200
Completed: 197 / 200 (98%)

red_aggro wins: 58 (29.0%)
white_weenie wins: 39 (19.5%)
Draws: 3 (1.5%)

Average turns: 23.4
Average actions: 67
Average duration: 1845ms
Wall time: 368500ms

Results saved to: /path/to/ai-matchup-red_aggro-vs-white_weenie-20240506_123456
```

## Adding Custom Decks

### Method 1: Text Decklist File (Recommended)
Create a simple text file like `my-deck.txt`:
```
# My Custom Deck
20 Mountain
4 Emberheart Challenger
4 Flamecache Gecko
4 Frilled Sparkshooter
4 Blooming Blast
```

Run with: `./gradlew :game-server:runAiMatchup -Pargs="--deck1-file=my-deck.txt --deck2=red_creatures"`

### Method 2: JSON Format
1. Edit `test-decks.json`
2. Add your deck in this format:

```json
{
  "my_custom_deck": {
    "name": "My Custom Deck",
    "cards": {
      "Emberheart Challenger": 4,
      "Flamecache Gecko": 4,
      "Mountain": 20
    }
  }
}
```

3. Run with: `./gradlew :game-server:runAiMatchup -Pargs="--deck1=my_custom_deck --deck2=red_creatures"`

## Technical Details

- **AI Engine**: Uses the same EngineAiPlayerController as production games
- **Fair Play**: Alternates starting player every game to eliminate first-player advantage
- **Error Handling**: Automatic fallback to engine AI if actions fail
- **Performance**: ~1-2 seconds per game, ~5-10 minutes for 100 games
- **Memory**: Efficient streaming, no memory leaks even for 1000+ games

## Troubleshooting

### Build Issues
```bash
# Ensure the project is built
./gradlew clean build
```

### Missing Cards
If you get "card not found" errors, ensure:
1. Cards exist in the Bloomburrow set definition
2. Card names match exactly (case-sensitive)
3. Basic lands are included in the deck

### Performance Issues
- Reduce `--games` for testing
- Increase `--max-turns` if games are drawing too early
- Use `--save-replays` only when needed (slows down execution)

### Large Output
For 1000+ games, consider:
- Running without `--save-replays`
- Redirecting output: `./gradlew :game-server:runAiMatchup -Pargs="..." > results.txt`
- Using `--max-turns=30` for faster games

## Examples

### Test Run (10 games, fast)
```bash
./gradlew :game-server:runAiMatchup -Pargs="--deck1=red_creatures --deck2=white_creatures --games=10 --max-turns=30"
```

### Text Decklist vs Predefined (100 games, full replays)
```bash
./gradlew :game-server:runAiMatchup -Pargs="--deck1-file=wauras.txt --deck2=red_creatures --games=100 --save-replays"
```

### Two Text Decklists (50 games)
```bash
./gradlew :game-server:runAiMatchup -Pargs="--deck1-file=deck1.txt --deck2-file=deck2.txt --games=50"
```

### Tournament Simulation (1000 games, no replays)
```bash
./gradlew :game-server:runAiMatchup -Pargs="--deck1=white_creatures --deck2=blue_creatures --games=1000"
```

## Tournament Mode

Run comprehensive tournaments with multiple decks playing against each other in round-robin format.
Tournament output also includes `tournament-card-importance.csv`, which ranks cards for each deck across all of that deck's tournament games using the same win-rate lift score as single-matchup mode.

### Quick Start

```bash
# Build the project first
./gradlew :game-server:build

# Run tournament with default deck pool
./gradlew :game-server:runAiMatchup -Pargs="--tournament --games=50"

# Custom deck pool with more games
./gradlew :game-server:runAiMatchup -Pargs="--tournament --deck-pool=bolav/tournament-decks.json --games=100"
./gradlew :game-server:runAiMatchup -Pargs="--tournament --deck-pool=tournament-decks.json --games=100 --save-replays"
```

### Tournament Features

- **Round-robin format**: Every deck plays against every other deck
- **Comprehensive statistics**: Win rates, play/draw performance, matchup breakdowns
- **Fair play**: Alternates starting player in each game
- **Detailed rankings**: Automatic deck ranking by overall performance
- **CSV export**: Tournament results and matchup matrix for analysis
- **Multi-deck support**: Mix JSON decks and text decklist files

### Deck Pool Configuration

Create a `tournament-decks.json` file:

```json
{
  "jsonDecks": ["red_creatures", "white_creatures", "blue_creatures"],
  "textFiles": ["wauras.txt", "my-custom-deck.txt"]
}
```

Or use simple format (one deck per line):
```
# tournament-decks.txt
red_creatures
white_creatures
blue_creatures
wauras.txt
my-custom-deck.txt
```

### Tournament Command Line Options

| Option | Description | Default |
|--------|-------------|---------|
| `--tournament` | Enable tournament mode | `false` |
| `--deck-pool=<file>` | Deck pool configuration file | `tournament-decks.json` |
| `--games=<number>` | Games per matchup | `50` |
| `--max-turns=<number>` | Maximum turns before draw | `50` |
| `--save-replays` | Save detailed game logs | `false` |

### Tournament Output

Creates a timestamped directory with comprehensive results:

```
tournament-20240506_123456/
├── tournament-results.csv      # Overall deck performance
├── matchup-matrix.csv          # Head-to-head results
├── red_creatures-vs-white_creatures/
│   ├── game-1.log              # Individual game logs
│   ├── game-2.log
│   └── ...
└── white_creatures-vs-blue_creatures/
    ├── game-1.log
    └── ...
```

### Tournament Results Format

**Console Output:**
```
=== TOURNAMENT RESULTS ===
Total games: 300
Total matchups: 6
Wall time: 15m 42s

=== DECK RANKINGS ===
Rank | Deck            | Win Rate | Total W-L-D | Play Rate | Draw Rate
-----|-----------------|----------|-------------|-----------|-----------
  1 | white_creatures |   68.0%  |   136-64-0  |   70.0%   |   66.0%
  2 | red_creatures   |   58.0%  |   116-84-0  |   62.0%   |   54.0%
  3 | blue_creatures  |   42.0%  |    84-116-0 |   44.0%   |   40.0%

=== DETAILED MATCHUP RESULTS ===
white_creatures:
  Overall: 136-64-0 (68.0%)
  On play: 70-30 (70.0%)
  On draw: 66-34 (66.0%)
    vs red_creatures: 45-15 (0 draws) | Play: 23-7 | Draw: 22-8
    vs blue_creatures: 51-19 (0 draws) | Play: 27-3 | Draw: 24-16
```

**CSV Files:**

`tournament-results.csv`:
```csv
deck,total_wins,total_losses,total_draws,win_rate,play_wins,draw_wins,play_win_rate,draw_win_rate
white_creatures,136,64,0,68.00,70,66,70.00,66.00
red_creatures,116,84,0,58.00,62,54,62.00,54.00
blue_creatures,84,116,0,42.00,44,40,44.00,40.00
```

`matchup-matrix.csv`:
```csv
deck,white_creatures,red_creatures,blue_creatures
white_creatures,-,45-15,51-19
red_creatures,15-45,-,41-29
blue_creatures,19-51,29-41,-
```

### Tournament Examples

**Small Tournament (3 decks, 20 games each):**
```bash
./gradlew :game-server:runAiMatchup -Pargs="--tournament --games=20"
```

**Large Tournament (5+ decks, 100 games each, full replays):**
```bash
./gradlew :game-server:runAiMatchup -Pargs="--tournament --games=100 --save-replays --deck-pool=large-tournament.json"
```

**Custom Deck Pool Tournament:**
```bash
# Create custom-tournament.json
{
  "jsonDecks": ["red_creatures", "white_creatures"],
  "textFiles": ["wauras.txt", "my-deck.txt"]
}

# Run tournament
./gradlew :game-server:runAiMatchup -Pargs="--tournament --deck-pool=custom-tournament.json --games=75"
```

### Tournament Statistics

The tournament system tracks:

- **Overall Performance**: Total wins, losses, draws, and win rate
- **Play/Draw Analysis**: Performance when going first vs second
- **Head-to-Head Matchups**: Detailed results for each deck pairing
- **First Player Advantage**: Quantification of going first benefit
- **Completion Rates**: Game completion and draw statistics

### Performance Considerations

- **Tournament size**: N decks play N×(N-1)/2 matchups
- **Time estimation**: ~2-3 seconds per game
- **Memory usage**: Efficient streaming, suitable for 1000+ games
- **Storage**: CSV files are small; enable `--save-replays` selectively

## Integration with Existing Tools

This script uses the same infrastructure as:
- `AIBenchmark.kt` - the production AI benchmark system
- `GameSimulator.kt` - the game simulation engine
- `AiGameManager.kt` - the AI player management system

Results are compatible with spreadsheet analysis tools and can be imported into data analysis pipelines.
