# Decisions

<!-- Append-only. One paragraph max per entry: chose / rejected / why. -->

## D-001 — Build tool: Maven (2026-09-21)
Chose Maven with the wrapper (`mvnw`). Rejected Gradle. Nothing in the feature list requires either, and this was left as my call. Maven's declarative `pom.xml` plus the Spring Boot parent/BOM keeps dependency versions in one obvious place, which matters because every new dependency has to be reviewed and logged here; it is also what most Spring Boot documentation assumes, so examiners can read it without learning a DSL. Gradle would build faster and the Fabric sample Java apps use it, but neither outweighs readability for a final-year project. Neither tool is on PATH on this machine, so the wrapper is the bootstrap path either way.

## D-002 — Package structure: by layer, not by feature (2026-09-21)
Chose package-by-layer (`controller/service/repository/ledger/storage/security`), mirroring the layout in FEATURE_LIST.md. Rejected package-by-feature (`evidence/`, `custody/`, `auth/` each holding their own controller, service, and repository). By-feature keeps related code together and scales better in large codebases, but by-layer makes the two rules that matter most to this project mechanically obvious: only `ledger/` may know Fabric and only `storage/` may know IPFS. It also matches how the feature list names its classes, so IDs map straight to files.

## D-003 — Spring Boot 4.1.1 on Java 21 (2026-09-21)
Chose Spring Boot 4.1.1 (the current GA and Initializr default) with Java 21 (the installed JDK, LTS). Rejected 3.5.x, the original plan: start.spring.io no longer offers it, and hand-editing an older parent into the pom would start the project on a line that is at or past its open-source support window. Rejected 4.0.8: still offered, but its support window likely ends before the project does, and a Boot minor upgrade mid-project costs more than starting on the newer minor. Rejected Java 17 (Initializr's default) since JDK 21 is installed and nothing here needs 17. Known cost: Boot 4 renamed/split starters and moved Actuator classes, and defaults to Jackson 3, which must be reconciled with jjwt's Jackson 2 dependency (verified when A1 is built).
