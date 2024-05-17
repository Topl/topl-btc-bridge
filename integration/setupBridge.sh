#!/bin/bash

shopt -s expand_aliases
alias brambl-cli="cs launch -r https://s01.oss.sonatype.org/content/repositories/releases co.topl:brambl-cli_2.13:2.0.0-beta2 -- "
export BTC_USER=bitcoin
export BTC_PASSWORD=password
export TOPL_WALLET_DB=topl-wallet.db
export TOPL_WALLET_JSON=topl-wallet.json
export TOPL_WALLET_MNEMONIC=topl-mnemonic.txt
export TOPL_WALLET_PASSWORD=password
rm -rf $TOPL_WALLET_DB $TOPL_WALLET_JSON $TOPL_WALLET_MNEMONIC 
rm genesisTx.pbuf genesisTxProved.pbuf groupMintingtx.pbuf groupMintingtxProved.pbuf seriesMintingTx.pbuf seriesMintingTxProved.pbuf
brambl-cli wallet init --network private --password password --newwalletdb $TOPL_WALLET_DB --mnemonicfile $TOPL_WALLET_MNEMONIC --output $TOPL_WALLET_JSON
export ADDRESS=$(brambl-cli wallet current-address --walletdb $TOPL_WALLET_DB)
brambl-cli simple-transaction create --from-fellowship nofellowship --from-template genesis --from-interaction 1 --change-fellowship nofellowship --change-template genesis --change-interaction 1  -t $ADDRESS -w $TOPL_WALLET_PASSWORD -o genesisTx.pbuf -n private -a 1000 -h  127.0.0.1 --port 9084 --keyfile $TOPL_WALLET_JSON --walletdb $TOPL_WALLET_DB --fee 10 --transfer-token lvl
brambl-cli tx prove -i genesisTx.pbuf --walletdb $TOPL_WALLET_DB --keyfile $TOPL_WALLET_JSON -w $TOPL_WALLET_PASSWORD -o genesisTxProved.pbuf
export GROUP_UTXO=$(brambl-cli tx broadcast -i genesisTxProved.pbuf -h 127.0.0.1 --port 9084)
bitcoin-cli -regtest -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD -named createwallet wallet_name=testwallet
export ADDRESS=`bitcoin-cli -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD -rpcwallet=testwallet -regtest getnewaddress`
echo "ADDRESS: $ADDRESS"
bitcoin-cli -rpcuser=$BTC_USER -rpcpassword=$BTC_PASSWORD -regtest generatetoaddress 101 $ADDRESS