# build

cd ..;./gradlew clean jar

#copy

cp ../build/libs/solr-kafka-streaming-1.0-SNAPSHOT.jar lib


#start

docker-compose up --build -d

# setup


docker exec -it solr solr zk upconfig -d /configs/collection -n collection

docker exec -it solr solr zk upconfig -d /configs/checkpointCollection -n checkpointCollection

docker exec -it solr solr create_collection -c collection -n collection

docker exec -it solr solr create_collection -c checkpointCollection -n checkpointCollection

docker exec -it solr post -c collection /docs/docs.csv

# watch


