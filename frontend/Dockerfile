FROM node:22-alpine
WORKDIR /app
COPY .next/standalone/ .
COPY .next/static/ .next/static/
COPY public/ public/
EXPOSE 3000
ENV NODE_ENV=production
ENTRYPOINT ["node", "server.js"]
