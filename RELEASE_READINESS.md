# Release Readiness - Quarkus Observability Dev Services Elastic

This checklist tracks readiness against the Quarkiverse `ChecklistForNewProjects`.

## Current status

- Local build status: PASS (`mvn clean install`)
- Container-backed extension tests: PASS (`mvn -pl integration-tests -am verify -DskipExtensionTests=false`)
- Ready to release from source/build perspective: YES
- Ready to release from Quarkiverse onboarding/process perspective: PENDING external actions listed below

## ChecklistForNewProjects mapping

### Requesting a new repository

- [ ] Create an Extension Proposal issue in `quarkusio/quarkus`
  - Scope: external process (GitHub issue)
  - Owner: extension maintainers
- [ ] Ensure repository settings are managed in Quarkiverse DevOps `.tf`
  - Scope: external process (DevOps PR)
  - Owner: Quarkiverse maintainers

### Start coding

- [x] Extension skeleton structure present (`runtime`, `deployment`, `integration-tests`, `docs`)
- [x] Maven-based multi-module extension layout present
- [ ] IDE formatter setup verified against Quarkus conventions for all contributors
  - Scope: team/local IDE setup

### After the repository is created

- [ ] Register extension in `quarkus-extension-catalog` (`extensions/*.yaml`)
  - Scope: external process (catalog PR)
- [ ] Optional company maintainer opt-in in catalog
  - Scope: external process (catalog PR)
- [ ] Register in Quarkus Ecosystem CI (`quarkus-ecosystem-ci` issue + `info.yaml`)
  - Scope: external process (issue + PR)
- [ ] Register docs in `quarkiverse-docs` Antora playbook
  - Scope: external process (docs PR)

### When your extension is ready

- [x] Core build and packaging pass locally
- [x] Integration tests execute in CI-like verify run
- [ ] Maven Central release execution (tag/release workflow)
  - Scope: external process (GitHub release and credentials)

## Local repository gaps fixed

- [x] Added top-level `LICENSE` file (Apache-2.0) required for release hygiene.

## Suggested next external actions

1. Open `quarkusio/quarkus` extension proposal issue (if not already done).
2. Open PR in Quarkiverse DevOps repository for any permissions/settings changes in `.tf`.
3. Open PR in `quarkus-extension-catalog` to register this extension.
4. Open issue + PR in `quarkus-ecosystem-ci` for snapshot compatibility checks.
5. Open PR in `quarkiverse-docs` to include this docs component in the global playbook.
6. Perform first release through Quarkiverse release process once all above are merged.


