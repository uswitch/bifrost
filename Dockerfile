FROM mastodonc/basejava:latest
MAINTAINER support <support@mastodonc.com>

ADD docker/start-bifrost.sh start-bifrost.sh

CMD ["/bin/bash", "/start-bifrost"]