#!/bin/sh

exec java $JAVA_OPTS -jar target/pricing-engine-0.0.1-SNAPSHOT.jar --spring.profiles.active=web
