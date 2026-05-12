just gym-server

./venv/bin/python3 -m magezero.argentum.collect \
  --games 200 --offline --simulations 100 \
  --gym-url http://localhost:8081 \
  --deck-config configs/uwtempo-env.json \
  --output data/uwtempo/ver1/testing/session1.hdf5 \
  --feature-map data/features/argentum-v1.json



  ./venv/bin/python3 -m magezero.argentum.collect \
      --games 10 --offline --simulations 20 \
      --gym-url http://localhost:8081 \
      --output data/test/ver1/testing/session1.hdf5 \
      --feature-map data/features/argentum-v1.json


./venv/bin/python3 src/magezero/train.py --deck uwtempo --version 1 --epochs 50
