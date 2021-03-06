# ErgoPool Proxy
This project is a proxy server for [ergopool.io](https://ergopool.io)
## Setup
### Prerequisite
#### ErgoNode
To use the proxy, node [v3.2.7](https://github.com/ergoplatform/ergo/releases/tag/v3.2.7) or above is needed.
#### OpenJDK 11
[Here](https://jdk.java.net/java-se-ri/11), binaries for OpenJDK 11 exists that depend on your OS, you can install one.
#### sbt 1.2.8
Depend on your OS, you can follow instructions in [this](https://www.scala-sbt.org/1.0/docs/Setup.html) page to install sbt
#### Appkit library (develop - 60478389921a2aabe4d79f223f54a6bd12a09e4c)
Appkit require GraalVM (Community or Enterprise edition) to be
[downloaded](https://www.graalvm.org/downloads/) and installed. Community
edition should be enough for Ergo Appkit library.

#### Install GraalVM Community Edition on MacOS

First you need to download an archive with the [latest
release](https://github.com/oracle/graal/releases) of GraalVM (e.g.
`graalvm-ce-java8-linux-amd64-19.3.1.tar.gz`) for Linux and put the programs from it
onto the `$PATH`.

```shell script
cd <your/directory/with/downloaded/graal>
tar -zxf graalvm-ce-java8-linux-amd64-19.3.1.tar.gz
export GRAAL_HOME=<your/directory/with/downloaded/graal>/graalvm-ce-java8-19.3.1
export PATH=$PATH:${GRAAL_HOME}/bin
```
For mac, you can view Appkit [README](https://github.com/aslesarenko/ergo-appkit/tree/60478389921a2aabe4d79f223f54a6bd12a09e4c)
Now it's time to build Appkit:
```shell script
git clone https://github.com/aslesarenko/ergo-appkit.git -b develop
cd ergo-appkit
git checkout 60478389921a2aabe4d79f223f54a6bd12a09e4c
JAVA_HOME=$GRAAL_HOME sbt publishLocal
```
## Installation
Now you have to clone and build this project:
```shell script
git clone http://github.com/ergopool-io/proxy.git
cd ergo-proxy
sbt assembly
```
Now you should set your config. An example is available in `/config/application.conf`.
Copy that into a new file and set proper settings for all keys.  
After creating the config file, you can run your proxy:  
* For Windows:
```shell script
java -D"config.file"=path\to\config -jar ergo-proxy-assembly-0.4.jar
```
* For Unix:
```shell script
java -Dconfig.file=path/to/config -jar ergo-proxy-assembly-0.4.jar
```
Proxy needs a mnemonic in order to function. Every time the proxy is run, It creates a new mnemonic if one does not exists. you should save the current mnemonic by going to the swagger pannel and use `/proxy/mnemonic/save` route with your desired password. After that, everytime you run the proxy, you need to load it with the previously used paassword in the swagger panel.

## Docker Quick Start
  The proxy has officially supported [Docker package](https://hub.docker.com/r/ergopoolio/proxy), for using your config first create a config file and bind to `/home/ergo/proxy/proxy.conf` for example:
  
  ```shell
    $ docker run -p 9000:9000 \
      --restart=always \
      -v /path/on/host/to/ergo/configFile:/home/ergo/proxy/proxy.conf \
      -d ergopoolio/proxy:latest 
  ```

You can use `9000` port to load the proxy.
  
  NOTE: The `/path/on/host/to/ergo/configFile` directory must have `777` permission or have owner/group numeric id equal to `9052` to be writable by the container.
