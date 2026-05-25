FROM eclipse-temurin:25-jdk-alpine

RUN addgroup -S craftpanel && adduser -S craftpanel -G craftpanel

WORKDIR /app

COPY build/install/master/ .

USER craftpanel

EXPOSE 8080 50051

ENTRYPOINT ["bin/master"]
