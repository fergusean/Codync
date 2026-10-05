# Environments and deployment

Reviewed against repository configuration on 2026-09-26. Checked-in configuration does not prove that a deployment is healthy or that an external dashboard is configured.

## Configuration ownership

| Layer | Source | Selection |
| --- | --- | --- |
| Apple app | `apps/shared/Config/dev.plist`, `main.plist` | `project.yml` copies the selected file to bundled `AccountConfig.plist`; Debug uses dev, Release uses main |
| Account SDK | `apps/shared/AccountSession.swift` | Public Clerk configuration; development environment overrides are supported |
| Host | `host/src/remote/cloud.rs` | `CODYNC_CLOUD=off` or stored disable wins; then `CODYNC_CLOUD_URL`, then the build's cloud (`CODYNC_ENV` at compile time) |
| Cloud Worker / D1 / DO | `cloud/wrangler.toml` | Root production, `dev`, or `local` environment |
| APNs Worker | `relay/wrangler.toml` | Separate deployment and secrets; see [relay README](../../relay/README.md) |

The environment is chosen when building, never stored: the host compiles in its cloud from `CODYNC_ENV` (`main` → `https://api.codync.dev`, unset or anything else → `https://dev-api.codync.dev`, debug or release alike), and the only cloud setting a host saves is on/off (**Reach from anywhere**, `codync-host cloud --enable|--disable`). `CODYNC_CLOUD_URL` points a host at a cloud you're developing (https, or http to this computer). Build the host with the same environment as the app next to it; the release workflows set `CODYNC_ENV=main`.

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
