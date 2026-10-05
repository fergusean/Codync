# Environments and deployment

Reviewed against repository configuration on 2026-09-26. Checked-in configuration does not prove that a deployment is healthy or that an external dashboard is configured.

## Configuration ownership

| Layer | Source | Selection |
| --- | --- | --- |
| Apple app | `apps/shared/Config/dev.plist`, `main.plist` | `project.yml` copies the selected file to bundled `AccountConfig.plist`; Debug uses dev, Release uses main |
| Account SDK | `apps/shared/AccountSession.swift` | Public Clerk configuration; development environment overrides are supported |
| Host | `host/src/remote/cloud.rs` | `CODYNC_CLOUD=off` or stored disable wins; then `CODYNC_CLOUD_URL`, stored URL, compiled default |
| Cloud Worker / D1 / DO | `cloud/wrangler.toml` | Root production, `dev`, or `local` environment |
| APNs Worker | `relay/wrangler.toml` | Separate deployment and secrets; see [relay README](../../relay/README.md) |

The host's compiled default is dev (`https://dev-api.codync.dev`) for every local build, debug or release; only the release workflows build with `CODYNC_ENV=main` and get `https://api.codync.dev`. Local builds of every client are dev (iOS Debug, desktop `account-config.mjs dev`, any `cargo build`), and main is built only by CI, so they never mix. Override it with the Mac's **Reach from anywhere** control or `codync-host cloud --url <url>`. `codync-host cloud` displays status; `--disable` turns access off. App and host must point at the same intended cloud.

## Checked-in readiness

- **Development:** app configuration and Worker configuration target `https://dev-api.codync.dev`; dev Clerk issuer and D1 binding are configured in source. Exercise the live path before claiming readiness.
- **Production:** `main.plist` targets `https://api.codync.dev` with the Clerk production instance (`clerk.codync.dev`, Google sign-in through the `codync-auth` Google Cloud project, published). The root Worker is deployed with D1 `codync`, `CLERK_SECRET_KEY` and `CLERK_WEBHOOK_SECRET` (Clerk webhook endpoint `https://api.codync.dev/v1/webhooks/clerk`, `user.deleted`).
- **Local:** Wrangler's `local` environment supports the isolated integration test and its test issuer. It is not a real Google sign-in test.

## Deployment procedure

Use [cloud/README.md](../../cloud/README.md) for exact Wrangler commands and binding names. Select the environment before changing anything:

1. Confirm the intended Cloudflare account, Worker, route, D1 database and Clerk issuer.
2. Create the database only if it does not exist; use its actual ID in the correct environment.
3. Apply migrations from `cloud/migrations/` to that environment.
4. Run cloud unit tests, type checking and the local integration test.
5. Deploy using `npm run deploy:dev` or `npm run deploy:main` from `cloud/` when deployment is intended.
6. Check the deployed health endpoint, then complete the device scenarios in [Cloudflare testing](cloudflare-testing.md).

Production also needs completed Apple native application registrations in Clerk and populated main app configuration. Publishable keys are public configuration; Clerk secret keys and APNs credentials do not belong in app bundles or documentation. Configure APNs using the separate relay guide.

A healthy HTTP endpoint proves Worker reachability only. It does not prove host registration, encrypted channel traffic, account approval, notification delivery or background reconnection.
