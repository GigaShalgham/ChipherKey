<!-- Thank you for contributing to Turnypt. -->

## Summary

<!-- What does this PR change, in one or two sentences? -->

## Security checklist

- [ ] This change does not weaken any security property documented in `docs/THREAT_MODEL.md`
- [ ] No cryptographic implementation was added to `:keyboard`
- [ ] No new permissions, network code, or background clipboard access
- [ ] Wire-format changes keep the golden-vector test passing (or bump CK1 with migration notes in `docs/PROTOCOL.md`)

## Testing

<!-- Paste real evidence: test task summary line and/or class counts from
     JUnit XML. Do not type numbers from memory. -->

- [ ] `./gradlew test` passes locally — evidence:
- [ ] New/changed behavior is covered by tests (or N/A with justification)

## Documentation

- [ ] README / docs / CHANGELOG updated where applicable
- [ ] Release notes implications considered (none / described)
