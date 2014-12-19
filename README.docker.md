deployment prerequesites
------------------------

- You will need an account on [Docker Hub](http://hub.docker.com) and be a member of the MastodonC organization.

- You will need to be logged in to that account from docker's point of view (You only need to do this once).

```
docker login
```

- You need to arrange to have docker listening on a tcp port... It should start with a command like (no trailing slash on the tcp://* bit):

```
/usr/bin/docker -d -H fd:// -H tcp://localhost:2375
```

deployment
----------

To build the ``kixi.eventlog`` docker image and publish it to [Docker Hub](http://hub.docker.com)

```
lein clean

# this throws exceptions about LOGSTASH port is undefined that can be ignored
lein uberimage

docker push mastodonc/kixi.bifrost:latest
