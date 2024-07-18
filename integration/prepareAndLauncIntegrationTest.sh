#!/bin/bash

# Stop all containers
docker stop $(docker ps -a -q)

# Remove previous data
rm consensusPrivateKey.pem consensusPublicKey.pem clientPrivateKey.pem clientPublicKey.pem

# Create keys

openssl ecparam -name secp256k1 -genkey -noout -out consensusPrivateKey.pem
openssl ec -in consensusPrivateKey.pem -pubout -out consensusPublicKey.pem
openssl ecparam -name secp256k1 -genkey -noout -out clientPrivateKey.pem
openssl ec -in clientPrivateKey.pem -pubout -out clientPublicKey.pem

# Start the containers
echo "Starting containers"
docker run --rm -d --add-host host.docker.internal:host-gateway -p 18444:18444 -p 18443:18443 -p 28332:28332 --name bitcoin01 toplprotocol/bitcoin-zmq:v25-regtest
docker run --rm -d --add-host host.docker.internal:host-gateway -p 18446:18444 -p 18445:18443 -p 28333:28332 --name bitcoin02 toplprotocol/bitcoin-zmq:v25-regtest


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
export TIMESTAMP=`date --date="+10 seconds" +%s%N | cut -b1-13`
echo > node01/config.yaml "\
bifrost:
  big-bang:
    staker-count: 2
    local-staker-index: 0
    timestamp: 0
    regtest-enabled: true
    stakes: [10000, 10000]
"
echo > node02/config.yaml "\
bifrost:
  big-bang:
    staker-count: 2
    local-staker-index: 1
    timestamp: 0
    regtest-enabled: true
    stakes: [10000, 10000]
"

export CONTAINER_ID=`docker run --rm -d --name bifrost01 -p 9085:9085 -p 9084:9084 -p 9091:9091 -v $(pwd)/node01:/bifrost-staking:rw toplprotocol/bifrost-tooling:v2.0.0-beta3 --  --config  /bifrost-staking/config.yaml --regtest`
export IP_CONTAINER=`docker network inspect bridge | jq  ".[0].Containers.\"$CONTAINER_ID\".IPv4Address" | sed  's:"::g' | sed -n 's:\(.*\)/.*:\1:p'`
echo "IP_CONTAINER: $IP_CONTAINER"
docker run --rm -d --name bifrost02 -e BIFROST_P2P_KNOWN_PEERS=$IP_CONTAINER:9085 -p 9087:9085 -p 9086:9084 -p 9092:9091 -v $(pwd)/node02:/bifrost-staking:rw toplprotocol/bifrost-tooling:v2.0.0-beta3 --  --config  /bifrost-staking/config.yaml --regtest

echo "Waiting for bifrost to start"
# Wait for bifrost to start
sleep 15

# Prepare the environment
echo "Preparing the environment"
shopt -s expand_aliases
alias brambl-cli="cs launch -r https://s01.oss.sonatype.org/content/repositories/releases co.topl:brambl-cli_2.13:2.0.0-beta5+2-4f603d9d-SNAPSHOT -- "
export BTC_USER=bitcoin
export BTC_PASSWORD=password
export TOPL_WALLET_DB=topl-wallet.db
export TOPL_WALLET_JSON=topl-wallet.json
export TOPL_WALLET_MNEMONIC=topl-mnemonic.txt
export TOPL_WALLET_PASSWORD=password
rm -rf $TOPL_WALLET_DB $TOPL_WALLET_JSON $TOPL_WALLET_MNEMONIC 
brambl-cli bifrost-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
rm genesisTx.pbuf genesisTxProved.pbuf groupMintingtx.pbuf groupMintingtxProved.pbuf seriesMintingTx.pbuf seriesMintingTxProved.pbuf
brambl-cli wallet init --network private --password password --newwalletdb $TOPL_WALLET_DB --mnemonicfile $TOPL_WALLET_MNEMONIC --output $TOPL_WALLET_JSON
export ADDRESS=$(brambl-cli wallet current-address --walletdb $TOPL_WALLET_DB)
brambl-cli simple-transaction create --from-fellowship nofellowship --from-template genesis --from-interaction 1 --change-fellowship nofellowship --change-template genesis --change-interaction 1  -t $ADDRESS -w $TOPL_WALLET_PASSWORD -o genesisTx.pbuf -n private -a 1000 -h  127.0.0.1 --port 9084 --keyfile $TOPL_WALLET_JSON --walletdb $TOPL_WALLET_DB --fee 10 --transfer-token lvl
brambl-cli tx prove -i genesisTx.pbuf --walletdb $TOPL_WALLET_DB --keyfile $TOPL_WALLET_JSON -w $TOPL_WALLET_PASSWORD -o genesisTxProved.pbuf
export GROUP_UTXO=$(brambl-cli tx broadcast -i genesisTxProved.pbuf -h 127.0.0.1 --port 9084)
brambl-cli bifrost-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
until brambl-cli genus-query utxo-by-address --host localhost --port 9084 --secure false --walletdb $TOPL_WALLET_DB; do sleep 5; done
echo "label: ToplBTCGroup" > groupPolicy.yaml
echo "registrationUtxo: $GROUP_UTXO#0" >> groupPolicy.yaml
brambl-cli simple-minting create --from-fellowship self --from-template default  -h 127.0.0.1 --port 9084 -n private --keyfile $TOPL_WALLET_JSON -w $TOPL_WALLET_PASSWORD -o groupMintingtx.pbuf -i groupPolicy.yaml  --mint-amount 1 --fee 10 --walletdb $TOPL_WALLET_DB --mint-token group
brambl-cli tx prove -i groupMintingtx.pbuf --walletdb $TOPL_WALLET_DB --keyfile $TOPL_WALLET_JSON -w $TOPL_WALLET_PASSWORD -o groupMintingtxProved.pbuf
export SERIES_UTXO=$(brambl-cli tx broadcast -i groupMintingtxProved.pbuf -h 127.0.0.1 --port 9084)
brambl-cli bifrost-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
echo "SERIES_UTXO: $SERIES_UTXO"
until brambl-cli genus-query utxo-by-address --host localhost --port 9084 --secure false --walletdb $TOPL_WALLET_DB; do sleep 5; done
echo "label: ToplBTCSeries" > seriesPolicy.yaml
echo "registrationUtxo: $SERIES_UTXO#0" >> seriesPolicy.yaml
echo "fungibility: group-and-series" >> seriesPolicy.yaml
echo "quantityDescriptor: liquid" >> seriesPolicy.yaml
brambl-cli simple-minting create --from-fellowship self --from-template default  -h localhost --port 9084 -n private --keyfile $TOPL_WALLET_JSON -w $TOPL_WALLET_PASSWORD -o seriesMintingTx.pbuf -i seriesPolicy.yaml  --mint-amount 1 --fee 10 --walletdb $TOPL_WALLET_DB --mint-token series
brambl-cli tx prove -i seriesMintingTx.pbuf --walletdb $TOPL_WALLET_DB --keyfile $TOPL_WALLET_JSON -w $TOPL_WALLET_PASSWORD -o seriesMintingTxProved.pbuf
export ASSET_UTXO=$(brambl-cli tx broadcast -i seriesMintingTxProved.pbuf -h 127.0.0.1 --port 9084)
brambl-cli bifrost-query mint-block --nb-blocks 1 -h 127.0.0.1  --port 9084 -s false
echo "ASSET_UTXO: $ASSET_UTXO"
until brambl-cli genus-query utxo-by-address --host localhost --port 9084 --secure false --walletdb $TOPL_WALLET_DB; do sleep 5; done
