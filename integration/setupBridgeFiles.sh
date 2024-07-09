#!/bin/bash

openssl ecparam -name secp256k1 -genkey -noout -out consensusPrivateKey.pem
openssl ec -in consensusPrivateKey.pem -pubout -out consensusPublicKey.pem
openssl ecparam -name secp256k1 -genkey -noout -out clientPrivateKey.pem
openssl ec -in clientPrivateKey.pem -pubout -out clientPublicKey.pem