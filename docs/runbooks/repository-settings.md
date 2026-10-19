# Repository Settings (GitHub)

- Status: Proposed. Approved by the product owner on 2026-10-18; **not yet applied**. CI (`.github/workflows/ci.yml`: api, web, contract, secret-scan) and `.github/dependabot.yml` are added by the PR that introduces them; branch protection requiring those checks is applied only after that PR is merged. These are repository settings, not files, so they must be set in GitHub by an administrator. Nothing here has been run or verified.

## Branch protection for `main`
- Require a pull request before merging.
- Require status checks to pass, once CI exists (build, tests, consistency check, secret scan).
- Block force pushes and branch deletion.
- Code-owner review is required once a second reviewer exists (see `.github/CODEOWNERS`). With one maintainer, requiring approvals would block all merges.
- Preferred: signed commits.

## Security features
- Secret scanning and push protection: on.
- Dependabot alerts and security updates: on. Add `.github/dependabot.yml` when `pom.xml` and `package.json` exist.
- Private vulnerability reporting: on.
- Actions: restrict to GitHub-owned and explicitly allowed actions; default workflow token permissions read-only; deployment uses OIDC roles (ADR-008).

## Applying
Through the repository Settings pages, or with the `gh` CLI after review. After applying, record the date and a screenshot or `gh api` output here. Until then the checks above are unverified.
