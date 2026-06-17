# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in OrzMCBackup, please report it privately:

- **Open a GitHub Security Advisory**: [Create a draft advisory](https://github.com/OrzMC/OrzMCBackup/security/advisories/new)
- **Or send an email**: 824219521@qq.com

Please **do not** report security vulnerabilities through public GitHub issues.

We will acknowledge receipt within 48 hours and provide an estimated timeline for a fix.

## Supported Versions

| Version | Supported |
|---------|-----------|
| ≥ 0.1.0 | ✅ |

## Dependency Security

- All dependencies are pinned to specific versions (via Gradle version catalog)
- Dependabot runs weekly scans for both Gradle and GitHub Actions dependencies
- Release artifacts are signed with GPG (Maven Central) and SHA-256 checksums (GitHub Releases)
