build:
    mvn package -DskipTests=true
    mv target/rpg-spine-1.0-SNAPSHOT-jar-with-dependencies.jar spine.jar

demo: build
    java -jar spine.jar --input demo/schema.rpg
