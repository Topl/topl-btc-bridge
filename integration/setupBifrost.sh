#!/usr/bin/fish

sudo rm -fr node01
sudo rm -fr node02
#rm -fr staking/*
#docker run --rm -i --user root  -p 9085:9085 -p 9084:9084 -p 9091:9091 -v (pwd)/config:/bifrost -v (pwd)/staking:/bifrost-staking:rw ghcr.io/topl/bifrost-node:2.0.0-beta3-24-7fd725a9 -- --cli true --config  /bifrost/config.conf < config.txt
#sudo chown -R mundacho staking/
mkdir -p node01
mkdir -p node02
chmod 777 node01
chmod 777 node02
# sed -i  -e 's/public/private/' staking/config.yaml
set TIMESTAMP (date --date="+10 seconds" +%s%N | cut -b1-13)
echo > node01/config.yaml "\
bifrost:
  big-bang:
    staker-count: 2
    local-staker-index: 0
    timestamp: $TIMESTAMP
    regtest-enabled: true
    stakes: [10000, 10000]
"
echo > node02/config.yaml "\
bifrost:
  big-bang:
    staker-count: 2
    local-staker-index: 1
    timestamp: $TIMESTAMP
    regtest-enabled: true
    stakes: [10000, 10000]
"
#cp  staking/config.yaml node01/
#cp  staking/config.yaml node02/
#cp -R staking/genesis/* node01/
#cp -R staking/genesis/* node02/
#cp -R staking/stakers/(ls staking/stakers/ | head -1) node01/stakers
#cp -R staking/stakers/(ls staking/stakers/ | tail -1) node02/stakers
set CONTAINER_ID (docker run --rm -d --name bifrost01 -p 9085:9085 -p 9084:9084 -p 9091:9091 -v (pwd)/node01:/bifrost-staking:rw ghcr.io/topl/bifrost-node:2.0.0-beta3-24-7fd725a9 --  --config  /bifrost-staking/config.yaml --regtest)
set IP_CONTAINER (docker network inspect bridge | jq  ".[0].Containers.\"$CONTAINER_ID\".IPv4Address" | sed  's:"::g' | sed -n 's:\(.*\)/.*:\1:p')
echo "IP_CONTAINER: $IP_CONTAINER"
docker run --rm -d --name bifrost02 -e BIFROST_P2P_KNOWN_PEERS=$IP_CONTAINER:9085 -p 9087:9085 -p 9086:9084 -p 9092:9091 -v (pwd)/node02:/bifrost-staking:rw ghcr.io/topl/bifrost-node:2.0.0-beta3-24-7fd725a9 --  --config  /bifrost-staking/config.yaml --regtest
