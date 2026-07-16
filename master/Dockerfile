FROM eclipse-temurin:25-jdk-alpine

RUN apk add --no-cache su-exec && \
    addgroup -S craftpanel && adduser -S craftpanel -G craftpanel

WORKDIR /app

COPY build/install/master/ .
COPY docker-entrypoint.sh ./docker-entrypoint.sh

RUN mkdir -p certs && chmod +x docker-entrypoint.sh

EXPOSE 8080 50051

HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=5 \
    CMD wget -qO- http://localhost:8080/health || exit 1

ARG APP_VERSION=unknown
ENV APP_VERSION=$APP_VERSION

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["bin/master"]
