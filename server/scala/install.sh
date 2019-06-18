#!/bin/sh

cd ~

echo "deb https://dl.bintray.com/sbt/debian /" | sudo tee -a /etc/apt/sources.list.d/sbt.list
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv 2EE0EA64E40A89B84B2DF73499E82A75642AC823

sudo apt update
sudo apt install openjdk-8-jdk sbt

git clone https://github.com/seancheatham/tmstream.git
git clone https://github.com/bytedeco/javacpp.git -b 1.5
git clone https://github.com/bytedeco/javacpp-presets.git -b 1.5

cd ~/javacpp
mvn clean install -Djavacpp.platform=linux-x86_64

cd ~/javacpp-presets
mvn clean install .,opencv

cd ~/tmstream/server/scala
sbt clean
sbt -Djavacpp.platform=linux-x86_64 compile