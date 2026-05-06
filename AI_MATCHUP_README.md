# AI Matchup Runner

Run automated AI vs AI games between defined decks with comprehensive statistics and replay saving.

## Features

- **100+ games**: Run any number of games between two defined decks
- **Fair matchups**: Automatically swaps starting player every other game
- **Statistics tracking**: Win rates, average turns, game duration, completion rates
- **Replay saving**: Optional detailed game logs for every game
- **CSV output**: Machine-readable results for further analysis
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
├── game-1.log               # First game (if --save-replays)
├── game-2.log               # Second game (if --save-replays)
├── game-3.log               # Third game (if --save-replays)
└── ...
```

### CSV Format

```csv
game,first_player,turns,actions,duration_ms,winner,p1_life,p2_life,completed,draw_reason
1,P1,15,42,1250,P1,20,0,true,
2,P2,18,51,1450,P2,0,20,true,
3,P1,50,134,3200,draw,5,5,false,maxTurns(50)
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

## Integration with Existing Tools

This script uses the same infrastructure as:
- `AIBenchmark.kt` - the production AI benchmark system
- `GameSimulator.kt` - the game simulation engine
- `AiGameManager.kt` - the AI player management system

Results are compatible with spreadsheet analysis tools and can be imported into data analysis pipelines.
