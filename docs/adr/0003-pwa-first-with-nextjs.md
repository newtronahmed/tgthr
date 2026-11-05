# ADR 0003: PWA first with Next.js, native app later

- Status: Accepted
- Date: 2026-11-04
- Deciders: Ahmed Zubairu
- Constitution articles touched: VI, VII

## Context

Most contributors arrive from a WhatsApp link and will not install anything. Organizers and returning members benefit from installation and notifications, but a native app would double the front-end work before launch. WhatsApp's in-app browser usually offers no install prompt, and iOS web push works only for installed web apps.

## Decision

We will build one Next.js (App Router, TypeScript) web app that serves the public checkout and an installable PWA. Public pages are server-rendered so link previews and first load are fast. All business rules stay in the Spring Boot API behind the OpenAPI contract, so a native app or a Capacitor wrapper can reuse it later.

## Consequences

**Good**

- One front-end codebase and no app-store wait.
- Link previews with proper Open Graph tags.
- A later native client needs no backend changes.

**Costs and risks**

- Push reach on iOS is partial; SMS is the fallback for guests.
- The install prompt needs guidance to open the page in Safari or Chrome.

## Alternatives considered

| Option | Why not |
| --- | --- |
| Flutter or React Native app first | Second front end before launch; guests would not install it |
| Thymeleaf pages from Spring Boot only | Lightest option, but two UI stacks once the organizer app is added |
