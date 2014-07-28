# spray-basic-authentication-cluster-sharding
`spray-basic-authentication-cluster-sharding`, uses the [Akka cluster-sharding extension](http://doc.akka.io/docs/akka/2.3.4/contrib/cluster-sharding.html#cluster-sharding)
to distribute actors over nodes. Cluster sharding at its core uses [akka-persistence](http://doc.akka.io/docs/akka/2.3.4/scala/persistence.html),
for its own state which has nothing to do with the Actor state of our problem domain so you will have to configure it. 

This example shows how to model a simple domain of `User`s that must authenticate using Basic Authentication to have 
access to the '/secure' context of the web service. The '/api' and '/web' are available to anyone.

All nodes, the 'seed', and the 'non-seed' a.k.a. normal nodes are part of the cluster and the nodes will use the seed
as the initial contact point for the cluster. All nodes, also the seed will have the ShardRegion actor started and all
nodes, the seed included will store the `User` domain's state. 

The `User` is a PersistentActor that will store its state in using [akka-persistence-cassandra](https://github.com/krasserm/akka-persistence-cassandra) 
in a Cassandra cluster of three nodes. This way the data is always available, event when nodes crash so the solution is always
available.

There is no 'UserService' Actor in this example. The solution uses akka cluster sharding that will determine, based upon the 
an ID of a user, and two functions, the `idExtractor` and the `shardResolver` on which node of the cluster to create the 
User `Entry` Actor. The entry will be the Actor/Aggregate/Entry, in true Domain Driven Design fashion, and as such is in 
control of the User domain. The client must know what the ID is of the Entry to send commands to it. In our case, 
the username is the ID of the entry, so the correct shardregion can be resolved. To recap, the ShardRegion actor creates 
an Entry based upon a determined node that is responsible for the shard at that point in time. When the actor has been
created, the message will be delivered to the `Entry`.

In the authentication example, clients know their username and passwords, and we don't have to look it up in a table. 
When an `Entry` actor (the User) is a `PersistentActor`, and when the actor is created on a node, its state will be recovered and
then the `Entry` will have its last known state. This is very handy when more nodes are added to the cluster and the `Entries`
will be rebalanced over the nodes. Because the user knows its username, the ShardRegion can determine where to send the
`Authenticate` message to, and the appropriate User `Entry` can respond.

For the user interface the authenticate flow is not enough. To get an index of the available entries over all the shards, 
a cluster singleton 'UserView' actor will index the available Entries. It is also a PersistentActor but its focus is only 
to index the entries, it does not do anything else. When a list of entries will be requested, the actor can be used. Because this 
actor could have a stable persistenceId, multiple views could be used to distribute Query commands, but in this example 
I've opted just to query the Actor directly, but for more complicated indexes the Views are a better option.

This is the simplest way to get an index. Solr and other distributed index solutions have modeled a way to create an index
from distributed information, but I have not tried to create such a service so far.
                                   
# Docker
This example can be run using [Docker](http://docker.io) and I would strongly advice using Docker and just take a few 
hours to work through the guide on how to use this *great* piece of software.

## Run the example
The example is highly 'clusterized', meaning we must run a small cassandra cluster and the example as a cluster. Using scripts, this will become
much easier. The configuration I have based upon 
[Michael Hamrah's blog post - Running an Akka Cluster with Docker Containers](http://blog.michaelhamrah.com/2014/03/running-an-akka-cluster-with-docker-containers/).

We must first run the cassandra cluster and then the example as a cluster using scripts

## Launching cassandra
To launch cassandra use the following script:

    $ scripts/docker-cassandra-run.sh
    
This will launch three cassandra instances:

    $ sudo docker ps

    CONTAINER ID        IMAGE                     COMMAND             CREATED             STATUS              PORTS                                                                                                       NAMES
    8b75e4bfed66        poklet/cassandra:latest   start 172.17.0.2    18 hours ago        Up 2 hours          22/tcp, 61621/tcp, 7000/tcp, 7001/tcp, 7199/tcp, 8012/tcp, 9042/tcp, 9160/tcp                               cass3
    08cc7b31fc38        poklet/cassandra:latest   start 172.17.0.2    18 hours ago        Up 2 hours          22/tcp, 61621/tcp, 7000/tcp, 7001/tcp, 7199/tcp, 8012/tcp, 9042/tcp, 9160/tcp                               cass2
    6a02660b6104        poklet/cassandra:latest   start               18 hours ago        Up 2 hours          22/tcp, 61621/tcp, 7000/tcp, 7001/tcp, 7199/tcp, 8012/tcp, 0.0.0.0:9042->9042/tcp, 0.0.0.0:9160->9160/tcp   cass1

## Launching the example cluster

    $ scripts/docker-run.sh
    
This will launch three instances of the cluster example:
    
    $ sudo docker ps   
     
    CONTAINER ID        IMAGE                                COMMAND                CREATED              STATUS              PORTS                                                                           NAMES
    ee577288c09d        dnvriend/spray-ba-sharding:latest   /appl/start /bin/bas   12 seconds ago      Up 10 seconds       0.0.0.0:49169->2552/tcp, 0.0.0.0:49170->8080/tcp                                                            node2
    4c6199bdbf33        dnvriend/spray-ba-sharding:latest   /appl/start /bin/bas   13 seconds ago      Up 11 seconds       0.0.0.0:49167->2552/tcp, 0.0.0.0:49168->8080/tcp                                                            node1
    e0ff5b15a007        dnvriend/spray-ba-sharding:latest   /appl/start /bin/bas   13 seconds ago      Up 12 seconds       0.0.0.0:49165->2552/tcp, 0.0.0.0:49166->8080/tcp                                                            node1/seed,node2/seed,seed
    8b75e4bfed66        poklet/cassandra:latest             start 172.17.0.2       18 hours ago        Up 2 hours          22/tcp, 61621/tcp, 7000/tcp, 7001/tcp, 7199/tcp, 8012/tcp, 9042/tcp, 9160/tcp                               cass3,node1/cas3,node1/seed/cas3,node2/cas3,node2/seed/cas3,seed/cas3
    08cc7b31fc38        poklet/cassandra:latest             start 172.17.0.2       18 hours ago        Up 2 hours          22/tcp, 61621/tcp, 7000/tcp, 7001/tcp, 7199/tcp, 8012/tcp, 9042/tcp, 9160/tcp                               cass2,node1/cas2,node1/seed/cas2,node2/cas2,node2/seed/cas2,seed/cas2
    6a02660b6104        poklet/cassandra:latest             start                  18 hours ago        Up 2 hours          22/tcp, 61621/tcp, 7000/tcp, 7001/tcp, 7199/tcp, 8012/tcp, 0.0.0.0:9042->9042/tcp, 0.0.0.0:9160->9160/tcp   cass1,node1/cas1,node1/seed/cas1,node2/cas1,node2/seed/cas1,seed/cas1

## Seeds and nodes
The oldest node is called the leader of the cluster, and that is where the Cluster Singleton Actor will be run. When that node  
will crash or there is no communication, another node will be leader of the cluster and that is where the Cluster Singleton will be
re-created. Because we are using Akka persistence and Apache Cassandra, the Actor state will be preserved and the application will
keep running, pretty cool! 

Seeds are just nodes that know about the cluster. New nodes will use seed to get information about the cluster. All nodes of the cluster are
just that, instances that work together in the cluster. The oldest node is called the 'leader' and has special rights in the cluster. For more
information read the [Akka Cluster Specification](http://doc.akka.io/docs/akka/2.3.4/common/cluster.html)

## Log output
To view the log output:

    $ scripts/docker-log-seed.sh
    $ scripts/docker-log-node1.sh

## The AngularJS admin screen
In this example, there are multiple instances of the admin application, but the state is the cluster singleton. On which
node the singleton runs is to no concern of ours, only that we can access the state. Akka-cluster takes care of that for
us. Looking at the 'sudo docker ps' command above, you'll see that in this example, I have multiple options to access the 
admin screen. The ports may be different on your system.

Point the browser to one of the following url (change the port to your mapped port):

    http://192.168.99.99:49170/web/index.html    
    http://192.168.99.99:49168/web/index.html    
    http://192.168.99.99:49166/web/index.html    
    
The Basic Authentication secured context is:
    
    http://192.168.99.99:49170/secure
    http://192.168.99.99:49168/secure
    http://192.168.99.99:49166/secure
 
## Stopping and removing all the images    
To stop and remove all the images from your system, use the following scripts:

    $ scripts/docker-remove.sh
    $ scripts/docker-cassandra-remove.sh
    
# Akka Cluster Magic
Lets see some cluster magic in action! 

## Single UserView in the cluster 
Access the admin screen using the first URL above and create a user. Next navigate to the second and third URL and ensure 
that the user is already there! See, the nodes know where to find the cluster singleton instance!

## Sharding in action!
Now lets create some users. Because the ID is based upon the hashcode of the username, names that are similar will be 
created on the same shard. So we will use several different user names. But first we want to see the logging from
all of the nodes, so start three terminals and type:

Terminal1:

    $ sudo docker logs -f seed

Terminal 2:

    $ sudo docker logs -f node1

Terminal 3: 

    $ sudo docker logs -f node2

Launch the user interface in your browser, use any of the nodes:    
    
    http://192.168.99.99:49166/web/index.html
     
Click on the User creds tab and create a user, notice that the UserView, the `ClusterSingleton` is created on the oldest
node, and is polling the view for data:

    ...
    [INFO] [07/28/2014 12:21:37.278] [ClusterSystem-akka.actor.default-dispatcher-18] [akka.tcp://ClusterSystem@172.17.0.5:2552/user/userViewSingleton/userView] Getting all users
    [INFO] [07/28/2014 12:21:37.778] [ClusterSystem-akka.actor.default-dispatcher-18] [akka.tcp://ClusterSystem@172.17.0.5:2552/user/userViewSingleton/userView] Getting all users
    [INFO] [07/28/2014 12:21:38.279] [ClusterSystem-akka.actor.default-dispatcher-17] [akka.tcp://ClusterSystem@172.17.0.5:2552/user/userViewSingleton/userView] Getting all users
    [INFO] [07/28/2014 12:21:38.780] [ClusterSystem-akka.actor.default-dispatcher-17] [akka.tcp://ClusterSystem@172.17.0.5:2552/user/userViewSingleton/userView] Getting all users
    ...

Create some users, 'aa' that in my case is created on the 'seed' node:

    [INFO] [07/28/2014 12:22:16.221] [ClusterSystem-akka.actor.default-dispatcher-2] [akka.tcp://ClusterSystem@172.17.0.5:2552/user/sharding/Users/aa] Creating entry: Create(aa,aa)
    
User 'zz' also on the 'seed' node:
        
    [INFO] [07/28/2014 12:23:18.355] [ClusterSystem-akka.actor.default-dispatcher-16] [akka.tcp://ClusterSystem@172.17.0.5:2552/user/sharding/Users/zz] Creating entry: Create(zz,aa)
   
User 'kk' on 'node1':
    
    [INFO] [07/28/2014 12:23:41.852] [ClusterSystem-akka.actor.default-dispatcher-22] [akka.tcp://ClusterSystem@172.17.0.6:2552/user/sharding/Users/kk] Creating entry: Create(kk,aa)
    
User 'gg' on 'node2':
    
    [INFO] [07/28/2014 12:25:31.051] [ClusterSystem-akka.actor.default-dispatcher-20] [akka.tcp://ClusterSystem@172.17.0.7:2552/user/sharding/Users/gg] Creating entry: Create(gg,aa)
    
They are all added to the `ClusterSingleton` `UserView`:
    
    [INFO] [07/28/2014 12:25:31.093] [ClusterSystem-akka.actor.default-dispatcher-19] [akka.tcp://ClusterSystem@172.17.0.5:2552/user/userViewSingleton/userView] List(UserWithoutPassword(gg), UserWithoutPassword(kk), UserWithoutPassword(zz), UserWithoutPassword(aa))
    
Now authenticate (I will use httpie for this):
   
    http -a gg:aa http://192.168.99.99:49166/secure
   
Use eg. the 'gg' user, that lives on 'node2':
   
   [INFO] [07/28/2014 12:28:01.408] [ClusterSystem-akka.actor.default-dispatcher-4] [akka.tcp://ClusterSystem@172.17.0.7:2552/user/sharding/Users/gg] Recovering entry: Created(State(1406546731077,gg,200d52699cf2f2f0008e4a7c10b111a5619d600d542485e63829519900895a3c726b26612e811b4699bc3ea8284922bebcda6c7fca53aa59799afa29862c62c4,3dfceb27e749079162e0270696d15ba1))
   [INFO] [07/28/2014 12:28:01.426] [ClusterSystem-akka.actor.default-dispatcher-17] [akka.tcp://ClusterSystem@172.17.0.7:2552/user/sharding/Users/gg] Authenticating: Authenticate(Some(UserPass(gg,aa)))
   [INFO] [07/28/2014 12:28:01.612] [ClusterSystem-akka.actor.default-dispatcher-6] [akka.tcp://ClusterSystem@172.17.0.7:2552/user/sharding/Users/gg] Authenticating: Authenticate(Some(UserPass(gg,aa)))

As you can see, the `User` is recoved, because it uses Passivation, that means, to save memory, the actor can be unloaded from memory and all messages to that actor will be cached. The Actor
can be recovered, and will consume memory again, but all the state is there. Next the Authenticate message will be received by the User Actor, and it can execute the authenticate logic.

## Rebalancing!
Lets shut down 'node2':

    $ sudo docker stop node2
    
    $ sudo docker ps
    
    CONTAINER ID        IMAGE                                COMMAND                CREATED             STATUS              PORTS                                                                           NAMES
66e0322b22aa        dnvriend/spray-ba-sharding:latest   /appl/start /bin/bas   16 minutes ago      Up 16 minutes       0.0.0.0:49173->2552/tcp, 0.0.0.0:49174->8080/tcp                                                            node1
6b7376f5da8e        dnvriend/spray-ba-sharding:latest   /appl/start /bin/bas   16 minutes ago      Up 16 minutes       0.0.0.0:49171->2552/tcp, 0.0.0.0:49172->8080/tcp                                                            node1/seed,node2/seed,seed
8b75e4bfed66        poklet/cassandra:latest             start 172.17.0.2       18 hours ago        Up 3 hours          22/tcp, 61621/tcp, 7000/tcp, 7001/tcp, 7199/tcp, 8012/tcp, 9042/tcp, 9160/tcp                               cass3,node1/cas3,node1/seed/cas3,node2/cas3,node2/seed/cas3,seed/cas3
08cc7b31fc38        poklet/cassandra:latest             start 172.17.0.2       18 hours ago        Up 3 hours          22/tcp, 61621/tcp, 7000/tcp, 7001/tcp, 7199/tcp, 8012/tcp, 9042/tcp, 9160/tcp                               cass2,node1/cas2,node1/seed/cas2,node2/cas2,node2/seed/cas2,seed/cas2
6a02660b6104        poklet/cassandra:latest             start                  18 hours ago        Up 3 hours          22/tcp, 61621/tcp, 7000/tcp, 7001/tcp, 7199/tcp, 8012/tcp, 0.0.0.0:9042->9042/tcp, 0.0.0.0:9160->9160/tcp   cass1,node1/cas1,node1/seed/cas1,node2/cas1,node2/seed/cas1,seed/cas1

As we can see, the 'node2' is now dead and gone. Only 'seed' and 'node1' are running. Let's authenticate again with 'gg'. You will be saying, hang on, 
the 'gg' user lives on 'node2' and that node is gone, and so is the state, but after I authenticate again I see on the  'seed' node, the following log:

    [INFO] [07/28/2014 12:34:04.112] [ClusterSystem-akka.actor.default-dispatcher-17] [akka.tcp://ClusterSystem@172.17.0.5:2552/user/sharding/Users/gg] Recovering entry: Created(State(1406546731077,gg,200d52699cf2f2f0008e4a7c10b111a5619d600d542485e63829519900895a3c726b26612e811b4699bc3ea8284922bebcda6c7fca53aa59799afa29862c62c4,3dfceb27e749079162e0270696d15ba1))
    [INFO] [07/28/2014 12:34:04.129] [ClusterSystem-akka.actor.default-dispatcher-17] [akka.tcp://ClusterSystem@172.17.0.5:2552/user/sharding/Users/gg] Authenticating: Authenticate(Some(UserPass(gg,aa)))

The Actor has been recreated on the 'seed' node, and it can authenticate the 'gg' user no problem at all! Let's start 'node2' again:

    $ sudo docker start node2
    $ sudo docker logs -f node2

Authenticate again:
 
    http -a gg:aa http://192.168.99.99:49166/secure
        
The User entry 'gg' did not move, now create a whole lot of users:

    $ scripts/create-users.sh

Now use the bulk login script:

    $ scripts/authenticate-users.sh
    
As you see, all three nodes help authenticating request    

Stop 'node1' and 'node2' and use the bulk login script:

    $ scripts/authenticate-users.sh

It takes some time before the cluster has lost the nodes, but authentication will continue, the 'seed' node:
    
    $ scripts/authenticate-users.sh
    
All User entries will be restored on the 'seed' node, all authentication will be done by the 'seed' node only, While the 
script is authenticating, start 'node1' and 'node2' again:

    $ sudo docker start node1
    $ sudo docker start node2
    
After some time the other nodes will receive user entries, the cluster knows about the new ShardRegions, and the entries
have been moved from 'seed' to 'node1' and 'node2'. 

## Viewing the journal/snapshot data
The other nodes, most likely are 172.17.0.2 (cass1) 172.17.0.3 (cass2) and 172.17.0.4 (cass3). To verify that the data
is stored on all the nodes, we should query them: 

    $ sudo docker run -ti poklet/cassandra /bin/bash

Then type:
    
    bash-4.1# cqlsh 172.17.0.2
    Connected to Test Cluster at 172.17.0.2:9160.
    [cqlsh 4.1.1 | Cassandra 2.0.6 | CQL spec 3.1.1 | Thrift protocol 19.39.0]
    Use HELP for help.
    cqlsh>

Alternatively you  could also run cqlsh directly:
    
    $ sudo docker run -ti poklet/cassandra cqlsh 172.17.0.3

By default, the journal messages are stored in the keyspace 'akka' and in the table 'messages':
    
    cqlsh> select * from akka.messages;

By default, snapshots are stored in the keyspace 'akka_snapshot' and in the table 'snapshots':
    
    cqlsh> select * from akka_snapshot.snapshots;

Login to all the nodes, and verify the data can be resolved.
    
# Httpie
We will use the *great* tool [httpie](https://github.com/jakubroztocil/httpie), so please install it:

# REST API
## Getting a list of users
        
    $ http http://localhost:8080/api/users
    
## Get a user by name
The username has been encoded in the path
    
    $ http http://localhost:8080/api/users/foo
    
## Adding or updating users

    $ http POST http://localhost:8080/api/users username="foo" password="bar"
    
## Updating users    

    $ http PUT http://localhost:8080/api/users username="foo" password="bar"
    
## Deleting a user
The username has been encoded in the path

    $ http DELETE http://localhost:8080/api/users/foo
    
# The secured resource
The resource that has been secured for us is in the /secure context

## No authentication

    $ http http://localhost:8080/secure

## Basic Authentication
    
    $ http -a foo:bar http://localhost:8080/secure
    
# User Credentials CRUD Interface
The application should automatically launch your browser and show the CRUD screen, but if it doesn't:

    http://localhost:8080/web/index.html
        
Have fun!