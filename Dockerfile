# The API image (doc/plain/DevSecOps.md C2). CI builds and tests the jar first (./mvnw verify), so this only packages it.
# On your PC:  ./mvnw package -DskipTests && docker build -t modern-stock-api .
FROM eclipse-temurin:24-jre-noble

# Ubuntu's security fixes published since the base image was built, then the user the API runs as
RUN apt-get update && apt-get upgrade -y --no-install-recommends && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --no-create-home app
WORKDIR /app
# Owned by root: the app user can read the jar but not change it, so a compromised app can't replace its own code
COPY target/modern_stock_api.jar app.jar
USER 10001

ENV SPRING_PROFILES_ACTIVE=prod
# Render free has 512 MB and a tenth of a CPU: a small heap, the serial GC and the quick JIT start faster and fit.
# A JAVA_TOOL_OPTIONS variable in Render replaces this line without a rebuild.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
