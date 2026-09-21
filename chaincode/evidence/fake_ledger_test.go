package main

import (
	"sort"
	"strings"
	"time"

	"github.com/hyperledger/fabric-chaincode-go/pkg/cid"
	"github.com/hyperledger/fabric-chaincode-go/shim"
	"github.com/hyperledger/fabric-contract-api-go/contractapi"
	"github.com/hyperledger/fabric-protos-go/ledger/queryresult"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// A fake ledger for unit tests. There is no mock stub in this Fabric version, so this models the parts
// of the peer the chaincode relies on:
//   - a transaction's writes are applied only if the function returns nil (Fabric discards the writes of a
//     failed transaction), which lets tests prove a rejected call changes nothing;
//   - composite keys and partial-key range scans;
//   - per-key history with the transaction id and timestamp;
//   - a counter of DelState calls, which must stay zero (constraint C-02).
// Methods of shim.ChaincodeStubInterface that are not implemented here panic if the chaincode ever calls them.

type histItem struct {
	txID  string
	ts    time.Time
	value []byte
}

type emittedEvent struct {
	name    string
	payload []byte
}

type fakeLedger struct {
	state              map[string][]byte
	history            map[string][]histItem
	txCount            int
	clock              time.Time
	delCalls           int
	events             []emittedEvent
	historyNewestFirst bool // simulate a peer that iterates history newest-first
}

func newFakeLedger() *fakeLedger {
	return &fakeLedger{
		state: map[string][]byte{}, history: map[string][]histItem{},
		clock: time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC),
	}
}

// tx is one in-flight transaction.
type tx struct {
	shim.ChaincodeStubInterface
	l       *fakeLedger
	id      string
	ts      time.Time
	pending map[string][]byte
	event   *emittedEvent
}

func (t *tx) GetState(key string) ([]byte, error) {
	if v, ok := t.pending[key]; ok {
		return v, nil
	}
	return t.l.state[key], nil
}
func (t *tx) PutState(key string, value []byte) error { t.pending[key] = value; return nil }
func (t *tx) DelState(key string) error               { t.l.delCalls++; return nil }
func (t *tx) GetTxID() string                         { return t.id }
func (t *tx) GetTxTimestamp() (*timestamppb.Timestamp, error) {
	return timestamppb.New(t.ts), nil
}
func (t *tx) SetEvent(name string, payload []byte) error {
	t.event = &emittedEvent{name, payload}
	return nil
}

func (t *tx) CreateCompositeKey(objectType string, attrs []string) (string, error) {
	return "\x00" + objectType + "\x00" + strings.Join(append(append([]string{}, attrs...), ""), "\x00"), nil
}

func (t *tx) SplitCompositeKey(key string) (string, []string, error) {
	parts := strings.Split(strings.TrimPrefix(key, "\x00"), "\x00")
	return parts[0], parts[1 : len(parts)-1], nil
}

type kvIter struct {
	items []*queryresult.KV
	pos   int
}

func (i *kvIter) HasNext() bool { return i.pos < len(i.items) }
func (i *kvIter) Close() error  { return nil }
func (i *kvIter) Next() (*queryresult.KV, error) {
	i.pos++
	return i.items[i.pos-1], nil
}

func (t *tx) GetStateByPartialCompositeKey(objectType string, attrs []string) (shim.StateQueryIteratorInterface, error) {
	prefix, _ := t.CreateCompositeKey(objectType, attrs)
	merged := map[string][]byte{}
	for k, v := range t.l.state {
		merged[k] = v
	}
	for k, v := range t.pending {
		merged[k] = v
	}
	var keys []string
	for k := range merged {
		if strings.HasPrefix(k, prefix) {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	it := &kvIter{}
	for _, k := range keys {
		it.items = append(it.items, &queryresult.KV{Key: k, Value: merged[k]})
	}
	return it, nil
}

type histIter struct {
	items []*queryresult.KeyModification
	pos   int
}

func (i *histIter) HasNext() bool { return i.pos < len(i.items) }
func (i *histIter) Close() error  { return nil }
func (i *histIter) Next() (*queryresult.KeyModification, error) {
	i.pos++
	return i.items[i.pos-1], nil
}

func (t *tx) GetHistoryForKey(key string) (shim.HistoryQueryIteratorInterface, error) {
	items := append([]histItem{}, t.l.history[key]...)
	if t.l.historyNewestFirst {
		for i, j := 0, len(items)-1; i < j; i, j = i+1, j-1 {
			items[i], items[j] = items[j], items[i]
		}
	}
	it := &histIter{}
	for _, h := range items {
		it.items = append(it.items, &queryresult.KeyModification{
			TxId: h.txID, Value: h.value, Timestamp: timestamppb.New(h.ts),
		})
	}
	return it, nil
}

// fakeCtx satisfies contractapi.TransactionContextInterface.
type fakeCtx struct {
	stub *tx
	msp  string
}

func (c *fakeCtx) GetStub() shim.ChaincodeStubInterface  { return c.stub }
func (c *fakeCtx) GetClientIdentity() cid.ClientIdentity { return fakeIdentity{msp: c.msp} }

type fakeIdentity struct {
	cid.ClientIdentity
	msp string
}

func (f fakeIdentity) GetMSPID() (string, error) { return f.msp, nil }

// invoke runs one transaction. Each gets a new id and a timestamp one second later, like a real ledger.
func (l *fakeLedger) invoke(msp string, fn func(ctx contractapi.TransactionContextInterface) error) error {
	l.txCount++
	l.clock = l.clock.Add(time.Second)
	t := &tx{l: l, id: txIDFor(l.txCount), ts: l.clock, pending: map[string][]byte{}}
	if err := fn(&fakeCtx{stub: t, msp: msp}); err != nil {
		return err // writes are discarded, exactly as Fabric does
	}
	for k, v := range t.pending {
		l.state[k] = v
		l.history[k] = append(l.history[k], histItem{txID: t.id, ts: t.ts, value: v})
	}
	if t.event != nil {
		l.events = append(l.events, *t.event)
	}
	return nil
}

func txIDFor(n int) string {
	return strings.Repeat("0", 60) + strings.Repeat("0", 4-len(itoa(n))) + itoa(n)
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	s := ""
	for ; n > 0; n /= 10 {
		s = string(rune('0'+n%10)) + s
	}
	return s
}
