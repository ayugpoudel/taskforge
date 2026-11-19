FROM eclipse-temurin:21-jre-alpine
RUN adduser -D -u 10001 taskforge
USER 10001
WORKDIR /app
COPY build/libs/taskforge-0.1.0.jar /app/taskforge.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/taskforge.jar"]
