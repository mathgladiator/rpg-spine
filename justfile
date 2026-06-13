build:
    mvn package -DskipTests=true
    mv target/rpg-spine-1.0-SNAPSHOT-jar-with-dependencies.jar spine.jar

demo: build
    java -jar spine.jar --input demo/schema.rpg

# launch the JavaFX editor over the demo/ directory
edit: build
    java -jar spine.jar --editor demo
