# Web app (not scaffolded yet)

One Next.js (App Router, TypeScript) codebase will serve both the public checkout and the installable PWA (ADR 0003).

Planned structure:

```
web/
  app/
    g/[slug]/        public goal page and checkout (server-rendered, minimal JavaScript)
    receipt/[id]/    live receipt page
    (organizer)/     signed-in organizer and member screens
  lib/api/           client generated from specs/api/openapi.yaml
  public/            manifest, icons
```

Rules from the constitution that apply here: guests never need to install or sign in (VI.1), public pages stay small and work in WhatsApp's in-app browser (VI.2), and tests reference criteria with `criterion("AC-...")` so the traceability check can see them.

Scaffold with `npx create-next-app@latest` when FEAT-001's backend slice is green.
