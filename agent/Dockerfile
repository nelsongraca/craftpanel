FROM eclipse-temurin:25-jdk-alpine

ARG DOCKER_GID=999

RUN apk add --no-cache su-exec pv && \
    addgroup -g ${DOCKER_GID} docker || true && \
    addgroup -g 1000 craftpanel && \
    adduser -u 1000 -G craftpanel -S craftpanel && \
    adduser craftpanel docker

WORKDIR /app

COPY build/install/agent/ .
COPY docker-entrypoint.sh ./docker-entrypoint.sh

RUN mkdir -p config && chmod +x docker-entrypoint.sh

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["bin/agent"]
