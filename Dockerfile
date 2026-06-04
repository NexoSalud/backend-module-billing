FROM eclipse-temurin:17-jre

RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline

COPY src ./src

RUN mvn clean package -DskipTests

EXPOSE 8087

ENTRYPOINT ["java", "-jar", "target/billing-service-0.0.1-SNAPSHOT.jar"]
