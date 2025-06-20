web: APP_PORT=3000 heroku-applink-service-mesh-latest-amd64 /app/startup.sh
worker: java $JAVA_OPTS -jar target/pricing-engine-0.0.1-SNAPSHOT.jar --spring.profiles.active=worker
