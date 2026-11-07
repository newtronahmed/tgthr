.PHONY: up down test trace trace-strict lint-api check format web-check

GRADLE ?= $(shell test -x backend/gradlew && echo ./gradlew || echo gradle)

up:
	docker compose -f infra/docker-compose.yml up -d

down:
	docker compose -f infra/docker-compose.yml down

test:
	cd backend && $(GRADLE) test

trace:
	python3 tools/check_traceability.py

trace-strict:
	python3 tools/check_traceability.py --strict

lint-api:
	npx --yes @stoplight/spectral-cli lint specs/api/openapi.yaml --ruleset specs/api/.spectral.yaml

format:
	cd backend && $(GRADLE) spotlessApply

check: trace lint-api
	cd backend && $(GRADLE) spotlessCheck check

web-check:
	cd web && npm run build
