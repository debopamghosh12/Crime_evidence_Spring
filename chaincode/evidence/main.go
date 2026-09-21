package main

import (
	"log"

	"github.com/hyperledger/fabric-contract-api-go/contractapi"
)

func main() {
	chaincode, err := contractapi.NewChaincode(&EvidenceContract{})
	if err != nil {
		log.Panicf("cannot create the evidence chaincode: %v", err)
	}
	if err := chaincode.Start(); err != nil {
		log.Panicf("cannot start the evidence chaincode: %v", err)
	}
}
