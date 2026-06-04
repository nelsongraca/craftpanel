FROM eclipse-temurin:25-jdk-alpine

RUN apk add --no-cache su-exec && \
    addgroup -S craftpanel && adduser -S craftpanel -G craftpanel

WORKDIR /app

COPY build/install/master/ .
COPY docker-entrypoint.sh ./docker-entrypoint.sh

RUN mkdir -p certs && chmod +x docker-entrypoint.sh

EXPOSE 8080 50051

ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["bin/master"]
