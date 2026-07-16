FROM node:22-alpine
WORKDIR /app
COPY .next/standalone/ .
COPY .next/static/ .next/static/
COPY public/ public/
EXPOSE 3000
ENV NODE_ENV=production
ENV HOSTNAME=0.0.0.0

HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=5 \
    CMD wget -qO- http://localhost:3000/ || exit 1

ENTRYPOINT ["node", "server.js"]
