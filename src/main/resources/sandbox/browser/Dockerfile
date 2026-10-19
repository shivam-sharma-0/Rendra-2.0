# Headless browser for the runtime check: loads a built site and reports runtime errors (probe.mjs).
# Runs as uid 1000 with no network; the site is mounted read-only.
FROM node:22-bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends chromium fonts-liberation fonts-noto-color-emoji \
 && rm -rf /var/lib/apt/lists/*
COPY probe.mjs relay.mjs outcomes.mjs /opt/codepal/
ENV CHROMIUM=/usr/bin/chromium
USER 1000:1000
WORKDIR /tmp
ENTRYPOINT ["node", "/opt/codepal/probe.mjs"]
