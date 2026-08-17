# AstraDB 多阶段构建（构建 → 运行）
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# 先拷贝 pom 以利用层缓存
COPY pom.xml ./
COPY core/pom.xml core/
COPY server/pom.xml server/
# maven 本地仓库固定在镜像内（避免宿主仓库依赖）
RUN mvn -q -Dmaven.repo.local=/build/.m2 -pl server -am dependency:go-offline || true
COPY core/src core/src
COPY server/src server/src
RUN mvn -q -Dmaven.repo.local=/build/.m2 -pl server -am package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/server/target/astradb-server-*.jar app.jar
EXPOSE 8080
VOLUME /data
ENV ASTRA_DB_DATA_DIR=/data
ENTRYPOINT ["java", "-jar", "app.jar", "--astradb.data-dir=/data"]
