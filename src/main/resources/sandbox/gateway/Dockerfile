# Trusted relay between the backend and generated app servers; runs on the host network.
FROM node:22-bookworm-slim
COPY gateway.mjs /gateway/gateway.mjs
USER 1000:1000
CMD ["node", "/gateway/gateway.mjs"]
