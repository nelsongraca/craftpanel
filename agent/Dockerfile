FROM eclipse-temurin:25-jdk-alpine

ARG DOCKER_GID=999

RUN addgroup -g ${DOCKER_GID} docker || true && \
    addgroup -S craftpanel && \
    adduser -S craftpanel -G craftpanel && \
    adduser craftpanel docker

RUN mkdir -p /etc/craftpanel && chown craftpanel:craftpanel /etc/craftpanel

WORKDIR /app

COPY build/install/agent/ .

USER craftpanel

ENTRYPOINT ["bin/agent"]
