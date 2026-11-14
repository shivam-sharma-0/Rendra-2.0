# Headless browser for the runtime check: loads a built site and reports runtime errors (probe.mjs).
# Runs as uid 1000 with no network; the site is mounted read-only.
FROM node:22-bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends chromium fonts-inter fonts-liberation fonts-noto-color-emoji \
 && rm -rf /var/lib/apt/lists/* \
 && printf '%s\n' '<?xml version="1.0"?>' '<!DOCTYPE fontconfig SYSTEM "fonts.dtd">' '<fontconfig>' \
    '<alias><family>sans-serif</family><prefer><family>Inter</family><family>Liberation Sans</family></prefer></alias>' \
    '<alias><family>system-ui</family><prefer><family>Inter</family></prefer></alias>' \
    '<alias><family>ui-sans-serif</family><prefer><family>Inter</family></prefer></alias>' \
    '<alias><family>serif</family><prefer><family>Liberation Serif</family></prefer></alias>' \
    '<alias><family>monospace</family><prefer><family>Liberation Mono</family></prefer></alias>' \
    '</fontconfig>' > /etc/fonts/local.conf
COPY probe.mjs relay.mjs outcomes.mjs /opt/codepal/
ENV CHROMIUM=/usr/bin/chromium
USER 1000:1000
WORKDIR /tmp
ENTRYPOINT ["node", "/opt/codepal/probe.mjs"]
