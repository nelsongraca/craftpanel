FROM eclipse-temurin:25-jdk-alpine

ARG DOCKER_GID=999

RUN apk add --no-cache su-exec && \
    addgroup -g ${DOCKER_GID} docker || true && \
    addgroup -S craftpanel && \
    adduser -S craftpanel -G craftpanel && \
    adduser craftpanel docker

WORKDIR /app

COPY build/install/agent/ .
COPY docker-entrypoint.sh ./docker-entrypoint.sh

RUN mkdir -p config && chmod +x docker-entrypoint.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["bin/agent"]
