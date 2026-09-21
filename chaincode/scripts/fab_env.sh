#!/bin/bash
# Source inside WSL. Points the fabric-samples `peer` CLI at the revived test network as an org ADMIN.
# Where fabric-samples (with bin/, config/, test-network/) lives inside WSL. Override with FSAMPLES=...
export FSAMPLES=${FSAMPLES:-/home/debop/crime-evidence-mgmt/fabric-samples}
export PATH=$FSAMPLES/bin:$PATH
export FABRIC_CFG_PATH=$FSAMPLES/config
export CORE_PEER_TLS_ENABLED=true
export ORGS=$FSAMPLES/test-network/organizations
export ORDERER_CA=$ORGS/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem
export ORG1_TLS=$ORGS/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt
export ORG2_TLS=$ORGS/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt
org1() { export CORE_PEER_LOCALMSPID=Org1MSP CORE_PEER_TLS_ROOTCERT_FILE=$ORG1_TLS CORE_PEER_ADDRESS=localhost:7051 CORE_PEER_MSPCONFIGPATH=$ORGS/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp; }
org2() { export CORE_PEER_LOCALMSPID=Org2MSP CORE_PEER_TLS_ROOTCERT_FILE=$ORG2_TLS CORE_PEER_ADDRESS=localhost:9051 CORE_PEER_MSPCONFIGPATH=$ORGS/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp; }
