# syntax=docker/dockerfile:1
#
# Backend del CMS: imagen de ejecución con JRE 21, sin herramientas de compilación.
# Imágenes base con versión y digest fijados (multiarquitectura: amd64 y arm64).

ARG JDK_IMAGE=eclipse-temurin:21.0.13_9-jdk-noble@sha256:114cabb73033ca4ce7ceec3deb62396bc1b716282bae051ed3c1d82e38fcab2d
ARG JRE_IMAGE=eclipse-temurin:21.0.13_9-jre-noble@sha256:0eda5830ed7b669abc246a8dc520ea8a23cab06f9a8d4b6964109e2f2394e46f

# --- 1. Compilación (Maven 3.9.14 a través del wrapper, con su SHA-256 verificado) ---------
FROM ${JDK_IMAGE} AS build
WORKDIR /build

# Sin unzip, el wrapper descargaría el .tar.gz en lugar del .zip cuyo SHA-256 está fijado.
RUN apt-get update \
  && apt-get install -y --no-install-recommends unzip \
  && rm -rf /var/lib/apt/lists/*

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Los tests se ejecutan fuera de la imagen (necesitan Docker para PostgreSQL): mvnw verify.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
  && java -Djarmode=tools -jar target/cms-api-*.jar extract --layers --launcher --destination extracted

# --- 2. Ejecución ------------------------------------------------------------------------
FROM ${JRE_IMAGE}

RUN groupadd --system cms && useradd --system --gid cms --no-create-home --shell /usr/sbin/nologin cms
WORKDIR /app

# Capas de Spring Boot: las dependencias cambian poco y se reutilizan entre builds.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

USER cms
EXPOSE 8080

# Memoria: el heap se ajusta al límite del contenedor (la medición y los límites, en la 6.1.4).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=45 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
