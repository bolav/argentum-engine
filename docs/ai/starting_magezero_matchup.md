  1. Build
  just build

  2. Start the MageZero inference server (terminal 1)
  cd MageZero
  ./venv/bin/python3 src/magezero/server.py \
    --deck uwtempo --version 1 --port 50052

  3. Start the agent service (terminal 2)
  cd MageZero
  ./venv/bin/python3 -m magezero.argentum.agent_service \
    --port 5005 \
    --server-url http://127.0.0.1:50052 \
    --feature-map data/features/argentum-v1.json

  4. Start the game-server with MageZero mode (terminal 3)
  GAME_AI_MODE=magezero just server

  5. Start the web client (terminal 4)
  just client

  6. Play

  Open http://localhost:5173, start a Quick Game vs AI, pick the UWTempo deck. The AI opponent will use
  MageZero.