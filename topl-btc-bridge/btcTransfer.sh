#!/bin/bash

export BTC_USER=bitcoin
export BTC_PASSWORD=password
export ESCROW_ADDRESS="$1"

bitcoin-cli -regtest -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD -named createwallet wallet_name=testwallet
export ADDRESS=`bitcoin-cli -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD -rpcwallet=testwallet -regtest getnewaddress`
echo "ADDRESS: $ADDRESS"
bitcoin-cli -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD -regtest generatetoaddress 101 $ADDRESS
export UTXO=`bitcoin-cli -rpcwallet=testwallet -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD  -regtest listunspent | jq -r .[0].txid`
echo "UTXO: $UTXO"
export UTXO_INDEX=`bitcoin-cli -rpcwallet=testwallet -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD -regtest listunspent | jq -r .[0].vout`
echo "UTXO_INDEX: $UTXO_INDEX"
export TX=$(bitcoin-tx -regtest -create in=$UTXO:$UTXO_INDEX outaddr=49.99:"$ESCROW_ADDRESS")
export SIGNED_TX=$(bitcoin-cli -regtest -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD -rpcwallet=testwallet signrawtransactionwithwallet $TX | jq -r .hex)
bitcoin-cli  -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD  -regtest sendrawtransaction $SIGNED_TX 
sleep 5
bitcoin-cli -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD -regtest generatetoaddress 6 $ADDRESS
