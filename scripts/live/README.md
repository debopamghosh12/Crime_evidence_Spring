# Live verification scripts

Run against a running backend (`http://localhost:8080`) with real PostgreSQL and an **offline** Kubo node
(constraint C-09). They write no secrets: credentials and Fabric identity paths live in an `env.sh` in a scratch
directory OUTSIDE the repository, named by `WORKDIR`.

| Script | What it does |
|---|---|
| `live_phase2.sh` | The full Phase 2 checklist: register (forged collector ignored), retrieve, verify, **on-disk block corruption -> TAMPERED**, deleted block -> NOT_FOUND, versions, history, disposal, roles, upload limits, IPFS outage. Works with either ledger. |
| `live_fabric_extra.sh` | Fabric-only checks: API tx ids exist on the peers' ledger (qscc), concurrent-update race, peer outage and self-recovery. Needs `FAB_SCRIPTS_WSL`. |

`env.sh` must export: `DB_PASSWORD`, `DB_URL`, `BLOCKEVIDENCE_JWT_SECRET`, `BLOCKEVIDENCE_DEV_SEED_PASSWORD`,
`SPRING_PROFILES_ACTIVE`, and for Fabric `FABRIC_TLS_CERT_PATH`, `FABRIC_CERT_PATH`, `FABRIC_KEY_PATH`.
See `docs/FABRIC_RUNBOOK.md`. Both scripts export `MSYS_NO_PATHCONV=1` where they call Docker/WSL, because Git Bash
otherwise rewrites `/data/...` arguments.
