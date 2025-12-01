# Multi-stage build for Aurora Blue-Green Workload Simulator
# Stage 1: Build the application
FROM public.ecr.aws/docker/library/maven:3.9-amazoncorretto-17 AS builder

WORKDIR /build

# Copy POM file first for better caching
COPY pom.xml .

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime image
FROM public.ecr.aws/amazoncorretto/amazoncorretto:17

# Metadata
LABEL maintainer="AWS Solutions Architecture"
LABEL description="Aurora Blue-Green Deployment Workload Simulator"
LABEL version="1.0.0"

# Create application user
RUN yum install -y shadow-utils && \
    groupadd -r appuser && \
    useradd -r -g appuser appuser && \
    yum remove -y shadow-utils && \
    yum clean all

# Create application directory
WORKDIR /app

# Create logs directory
RUN mkdir -p /app/logs && chown -R appuser:appuser /app

# Copy JAR from builder stage
COPY --from=builder /build/target/workload-simulator.jar /app/workload-simulator.jar

# Switch to non-root user
USER appuser

# Expose Prometheus metrics port (if enabled)
EXPOSE 9090

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=40s --retries=3 \
  CMD java -cp /app/workload-simulator.jar com.aws.aurora.HealthCheck || exit 1

# Set JVM options for container environment
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+UseG1GC \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

# Entry point
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/workload-simulator.jar \"$@\"", "--"]

# Default command (can be overridden)
CMD ["--help"]
