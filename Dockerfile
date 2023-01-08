FROM maven:3.6.3-openjdk-11 AS builder
COPY . /usr/src/app
WORKDIR /usr/src/app
RUN mvn clean install -DskipTests=true 

FROM eclipse-temurin:11-jre-ubi9-minimal
LABEL maintainer="SNOMED International <tooling@snomed.org>"
ARG SUID=1042
ARG SGID=1042

VOLUME /tmp

# Create necessary directories
RUN mkdir /app
WORKDIR /app
RUN mkdir /snomed-drools-rules

# Copy jar from builder image
COPY --from=builder /usr/src/app/target/snowstorm-*.jar snowstorm.jar

# Create the snowstorm user
RUN groupadd -g $SGID snowstorm && \
    useradd -g snowstorm -u $SUID snowstorm

# Change permissions.
RUN chown -R snowstorm:snowstorm /app

# Run as the snowstorm user.
USER snowstorm
EXPOSE 8080
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","snowstorm.jar"]
