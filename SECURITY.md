# Security policy

## Supported versions

The project is in preview. Security fixes target the latest development version and the latest published preview tag, if one exists. Older preview versions have no guaranteed backport support.

## Report a vulnerability

Use the repository's GitHub **Security → Report a vulnerability** form when private vulnerability reporting is enabled. Include the affected version, reproducible steps using synthetic data, impact and any proposed fix. Do not include credentials or real user data.

If the private reporting form is unavailable, open an Issue containing only a request for a private contact channel. Do not publish exploit details there. The maintainer should establish a private channel before receiving sensitive material. No response-time guarantee is currently offered.

Before making the repository public, the maintainer should enable GitHub private vulnerability reporting and dependency/security alerts.

## Development host

The Web demo listens on loopback and uses a fixed development identity. It is not configured as a public authenticated service. Shared deployments must authenticate requests, supply a trusted `ExecutionIdentityResolver`, and enforce authorization. See [runtime and HTTP boundaries](README.md#runtime-and-http-boundaries).
