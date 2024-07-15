FROM ghcr.io/topl/bifrost-node:2.0.0-beta3-29-11442de4
USER root
RUN curl -fL "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-linux.gz" | gzip -d > cs
RUN chmod +x cs
RUN mv cs /usr/local/bin
RUN cs setup --yes
## create alias in profile
RUN echo "#!/bin/bash" >> /usr/local/bin/brambl-cli
## run forwarding the parameters
RUN echo 'cs launch -r https://s01.oss.sonatype.org/content/repositories/releases co.topl:brambl-cli_2.13:2.0.0-beta6 -- $@' >> /usr/local/bin/brambl-cli
RUN chmod +x /usr/local/bin/brambl-cli
USER 1001:0
RUN brambl-cli wallet init --network private --password password --newwalletdb /tmp/wallet.db --mnemonicfile /tmp/mnemonic.txt  -o /tmp/keyfile.json
# ENTRYPOINT ["/opt/docker/bin/bifrost-node"]
# EXPOSE 9085 9084 9091