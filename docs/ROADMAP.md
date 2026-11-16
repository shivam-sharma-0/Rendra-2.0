# Roadmap

Rendra already works end to end locally. This page lists what comes next, roughly in order.

## Now

- **Blueprint engine as the default.** The blueprint engine passes all four benchmark prompts. The next steps are to compare it against the older pipeline, make it the default and remove the old path.
- **Better design range.** Bundled per-preset font pairings, richer themes with texture and depth, and a stronger visual review step.
- **Real-use fixes.** Issues found by building real apps, such as long navigation bars on mobile and denser dashboards.

## Next

- **API-key model access for hosting.** A model registry by role, with platform keys, bring-your-own-key for users, per-key spend limits and fallbacks.
- **One-click publishing.** Publish to a permanent URL, with custom domains.
- **Per-project network isolation.** Separate networks per generated app, so that one app's server cannot reach another's.
- **Iterating on an existing app.** Better follow-up edits on a saved app, with targeted retrieval of the relevant files for large projects.
- **Account polish.** Profile editing, notification preferences, and session revocation everywhere.

## Later

- **More stacks.** A language-pack system for stacks beyond React with Node and MongoDB.
- **Stronger sandboxing.** A lighter, stricter process sandbox inside containers.
- **GitHub export and sync.** Push a generated app to the user's own repository.
- **Teams and billing.** Plans, usage-based limits and team workspaces at scale.
- **Hosted service.** Deploy Rendra itself with monitoring, backups and horizontal scaling of workers.
