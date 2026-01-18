FROM openjdk:8-alpine
MAINTAINER Your Name <you@example.com>

ADD target/sandbag-0.0.1-SNAPSHOT-standalone.jar /sandbag/app.jar

EXPOSE 8080

CMD ["java", "-jar", "/sandbag/app.jar"]
