![Thor wades rivers while the rest of the æsir ride across the bridge Bifröst as described in Grímnismál.](http://upload.wikimedia.org/wikipedia/commons/thumb/b/bc/Thor_wades_while_the_%C3%A6sir_ride_by_Fr%C3%B8lich.jpg/559px-Thor_wades_while_the_%C3%A6sir_ride_by_Fr%C3%B8lich.jpg).

# bifrost

Archive Kafka data safely to S3.

bifrost is a daemon that connects to Kafka, consumes all topics and
persists them to S3 for long term storage and analysis. It uses the
[baldr file format](https://github.com/uswitch/baldr) to store Kafka
messages. baldr-files are gzipped before they are written to disk.

There are other services for persisting messages from Kafka to S3, such
as [secor](https://github.com/pinterest/secor) from pinterest. The main
difference between bifrost and these is, that whereas they rely on the
Hadoop sequence files for storage, bifrost uses the baldr format. This
means that consumers of the persisted messages do not need to rely on
often very large implementations of libraries for reading Hadoop
sequence files. The baldr file format follows a minimal design that
allows for easy and quick streaming with a very small code-footprint. It
does not allow for arbitrary indexing.

## Usage

bifrost can be run directly from a checkout of the project by using
leiningen. The app requires some basic configuration, namely ZooKeeper
configuration to connect to Kafka and AWS credentials to store
baldr-files on S3. The project contains an example configuration in
`etc/config.edn.example`.

     $ lein run -- --config ./etc/config.edn

To run the app in production, we recomment building an uberjar and run
that on the app server.

    $ lein uberjar
    $ java -jar target/*-standalone.jar --config /opt/uswitch/bifrost/etc/config.edn

The Java temp-dir is used for storing baldr-files locally before
uploading them. Files are removed upon succesful upload and program
exit. To change the temp-directory, override `java.io.tmpdir`.

Logging is done through logback. To configure logback, please set
`logback.configurationFile`. The logback configuration is only respected
for uberjars. sl4j is used in development.

Here's a complete example of configuring and running an uberjar in
production.

    $ java -Djava.io.tmpdir=/mnt/bifrost-tmp \
           -Dlogback.configurationFile=/opt/uswitch/bifrost/etc/logback.xml \
           -server \
           -jar /opt/uswitch/bifrost/lib/*-standalone.jar \
           --config /opt/uswitch/bifrost/etc/config.edn

## License

Copyright © 2014 uSwitch

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
