#!/bin/bash
# Usage: VER=1.1 SEQ=2 bash chaincode/scripts/deploy_cc.sh   (a new version needs a new, higher SEQ)
# Deploys chaincode "evidence" v1.0 (sequence 1) to crimechannel on the revived test network. Run inside WSL.
set -e
SPW="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # this scripts directory (inside WSL)
. "$SPW/fab_env.sh"
CH=crimechannel; NAME=evidence; VER=${VER:-1.0}; SEQ=${SEQ:-1}
SRC="$SPW/../evidence"
WORK=/tmp/cc-evidence

echo "### copy source to a WSL-local dir (no spaces in the path) and vendor dependencies"
rm -rf "$WORK" /tmp/evidence.tar.gz; mkdir -p "$WORK"
cp "$SRC"/*.go "$SRC"/go.mod "$SRC"/go.sum "$WORK"/
rm -f "$WORK"/*_test.go
(cd "$WORK" && go mod vendor 2>&1 | tail -2 && ls | head -20)

echo "### package"
peer lifecycle chaincode package /tmp/evidence.tar.gz --path "$WORK" --lang golang --label ${NAME}_${VER}
PKG=$(peer lifecycle chaincode calculatepackageid /tmp/evidence.tar.gz)
echo "package id: $PKG"

echo "### install on Org1 (the peer builds the chaincode image; this takes a while)"
org1; peer lifecycle chaincode install /tmp/evidence.tar.gz 2>&1 | tail -3
echo "### install on Org2"
org2; peer lifecycle chaincode install /tmp/evidence.tar.gz 2>&1 | tail -3

ORD="-o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile $ORDERER_CA"
echo "### approve for Org1"
org1; peer lifecycle chaincode approveformyorg $ORD --channelID $CH --name $NAME --version $VER --package-id "$PKG" --sequence $SEQ 2>&1 | tail -2
echo "### approve for Org2"
org2; peer lifecycle chaincode approveformyorg $ORD --channelID $CH --name $NAME --version $VER --package-id "$PKG" --sequence $SEQ 2>&1 | tail -2

echo "### commit readiness"
peer lifecycle chaincode checkcommitreadiness --channelID $CH --name $NAME --version $VER --sequence $SEQ --output json 2>&1 | tail -6

echo "### commit"
peer lifecycle chaincode commit $ORD --channelID $CH --name $NAME --version $VER --sequence $SEQ \
  --peerAddresses localhost:7051 --tlsRootCertFiles $ORG1_TLS --peerAddresses localhost:9051 --tlsRootCertFiles $ORG2_TLS 2>&1 | tail -3

echo "### committed definitions"
peer lifecycle chaincode querycommitted --channelID $CH 2>&1 | tail -4
