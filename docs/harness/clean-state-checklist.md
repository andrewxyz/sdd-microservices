# Clean State Checklist

Walk this before ending a session (see `CLAUDE.md`).

- [ ] The standard startup path still works (`scripts/up.sh` in WSL, or `npm run dev`).
- [ ] The standard verification path still runs (`./init.sh`).
- [ ] Current progress is recorded in `claude-progress.md`.
- [ ] `feature_list.json` reflects what is actually passing versus unverified.
- [ ] No half-finished step is left undocumented.
- [ ] No secrets, `.env` content or audit scratch files are staged (the repo is public).
- [ ] The next session can continue without manual repair.
