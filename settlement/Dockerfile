# syntax=docker/dockerfile:1

ARG JAVA_VERSION=21


########################################
# Development stage for devcontainer
########################################
FROM eclipse-temurin:${JAVA_VERSION}-jdk-jammy AS dev

ARG USERNAME=vscode
ARG USER_UID=1000
ARG USER_GID=1000
ENV DEBIAN_FRONTEND=noninteractive

# curl: get installer for Claude Code
# ripgrep: Used for grep in Claude Code
# postgresql-client: Connect to DB via container
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        less \
        postgresql-client \
        procps \
        ripgrep \
        sudo \
        unzip \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid ${USER_GID} ${USERNAME} \
    && useradd --uid ${USER_UID} --gid ${USER_GID} --create-home --shell /bin/bash ${USERNAME} \
    && echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > /etc/sudoers.d/${USERNAME} \
    && chmod 0440 /etc/sudoers.d/${USERNAME}

# login with non-root user
USER ${USERNAME}
ENV PATH=/home/${USERNAME}/.local/bin:${PATH}

RUN curl -fsSL https://claude.ai/install.sh | bash \
    && claude --version

WORKDIR /workspace



########################################
# Build stage
########################################
FROM eclipse-temurin:${JAVA_VERSION}-jdk-jammy AS builder

WORKDIR /build

COPY --chmod=755 mvnw ./
COPY .mvn .mvn
COPY pom.xml ./
RUN ./mvnw -B dependency:go-offline

COPY src src
RUN ./mvnw -B clean package -DskipTests \
    && cp target/*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted


########################################
# Production stage
########################################
FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy AS prod

WORKDIR /application

RUN groupadd --system spring && useradd --system --gid spring spring

COPY --from=builder --chown=spring:spring /build/extracted/dependencies/ ./
COPY --from=builder --chown=spring:spring /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:spring /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:spring /build/extracted/application/ ./

USER spring
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "application.jar"]
