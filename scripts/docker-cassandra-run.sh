#!/bin/bash
sudo docker run -d --name cass1 -p 9160:9160 -p 9042:9042 poklet/cassandra start
CASS1_IP=$(sudo docker inspect --format '{{ .NetworkSettings.IPAddress }}' cass1)
sudo docker run -d --name cass2 poklet/cassandra start $CASS1_IP
sudo docker run -d --name cass3 poklet/cassandra start $CASS1_IP
